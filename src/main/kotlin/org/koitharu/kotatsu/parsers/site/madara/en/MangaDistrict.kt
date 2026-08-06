package org.koitharu.kotatsu.parsers.site.madara.en

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGA_DISTRICT", "MangaDistrict", "en", ContentType.HENTAI)
internal class MangaDistrict(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGA_DISTRICT, "mangadistrict.com", pageSize = 30) {

	override val tagPrefix = "publication-genre/"

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		return try {
			val pages = page + 1

			val url = buildString {
				append("https://")
				append(domain)

				if (pages > 1) {
					append("/page/")
					append(pages.toString())
				}
				append("/?s=")

				filter.query?.let {
					append(filter.query.urlEncoded())
				}

				append("&post_type=wp-manga")

				if (filter.tags.isNotEmpty()) {
					filter.tags.forEach {
						append("&genre[]=")
						append(it.key)
					}
				}

				filter.states.forEach {
					append("&status[]=")
					when (it) {
						MangaState.ONGOING -> append("on-going")
						MangaState.FINISHED -> append("end")
						MangaState.ABANDONED -> append("canceled")
						MangaState.PAUSED -> append("on-hold")
						MangaState.UPCOMING -> append("upcoming")
						else -> throw IllegalArgumentException("$it not supported")
					}
				}

				filter.contentRating.oneOrThrowIfMany()?.let {
					append("&adult=")
					append(
						when (it) {
							ContentRating.SAFE -> "0"
							ContentRating.ADULT -> "1"
							else -> ""
						},
					)
				}

				if (filter.year != 0) {
					append("&release=")
					append(filter.year.toString())
				}

				if (!filter.author.isNullOrEmpty()) {
					filter.author.let {
						append("&author=")
						append(it.lowercase().replace(" ", "-"))
					}
				}

				append("&m_orderby=")
				when (order) {
					SortOrder.POPULARITY -> append("views")
					SortOrder.UPDATED -> append("latest")
					SortOrder.NEWEST -> append("new-manga")
					SortOrder.ALPHABETICAL -> append("alphabet")
					SortOrder.RATING -> append("rating")
					SortOrder.RELEVANCE -> {}
					else -> {}
				}
			}
			parseMangaList(webClient.httpGet(url).parseHtml())
		} catch (e: Exception) {
			emptyList()
		}
	}
    
	override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
		val slug = manga.url.removeSuffix('/').substringAfterLast('/')
		val doc2 = webClient.httpPost(
			"https://$domain/series/$slug/ajax/chapters/",
			mapOf(),
		).parseHtml()
		val ul = doc2.body().selectFirstOrThrow("ul")
		val dateFormat = SimpleDateFormat(datePattern, Locale.US)
		return ul.select("li").mapChapters(reversed = true) { i, li ->
			val a = li.selectFirst("a")
			val href = a?.attrAsRelativeUrlOrNull("href") ?: li.parseFailed("Link is missing")
			MangaChapter(
				id = generateUid(href),
				title = a.ownText(),
				number = i + 1f,
				volume = 0,
				url = href,
				uploadDate = parseChapterDate(
					dateFormat,
					li.selectFirst("span.chapter-release-date i")?.text(),
				),
				source = source,
				scanlator = null,
				branch = null,
			)
		}
	}
}
