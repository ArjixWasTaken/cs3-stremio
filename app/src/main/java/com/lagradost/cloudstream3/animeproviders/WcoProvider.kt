package com.lagradost.cloudstream3.animeproviders

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.WcoStream
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.*


class WcoProvider : MainAPI() {
    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA") || t.contains("Special")) TvType.ONA
            else if (t.contains("Movie")) TvType.AnimeMovie
            else TvType.Anime
        }
    }

    override val mainUrl = "https://wcostream.cc"
    override val name = "WCO Stream"
    override val hasQuickSearch = true
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.Anime,
        TvType.ONA
    )

    override fun getMainPage(): HomePageResponse {
        val urls = listOf(
            Pair("$mainUrl/ajax/list/recently_updated?type=tv", "Recently Updated Anime"),
            Pair("$mainUrl/ajax/list/recently_updated?type=movie", "Recently Updated Movies"),
            Pair("$mainUrl/ajax/list/recently_added?type=tv", "Recently Added Anime"),
            Pair("$mainUrl/ajax/list/recently_added?type=movie", "Recently Added Movies"),
        )

        val items = ArrayList<HomePageList>()
        for (i in urls) {
            try {
                val response = JSONObject(
                    app.get(
                        i.first,
                    ).text
                ).getString("html") // I won't make a dataclass for this shit
                val document = Jsoup.parse(response)
                val results = document.select("div.flw-item").map {
                    val filmPoster = it.selectFirst("> div.film-poster")
                    val filmDetail = it.selectFirst("> div.film-detail")
                    val nameHeader = filmDetail.selectFirst("> h3.film-name > a")
                    val title = nameHeader.text().replace(" (Dub)", "")
                    val href =
                        nameHeader.attr("href").replace("/watch/", "/anime/").replace("-episode-.*".toRegex(), "/")
                    val isDub = filmPoster.selectFirst("> div.film-poster-quality")?.text()?.contains("DUB") ?: false
                    val poster = filmPoster.selectFirst("> img").attr("data-src")
                    val set: EnumSet<DubStatus> =
                        EnumSet.of(if (isDub) DubStatus.Dubbed else DubStatus.Subbed)
                    AnimeSearchResponse(title, href, this.name, TvType.Anime, poster, null, set)
                }
                items.add(HomePageList(i.second, results))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }


    private fun fixAnimeLink(url: String): String {
        val regex = "watch/([a-zA-Z\\-0-9]*)-episode".toRegex()
        val (aniId) = regex.find(url)!!.destructured
        return "$mainUrl/anime/$aniId"
    }

    private fun parseSearchPage(soup: Document): ArrayList<SearchResponse> {
        val items = soup.select(".film_list-wrap > .flw-item")
        if (items.isEmpty()) return ArrayList()
        val returnValue = ArrayList<SearchResponse>()
        for (i in items) {
            val href = fixAnimeLink(i.selectFirst("a").attr("href"))
            val img = fixUrl(i.selectFirst("img").attr("data-src"))
            val title = i.selectFirst("img").attr("title")
            val isDub = !i.select(".pick.film-poster-quality").isEmpty()
            val year = i.selectFirst(".film-detail.film-detail-fix > div > span:nth-child(1)").text().toIntOrNull()
            val type = i.selectFirst(".film-detail.film-detail-fix > div > span:nth-child(3)").text()

            returnValue.add(
                if (getType(type) == TvType.AnimeMovie) {
                    MovieSearchResponse(
                        title, href, this.name, TvType.AnimeMovie, img, year
                    )
                } else {
                    AnimeSearchResponse(
                        title,
                        href,
                        this.name,
                        TvType.Anime,
                        img,
                        year,
                        EnumSet.of(if (isDub) DubStatus.Dubbed else DubStatus.Subbed),
                    )
                }
            )
        }
        return returnValue
    }

    override fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search"
        val response =
            app.get(url, params = mapOf("keyword" to query))
        var document = Jsoup.parse(response.text)
        val returnValue = parseSearchPage(document)

        while (!document.select(".pagination").isEmpty()) {
            val link = document.select("a.page-link[rel=\"next\"]")
            if (!link.isEmpty()) {
                val extraResponse = app.get(fixUrl(link[0].attr("href"))).text
                document = Jsoup.parse(extraResponse)
                returnValue.addAll(parseSearchPage(document))
            } else {
                break
            }
        }
        return returnValue
    }

    override fun quickSearch(query: String): List<SearchResponse> {
        val returnValue: ArrayList<SearchResponse> = ArrayList()

        val response = JSONObject(
            app.post(
                "https://wcostream.cc/ajax/search",
                data = mapOf("keyword" to query)
            ).text
        ).getString("html") // I won't make a dataclass for this shit
        val document = Jsoup.parse(response)

        document.select("a.nav-item").forEach {
            val title = it.selectFirst("img")?.attr("title").toString()
            val img = it?.selectFirst("img")?.attr("src")
            val href = it?.attr("href").toString()
            val isDub = title.contains("(Dub)")
            val filmInfo = it?.selectFirst(".film-infor")
            val year = filmInfo?.select("span")?.get(0)?.text()?.toIntOrNull()
            val type = filmInfo?.select("span")?.get(1)?.text().toString()
            if (title != "null") {
                returnValue.add(
                    if (getType(type) == TvType.AnimeMovie) {
                        MovieSearchResponse(
                            title, href, this.name, TvType.AnimeMovie, img, year
                        )
                    } else {
                        AnimeSearchResponse(
                            title,
                            href,
                            this.name,
                            TvType.Anime,
                            img,
                            year,
                            EnumSet.of(if (isDub) DubStatus.Dubbed else DubStatus.Subbed),
                        )
                    }
                )
            }
        }
        return returnValue
    }

    override fun load(url: String): LoadResponse {
        val response = app.get(url, timeout = 120).text
        val document = Jsoup.parse(response)

        val japaneseTitle = document.selectFirst("div.elements div.row > div:nth-child(1) > div.row-line:nth-child(1)")
            ?.text()?.trim()?.replace("Other names:", "")?.trim()

        val canonicalTitle = document.selectFirst("meta[name=\"title\"]")
            ?.attr("content")?.split("| W")?.get(0).toString()

        val isDubbed = canonicalTitle.contains("Dub")
        val episodeNodes = document.select(".tab-content .nav-item > a")

        val episodes = ArrayList(episodeNodes?.map {
            AnimeEpisode(it.attr("href"))
        } ?: ArrayList())

        val statusElem = document.selectFirst("div.elements div.row > div:nth-child(1) > div.row-line:nth-child(2)")
        val status = when (statusElem?.text()?.replace("Status:", "")?.trim()) {
            "Ongoing" -> ShowStatus.Ongoing
            "Completed" -> ShowStatus.Completed
            else -> null
        }
        val yearText =
            document.selectFirst("div.elements div.row > div:nth-child(2) > div.row-line:nth-child(4)")?.text()
        val year = yearText?.replace("Date release:", "")?.trim()?.split("-")?.get(0)?.toIntOrNull()

        val poster = document.selectFirst(".film-poster-img")?.attr("src")
        val type = document.selectFirst("span.item.mr-1 > a")?.text()?.trim()

        val synopsis = document.selectFirst(".description > p")?.text()?.trim()
        val genre = document.select("div.elements div.row > div:nth-child(1) > div.row-line:nth-child(5) > a")
            .map { it?.text()?.trim().toString() }

        return newAnimeLoadResponse(canonicalTitle, url, getType(type ?: "")) {
            japName = japaneseTitle
            engName = canonicalTitle
            posterUrl = poster
            this.year = year
            addEpisodes(if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed, episodes)
            showStatus = status
            plot = synopsis
            tags = genre
        }
    }

    override fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data).text
        val servers = Jsoup.parse(response).select("#servers-list > ul > li").map {
            mapOf(
                "link" to it?.selectFirst("a")?.attr("data-embed"),
                "title" to it?.selectFirst("span")?.text()?.trim()
            )
        }

        for (server in servers) {
            WcoStream().getSafeUrl(server["link"].toString(), "")?.forEach {
                callback.invoke(it)
            }
        }
        return true
    }
}
