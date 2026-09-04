package org.koitharu.kotatsu.parsers.site.all

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import java.util.EnumSet
import java.util.LinkedHashMap
import java.util.Locale

internal abstract class OniSagaParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	private val languageCode: String?,
) : PagedMangaParser(context, source, PAGE_SIZE), Interceptor {

	override val configKeyDomain = ConfigKey.Domain("onisaga.com")
	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isMultipleTagsSupported = true,
		isTagsExclusionSupported = true,
		isSearchSupported = true,
		isSearchWithFiltersSupported = true,
		isYearSupported = true,
	)

	private val readerRateLock = Mutex()

	@Volatile
	private var lastReaderRequestTime = 0L

	private val readerTokens = object : LinkedHashMap<String, String>(READER_TOKEN_CACHE_SIZE, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
			size > READER_TOKEN_CACHE_SIZE
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
		keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://$domain/")
		.build()

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val originalUrl = unwrapImageProxy(request.url)
		val encodedReferer = originalUrl.fragment
			?.takeIf { it.startsWith(IMAGE_REFERER_FRAGMENT) }
			?.removePrefix(IMAGE_REFERER_FRAGMENT)
		val referer = encodedReferer?.let {
			runCatching { context.decodeBase64(it).toString(Charsets.UTF_8) }.getOrNull()
		}
		val imageRequest = if (referer == null) {
			request
		} else {
			request.newBuilder()
				.url(originalUrl.newBuilder().fragment(null).build())
				.header("Referer", referer)
				.build()
		}
		return chain.proceed(imageRequest)
	}

	private fun unwrapImageProxy(url: HttpUrl): HttpUrl = when (url.host) {
		"wsrv.nl" -> url.queryParameter("url")?.toHttpUrlOrNull() ?: url
		"v.recipes" -> url.toString()
			.substringAfter("https://v.recipes/i/", "")
			.takeIf(String::isNotEmpty)
			?.toHttpUrlOrNull()
			?: url
		else -> url
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = GENRES.mapTo(linkedSetOf()) {
			MangaTag(key = it.second, title = it.first, source = source)
		},
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
			MangaState.ABANDONED,
		),
		availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.ADULT),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.NOVEL,
			ContentType.ONE_SHOT,
			ContentType.DOUJINSHI,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim()?.nullIfEmpty()
		val pageUrl = if (query == null) {
			"https://$domain/browse"
		} else {
			"https://$domain".toHttpUrl().newBuilder()
				.addPathSegment("search")
				.addPathSegment(query)
				.build()
				.toString()
		}
		val updates = filter.toLivewireUpdates(order)
		val stateKey = "$pageUrl\n${updates.cacheKey()}"
		val directPage = page == 1 && updates.isDefault()
		var initialState = getCachedState(INITIAL_LIST_STATES, pageUrl)
		var initialDocument: Document? = null
		if (directPage || initialState == null) {
			initialDocument = webClient.httpGet(pageUrl).parseHtml()
			initialState = initialDocument.extractLivewireState(POST_FILTER_COMPONENT)
			if (initialState != null) cacheState(INITIAL_LIST_STATES, pageUrl, initialState)
		}
		val document = if (directPage) {
			checkNotNull(initialDocument)
		} else {
			val state = getCachedState(ACTIVE_LIST_STATES, stateKey)
				?: initialState
				?: throw ParseException("Could not find Livewire browse state", pageUrl)
			val result = fetchLivewirePage(pageUrl, state, page, updates)
			cacheState(ACTIVE_LIST_STATES, stateKey, result.state)
			result.document
		}
		val contentRating = filter.contentRating.oneOrThrowIfMany()
		return parseMangaList(document).filter {
			contentRating == null || it.contentRating == contentRating
		}
	}

	private suspend fun fetchLivewirePage(
		pageUrl: String,
		state: LivewireState,
		page: Int,
		updates: LivewireUpdates,
	): LivewirePage {
		val payload = createLivewirePayload(
			state = state,
			updates = updates.toJson(),
			method = "gotoPage",
			params = JSONArray().put(page.toString()),
		)
		val response = webClient.httpPost(
			"https://$domain/livewire/update".toHttpUrl(),
			payload,
			livewireHeaders(pageUrl),
		).parseJson()
		val component = response.firstComponent()
			?: throw ParseException("Empty Livewire browse response", pageUrl)
		val html = component
			.optJSONObject("effects")
			?.getStringOrNull("html")
			.orEmpty()
		val nextState = component.getStringOrNull("snapshot")?.let { LivewireState(it, state.token) } ?: state
		return LivewirePage(Jsoup.parseBodyFragment(html, "https://$domain"), nextState)
	}

	private fun parseMangaList(document: Document): List<Manga> =
		document.select("div.relative.group").mapNotNull { it.toManga() }.distinctBy(Manga::url)

	private fun Element.toManga(): Manga? {
		val link = if (tagName() == "a") this else selectFirst("a[href*=\"/manga/\"]") ?: return null
		val url = link.attrAsRelativeUrlOrNull("href") ?: return null
		val segments = url.substringBefore('?').trim('/').split('/')
		if (segments.firstOrNull() != "manga" || segments.getOrNull(1).isNullOrEmpty()) return null
		val titleElement = selectFirst("div[data-flux-heading], h3, h4") ?: selectFirst("a[title]") ?: link
		val title = titleElement.attr("title").ifEmpty { titleElement.text() }.trim().nullIfEmpty() ?: return null
		val isAdult = selectFirst("span:containsOwn(18+)") != null
		return Manga(
			id = generateUid(url),
			title = title,
			altTitles = emptySet(),
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			rating = RATING_UNKNOWN,
			contentRating = if (isAdult) ContentRating.ADULT else ContentRating.SAFE,
			coverUrl = selectFirst("img[alt]:not([alt='']), img")?.resolveImageUrl(),
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val document = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		return parseDetails(document, manga).copy(chapters = fetchChapters(document))
	}

	private fun parseDetails(document: Document, fallback: Manga): Manga {
		val infoSection = document.selectFirst("div.flex.flex-col.md\\:flex-row")
		val title = document.selectFirst("h1, [data-flux-heading]")?.text()?.nullIfEmpty() ?: fallback.title
		val cover = document.selectFirst(".w-32 > picture img, div.flex.flex-col.md\\:flex-row picture img")
			?.resolveImageUrl()
			?: fallback.coverUrl
		val altTitles = document.selectFirst("p[class*=\"text-[13px]\"]")?.text()
			?.split(INTERPUNCT_REGEX)
			?.mapNotNull { it.trim().nullIfEmpty() }
			?.filterNotTo(linkedSetOf()) { it.equals(title, ignoreCase = true) }
			.orEmpty()
		val rating = document.selectFirst("span.text-xs")?.text()
			?.let { RATING_REGEX.find(it)?.groupValues?.get(1)?.toFloatOrNull() }
			?.takeIf { it > 0f }
			?.div(10f)
			?: fallback.rating
		val tags = infoSection?.select("a[href*=\"/genre/\"]")?.mapNotNullTo(linkedSetOf()) { element ->
			val name = element.text().trim().nullIfEmpty() ?: return@mapNotNullTo null
			val id = element.attr("href").substringBefore('?').trimEnd('/').substringAfterLast('/').nullIfEmpty()
				?: GENRES.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
				?: return@mapNotNullTo null
			MangaTag(id, name, source)
		}.orEmpty()
		return fallback.copy(
			title = title,
			altTitles = altTitles,
			coverUrl = cover,
			largeCoverUrl = cover,
			rating = rating,
			contentRating = if (document.selectFirst("span:containsOwn(18+)") != null) {
				ContentRating.ADULT
			} else {
				ContentRating.SAFE
			},
			tags = tags,
			state = parseState(document),
			authors = infoSection?.select("a[href*=\"/author/\"]")
				?.mapNotNullTo(linkedSetOf()) { it.text().trim().nullIfEmpty() }
				.orEmpty(),
			description = document.selectFirst("p.leading-relaxed")?.text()?.nullIfEmpty(),
		)
	}

	private fun parseState(document: Document): MangaState? {
		val text = (
			document.selectFirst("span:has(> span.size-1\\.5)")?.text()
				?: document.selectFirst(
					"span.inline-flex:matchesOwn(Completed|Ongoing|Hiatus|Cancelled|Releasing)",
				)?.text()
			)?.lowercase(Locale.ROOT)
			?: return null
		return when {
			"ongoing" in text || "releasing" in text -> MangaState.ONGOING
			"completed" in text -> MangaState.FINISHED
			"hiatus" in text -> MangaState.PAUSED
			"cancelled" in text || "dropped" in text -> MangaState.ABANDONED
			else -> null
		}
	}

	private suspend fun fetchChapters(document: Document): List<MangaChapter> = coroutineScope {
		val state = document.extractLivewireState(CHAPTER_LIST_COMPONENT)
			?: return@coroutineScope emptyList()
		val languages = languageCode?.let(::listOf) ?: LANGUAGE_CODES
		languages.map { code ->
			async { fetchLanguageChapters(document.location(), state, code) }
		}.awaitAll().flatten()
			.distinctBy(MangaChapter::url)
			.groupBy(MangaChapter::branch)
			.values
			.sortedByDescending(List<MangaChapter>::size)
			.flatMap { branch -> branch.sortedBy(MangaChapter::number) }
	}

	private suspend fun fetchLanguageChapters(
		referer: String,
		initialState: LivewireState,
		code: String,
	): List<MangaChapter> {
		var snapshot = initialState.snapshot
		var previousSize = 0
		var chapters = emptyList<MangaChapter>()
		repeat(MAX_CHAPTER_REQUESTS) {
			val response = webClient.httpPost(
				"https://$domain/livewire/update".toHttpUrl(),
				createLivewirePayload(
					state = LivewireState(snapshot, initialState.token),
					updates = JSONObject().put("language", code),
					method = "loadMoreChapters",
					params = JSONArray(),
					callCount = CHAPTER_LOAD_BATCH,
				),
				livewireHeaders(referer),
			).parseJson()
			val component = response.firstComponent() ?: return chapters
			val html = component.optJSONObject("effects")?.getStringOrNull("html") ?: return chapters
			val parsed = parseChapters(Jsoup.parseBodyFragment(html, "https://$domain"), code)
			if (parsed.size <= previousSize) return chapters
			chapters = parsed
			previousSize = parsed.size
			snapshot = component.getStringOrNull("snapshot") ?: return chapters
		}
		return chapters
	}

	private fun parseChapters(document: Document, language: String): List<MangaChapter> {
		val raw = ArrayList<RawChapter>()
		document.select("a.gap-4:has(div[data-flux-heading])").forEach { element ->
			val url = element.attrAsRelativeUrlOrNull("href")?.takeIf { "/read/" in it } ?: return@forEach
			raw.add(
				RawChapter(
					number = element.chapterNumber() ?: return@forEach,
					url = url,
					date = parseRelativeDate(element.chapterDateText()),
					group = null,
				),
			)
		}
		document.select("ui-dropdown:has(button div[data-flux-heading])").forEach { dropdown ->
			val button = dropdown.selectFirst("button") ?: return@forEach
			val number = button.chapterNumber() ?: return@forEach
			val date = parseRelativeDate(button.chapterDateText())
			var unknownIndex = 1
			dropdown.select("ui-menu a[data-flux-menu-item]").forEach { link ->
				val url = link.attrAsRelativeUrlOrNull("href")?.takeIf { "/read/" in it } ?: return@forEach
				val rawGroup = (
					link.selectFirst("span.text-sm")?.text()
						?: link.selectFirst("div.flex.items-center.gap-2 > span:not(.ml-auto)")?.text()
					).orEmpty().trim()
				val group = if (rawGroup.isEmpty() || rawGroup.equals("Unknown group", ignoreCase = true)) {
					"Unknown ${unknownIndex++}"
				} else {
					rawGroup
				}
				raw.add(RawChapter(number, url, date, group))
			}
		}
		val hasGroups = raw.any { it.group != null }
		return raw.groupBy { chapter ->
			val group = if (hasGroups) chapter.group ?: DEFAULT_GROUP else null
			if (languageCode == null) listOfNotNull(language, group).joinToString(" · ") else group
		}.flatMap { (branch, items) ->
			items.distinctBy(RawChapter::number).map { chapter ->
				MangaChapter(
					id = generateUid(chapter.url),
					title = null,
					number = chapter.number,
					volume = 0,
					url = chapter.url,
					scanlator = chapter.group,
					uploadDate = chapter.date,
					branch = branch,
					source = source,
				)
			}
		}
	}

	private fun Element.chapterNumber(): Float? {
		val heading = selectFirst("div[data-flux-heading]")?.text()
		val fallback = selectFirst("div.w-10")?.text()
		val text = heading?.replaceFirst(Regex("""^Chapter\s+""", RegexOption.IGNORE_CASE), "")
			?.trim()
			?.nullIfEmpty()
			?: fallback
		return text?.toFloatOrNull() ?: text?.let {
			CHAPTER_NUMBER_REGEX.find(it)?.groupValues?.get(1)?.toFloatOrNull()
		}
	}

	private fun Element.chapterDateText(): String = selectFirst("p[data-flux-text]")?.text()
		?.replace(" - ", " · ")
		?.split(INTERPUNCT_REGEX)
		?.firstOrNull { part ->
			val text = part.lowercase(Locale.ROOT)
			"ago" in text || "today" in text || "yesterday" in text
		}
		.orEmpty()

	private fun parseRelativeDate(value: String): Long {
		val text = value.lowercase(Locale.ROOT)
		if (text.isEmpty()) return 0L
		val now = System.currentTimeMillis()
		if ("today" in text) return now
		if ("yesterday" in text) return now - DAY_MILLIS
		val match = RELATIVE_DATE_REGEX.find(text) ?: return 0L
		val amount = match.groupValues[1].toLongOrNull() ?: return 0L
		val multiplier = when (match.groupValues[2]) {
			"minute" -> MINUTE_MILLIS
			"hour" -> HOUR_MILLIS
			"day" -> DAY_MILLIS
			"week" -> WEEK_MILLIS
			"month" -> MONTH_MILLIS
			"year" -> YEAR_MILLIS
			else -> return 0L
		}
		return now - amount * multiplier
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val body = webClient.httpGet(chapterUrl).parseRaw()
		val token = READER_TOKEN_REGEX.find(body)?.groupValues?.get(1)?.nullIfEmpty()
			?: throw ParseException("Could not find reader token", chapterUrl)
		putReaderToken(chapterUrl, token)
		val orders = PAGE_ORDER_REGEX.findAll(body)
			.mapNotNull { it.groupValues[1].toIntOrNull() }
			.distinct()
			.sorted()
			.toList()
		if (orders.isEmpty()) throw ParseException("Could not find reader pages", chapterUrl)
		return orders.map { order ->
			val key = "$chapterUrl#$order"
			MangaPage(
				id = generateUid(key),
				url = key,
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun getPageUrl(page: MangaPage): String {
		val chapterUrl = page.url.substringBeforeLast('#')
		val order = page.url.substringAfterLast('#').toIntOrNull()
			?: throw ParseException("Invalid reader page", page.url)
		val chapterId = chapterUrl.toHttpUrl().pathSegments.lastOrNull()?.nullIfEmpty()
			?: throw ParseException("Invalid chapter URL", chapterUrl)
		val apiUrl = "https://$domain/api/chapter/$chapterId/page/$order"
		repeat(READER_RETRIES) {
			var shouldRefreshToken = false
			val imageUrl = try {
				readerRateLock.withLock {
					val remaining = READER_DELAY_MILLIS - (System.currentTimeMillis() - lastReaderRequestTime)
					if (remaining > 0L) delay(remaining)
					val token = getReaderToken(chapterUrl) ?: loadReaderToken(chapterUrl)
					try {
						val response = webClient.httpGet(apiUrl, readerHeaders(token, chapterUrl))
						response.header("x-reader-token-next")?.nullIfEmpty()?.let {
							putReaderToken(chapterUrl, it)
						}
						val json = response.parseJson()
						json.getStringOrNull("url")?.toAbsoluteUrl(domain)?.let { return@withLock it }
						val message = json.getStringOrNull("message")
						if (message?.contains("token", ignoreCase = true) == true) {
							removeReaderToken(chapterUrl)
							shouldRefreshToken = true
							return@withLock null
						}
						throw ParseException(message ?: "Reader API error", apiUrl)
					} finally {
						lastReaderRequestTime = System.currentTimeMillis()
					}
				}
			} catch (error: HttpStatusException) {
				when (error.statusCode) {
					401, 403 -> {
						removeReaderToken(chapterUrl)
						shouldRefreshToken = true
					}
					429 -> delay(READER_DELAY_MILLIS)
					else -> throw error
				}
				null
			}
			if (imageUrl != null) {
				val encodedReferer = context.encodeBase64(chapterUrl.toByteArray(Charsets.UTF_8))
				return "${imageUrl.substringBefore('#')}#$IMAGE_REFERER_FRAGMENT$encodedReferer"
			}
			if (shouldRefreshToken) loadReaderToken(chapterUrl)
		}
		throw ParseException("Failed to fetch image after $READER_RETRIES attempts", apiUrl)
	}

	private suspend fun loadReaderToken(chapterUrl: String): String {
		val body = webClient.httpGet(chapterUrl).parseRaw()
		return READER_TOKEN_REGEX.find(body)?.groupValues?.get(1)?.nullIfEmpty()?.also {
			putReaderToken(chapterUrl, it)
		} ?: throw ParseException("Could not refresh reader token", chapterUrl)
	}

	private fun getReaderToken(chapterUrl: String): String? = synchronized(readerTokens) {
		readerTokens[chapterUrl]
	}

	private fun putReaderToken(chapterUrl: String, token: String) = synchronized(readerTokens) {
		readerTokens[chapterUrl] = token
	}

	private fun removeReaderToken(chapterUrl: String) = synchronized(readerTokens) {
		readerTokens.remove(chapterUrl)
	}

	private fun readerHeaders(token: String, referer: String): Headers = getRequestHeaders().newBuilder()
		.set("X-Reader-Token", token)
		.set("Sec-Fetch-Mode", "cors")
		.set("Sec-Fetch-Site", "same-origin")
		.set("Referer", referer)
		.build()

	override suspend fun getRelatedManga(seed: Manga): List<Manga> {
		val document = webClient.httpGet(seed.url.toAbsoluteUrl(domain)).parseHtml()
		val heading = document.select("div[data-flux-heading], h3, h2").firstOrNull {
			val text = it.text().lowercase(Locale.ROOT)
			"recommended" in text || "related" in text || "you may also like" in text
		} ?: return emptyList()
		val section = heading.parents().firstOrNull {
			it.select("div.relative.group, a[href*='/manga/']").size > 1
		} ?: return emptyList()
		return section.select("div.relative.group, a[href*='/manga/']:has(img)")
			.mapNotNull { it.toManga() }
			.filterNot { it.url == seed.url }
			.distinctBy(Manga::url)
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		if (link.host != domain) return null
		val segments = link.pathSegments.filter(String::isNotEmpty)
		val mangaUrl = when (segments.firstOrNull()) {
			"manga" -> "https://$domain/manga/${segments.getOrNull(1) ?: return null}"
			"read" -> webClient.httpGet(link).parseHtml()
				.selectFirst("a[href*=\"/manga/\"]")?.absUrl("href")?.nullIfEmpty()
				?: return null
			else -> return null
		}
		val document = webClient.httpGet(mangaUrl).parseHtml()
		val slug = mangaUrl.toHttpUrl().pathSegments.last()
		val stub = Manga(
			id = generateUid("/manga/$slug"),
			title = slug.replace('-', ' ').replaceFirstChar { it.titlecase(sourceLocale) },
			altTitles = emptySet(),
			url = "/manga/$slug",
			publicUrl = mangaUrl,
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
		return parseDetails(document, stub)
	}

	private fun Element.resolveImageUrl(): String? {
		for (attribute in IMAGE_ATTRIBUTES) {
			val value = attr(attribute)
			if (value.isNotEmpty() && !value.startsWith("data:")) {
				return absUrl(attribute).nullIfEmpty() ?: value.toAbsoluteUrl(domain)
			}
		}
		return null
	}

	private fun Document.extractLivewireState(componentName: String): LivewireState? {
		val token = selectFirst("meta[name=csrf-token]")?.attr("content")?.nullIfEmpty()
			?: selectFirst("input[name=_token]")?.attr("value")?.nullIfEmpty()
			?: return null
		for (element in select("*")) {
			val snapshot = element.attributes().firstOrNull { it.key.endsWith("snapshot") }?.value ?: continue
			if (componentName in snapshot) return LivewireState(snapshot, token)
		}
		return null
	}

	private fun livewireHeaders(referer: String): Headers = getRequestHeaders().newBuilder()
		.set("X-Livewire", "")
		.set("Accept", "application/json")
		.set("X-Requested-With", "XMLHttpRequest")
		.set("Origin", "https://$domain")
		.set("Referer", referer.substringBefore('?'))
		.build()

	private fun createLivewirePayload(
		state: LivewireState,
		updates: JSONObject,
		method: String,
		params: JSONArray,
		callCount: Int = 1,
	): JSONObject = JSONObject()
		.put("_token", state.token)
		.put(
			"components",
			JSONArray().put(
				JSONObject()
					.put("snapshot", state.snapshot)
					.put("updates", updates)
					.put("calls", JSONArray().apply {
						repeat(callCount) {
							put(
								JSONObject()
									.put("type", "call")
									.put("path", "")
									.put("method", method)
									.put("params", params),
							)
						}
					}),
			),
		)

	private fun getCachedState(
		cache: MutableMap<String, CachedLivewireState>,
		key: String,
	): LivewireState? = synchronized(cache) {
		val cached = cache[key] ?: return@synchronized null
		if (System.currentTimeMillis() - cached.createdAt > LIST_STATE_CACHE_TTL) {
			cache.remove(key)
			null
		} else {
			cached.state
		}
	}

	private fun cacheState(
		cache: MutableMap<String, CachedLivewireState>,
		key: String,
		state: LivewireState,
	) = synchronized(cache) {
		cache[key] = CachedLivewireState(state, System.currentTimeMillis())
	}

	private fun JSONObject.firstComponent(): JSONObject? = optJSONArray("components")?.optJSONObject(0)

	private fun MangaListFilter.toLivewireUpdates(order: SortOrder): LivewireUpdates {
		val selectedYear = year.takeUnless { it == YEAR_UNKNOWN }
		return LivewireUpdates(
			platform = types.oneOrThrowIfMany()?.toPlatform().orEmpty(),
			status = states.oneOrThrowIfMany()?.toStatus().orEmpty(),
			sort = order.toLivewireSort(),
			releaseStart = selectedYear?.let { "$it-01-01" },
			releaseEnd = selectedYear?.let { "$it-12-31" },
			genres = tags.map(MangaTag::key),
			excludedGenres = tagsExclude.map(MangaTag::key),
		)
	}

	private fun SortOrder.toLivewireSort(): String = when (this) {
		SortOrder.POPULARITY -> "view"
		SortOrder.RATING -> "vote_average"
		SortOrder.NEWEST -> "release_date"
		SortOrder.ALPHABETICAL -> "title"
		else -> "created_at"
	}

	private fun ContentType.toPlatform(): String? = when (this) {
		ContentType.MANGA -> "MANGA"
		ContentType.MANHWA -> "MANHWA"
		ContentType.MANHUA -> "MANHUA"
		ContentType.NOVEL -> "NOVEL"
		ContentType.ONE_SHOT -> "ONE-SHOT"
		ContentType.DOUJINSHI -> "DOUJINSHI"
		else -> null
	}

	private fun MangaState.toStatus(): String? = when (this) {
		MangaState.ONGOING -> "ongoing"
		MangaState.FINISHED -> "completed"
		MangaState.PAUSED -> "hiatus"
		MangaState.ABANDONED -> "cancelled"
		else -> null
	}

	private data class LivewireState(val snapshot: String, val token: String)
	private data class LivewirePage(val document: Document, val state: LivewireState)
	private data class CachedLivewireState(val state: LivewireState, val createdAt: Long)
	private data class RawChapter(val number: Float, val url: String, val date: Long, val group: String?)

	private data class LivewireUpdates(
		val platform: String = "",
		val status: String = "",
		val sort: String = "created_at",
		val minimumChapters: String = "",
		val group: String? = null,
		val releaseStart: String? = null,
		val releaseEnd: String? = null,
		val genres: List<String> = emptyList(),
		val excludedGenres: List<String> = emptyList(),
	) {
		fun cacheKey(): String = listOf(
			platform,
			status,
			sort,
			minimumChapters,
			group.orEmpty(),
			releaseStart.orEmpty(),
			releaseEnd.orEmpty(),
			genres.sorted().joinToString(","),
			excludedGenres.sorted().joinToString(","),
		).joinToString("|")

		fun isDefault(): Boolean = platform.isEmpty() &&
			status.isEmpty() &&
			sort == "created_at" &&
			minimumChapters.isEmpty() &&
			group == null &&
			releaseStart == null &&
			releaseEnd == null &&
			genres.isEmpty() &&
			excludedGenres.isEmpty()

		fun toJson(): JSONObject = JSONObject()
			.put("platform", platform)
			.put("status", status)
			.put("sort", sort)
			.put("min_chapters", minimumChapters)
			.put("group", group ?: JSONObject.NULL)
			.put("release_start", releaseStart ?: JSONObject.NULL)
			.put("release_end", releaseEnd ?: JSONObject.NULL)
			.put("genre", JSONArray(genres))
			.put("excludeGenre", JSONArray(excludedGenres))
	}

	@MangaSourceParser("ONISAGA", "OniSaga")
	class All(context: MangaLoaderContext) : OniSagaParser(context, MangaParserSource.ONISAGA, null)

	@MangaSourceParser("ONISAGA_EN", "OniSaga (English)", "en")
	class English(context: MangaLoaderContext) : OniSagaParser(context, MangaParserSource.ONISAGA_EN, "EN")

	@MangaSourceParser("ONISAGA_FR", "OniSaga (Français)", "fr")
	class French(context: MangaLoaderContext) : OniSagaParser(context, MangaParserSource.ONISAGA_FR, "FR")

	@MangaSourceParser("ONISAGA_JA", "OniSaga (日本語)", "ja")
	class Japanese(context: MangaLoaderContext) : OniSagaParser(context, MangaParserSource.ONISAGA_JA, "JA")

	@MangaSourceParser("ONISAGA_PT_BR", "OniSaga (Português Brasileiro)", "pt")
	class BrazilianPortuguese(context: MangaLoaderContext) :
		OniSagaParser(context, MangaParserSource.ONISAGA_PT_BR, "PT-BR")

	@MangaSourceParser("ONISAGA_PT", "OniSaga (Português)", "pt")
	class Portuguese(context: MangaLoaderContext) : OniSagaParser(context, MangaParserSource.ONISAGA_PT, "PT")

	@MangaSourceParser("ONISAGA_ES_419", "OniSaga (Español Latinoamérica)", "es")
	class LatinAmericanSpanish(context: MangaLoaderContext) :
		OniSagaParser(context, MangaParserSource.ONISAGA_ES_419, "ES-LA")

	@MangaSourceParser("ONISAGA_ES", "OniSaga (Español)", "es")
	class Spanish(context: MangaLoaderContext) : OniSagaParser(context, MangaParserSource.ONISAGA_ES, "ES")

	private companion object {
		const val PAGE_SIZE = 24
		const val CHAPTER_LOAD_BATCH = 10
		const val MAX_CHAPTER_REQUESTS = 10
		const val READER_RETRIES = 3
		const val READER_DELAY_MILLIS = 2_000L
		const val READER_TOKEN_CACHE_SIZE = 16
		const val LIST_STATE_CACHE_SIZE = 12
		const val LIST_STATE_CACHE_TTL = 15 * 60_000L
		const val IMAGE_REFERER_FRAGMENT = "onisaga-ref:"
		const val POST_FILTER_COMPONENT = "post-filter"
		const val CHAPTER_LIST_COMPONENT = "manga.chapter-list"
		const val DEFAULT_GROUP = "Default"
		const val MINUTE_MILLIS = 60_000L
		const val HOUR_MILLIS = 3_600_000L
		const val DAY_MILLIS = 86_400_000L
		const val WEEK_MILLIS = 604_800_000L
		const val MONTH_MILLIS = 2_592_000_000L
		const val YEAR_MILLIS = 31_536_000_000L

		val IMAGE_ATTRIBUTES = arrayOf("data-src", "data-lazy-src", "src")
		val LANGUAGE_CODES = listOf("EN", "FR", "JA", "PT-BR", "PT", "ES-LA", "ES")
		val INITIAL_LIST_STATES =
			object : LinkedHashMap<String, CachedLivewireState>(LIST_STATE_CACHE_SIZE, 0.75f, true) {
				override fun removeEldestEntry(
					eldest: MutableMap.MutableEntry<String, CachedLivewireState>?,
				): Boolean = size > LIST_STATE_CACHE_SIZE
			}
		val ACTIVE_LIST_STATES =
			object : LinkedHashMap<String, CachedLivewireState>(LIST_STATE_CACHE_SIZE, 0.75f, true) {
				override fun removeEldestEntry(
					eldest: MutableMap.MutableEntry<String, CachedLivewireState>?,
				): Boolean = size > LIST_STATE_CACHE_SIZE
			}
		val READER_TOKEN_REGEX = Regex("""readerToken["']?\s*:\s*["']([^"']+)["']""")
		val PAGE_ORDER_REGEX = Regex("""["']?order["']?\s*:\s*(\d+)""")
		val CHAPTER_NUMBER_REGEX = Regex("""(?:Chapter\s+)?([\d.]+)""", RegexOption.IGNORE_CASE)
		val RELATIVE_DATE_REGEX = Regex("""(\d+)\s+(minute|hour|day|week|month|year)s?\s+ago""")
		val RATING_REGEX = Regex("""(\d+(?:\.\d+)?)""")
		val INTERPUNCT_REGEX = Regex("""\s*·\s*""")

		val GENRES = """
			Action:1|Adaptation:61|Adult:67|Adventure:6|Aliens:84|Avant Garde:43|Award Winning:78|
			Boys Love:31|Comedy:2|Comics:90|Crazy MC:59|Crime:98|Demon:57|Demons:5|Doujinshi:79|
			Drama:15|Dungeons:56|Ecchi:29|Erotica:68|Fantasy:7|Full Color:62|Game:46|Gender Bender:75|
			Genderswap:63|Genius MC:49|Girls Love:28|Gore:80|Gourmet:42|Harem:37|Hentai:76|
			Historical:66|Horror:16|Isekai:3|Iyashikei:34|Josei:35|Kids:38|Lolicon:70|Long Strip:64|
			Magic:8|Magical Girls:99|Mahou Shoujo:41|Martial Arts:11|Mature:45|Mecha:36|Medical:101|
			Military:17|Monster Girls:88|Monsters:81|Murim:47|Music:30|Mystery:19|Necromancer:54|
			Overpowered:55|Parody:12|Philosophical:100|Post-Apocalyptic:85|Psychological:18|
			Regression:52|Reincarnation:48|Revenge:51|Reverse Harem:44|Romance:20|Samurai:86|
			School:21|School Life:24|Sci-Fi:13|Seinen:14|Self-Published:82|Shotacon:77|Shoujo:27|
			Shoujo Ai:73|Shounen:4|Shounen Ai:72|Slice of Life:26|Smut:69|Space:22|Sports:32|
			Super Power:9|Superhero:89|Supernatural:10|Survival:87|Suspense:39|System:50|Thriller:40|
			Time Travel:23|Tower:58|Tragedy:25|Vampire:33|Villain:53|Violence:60|Web Comic:65|
			Wuxia:113|Yaoi:74|Yuri:71
		""".trimIndent()
			.replace("\n", "")
			.split('|')
			.map { value -> value.substringBefore(':').trim() to value.substringAfter(':').trim() }
	}
}
