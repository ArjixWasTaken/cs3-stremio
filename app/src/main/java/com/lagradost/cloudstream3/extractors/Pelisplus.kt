package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.normalSafeApiCall
import com.lagradost.cloudstream3.pmap
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.extractorApis
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup

/**
 * overrideMainUrl is necessary for for other vidstream clones like vidembed.cc
 * If they diverge it'd be better to make them separate.
 * */
class Pelisplus(val mainUrl: String) {
    val name: String = "Vidstream"

    private fun getExtractorUrl(id: String): String {
        return "$mainUrl/play?id=$id"
    }

    private fun getDownloadUrl(id: String): String {
        return "$mainUrl/download?id=$id"
    }

    private val normalApis = arrayListOf(MultiQuality())

    // https://gogo-stream.com/streaming.php?id=MTE3NDg5
    fun getUrl(id: String, isCasting: Boolean = false, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            normalApis.pmap { api ->
                val url = api.getExtractorUrl(id)
                val source = api.getSafeUrl(url)
                source?.forEach { callback.invoke(it) }
            }
            val extractorUrl = getExtractorUrl(id)

            /** Stolen from GogoanimeProvider.kt extractor */
            normalSafeApiCall {
                val link = getDownloadUrl(id)
                println("Generated vidstream download link: $link")
                val page = app.get(link, referer = extractorUrl)

                val pageDoc = Jsoup.parse(page.text)
                val qualityRegex = Regex("(\\d+)P")

                //a[download]
                pageDoc.select(".dowload > a")?.pmap { element ->
                    val href = element.attr("href") ?: return@pmap
                    val qual = if (element.text()
                            .contains("HDP")
                    ) "1080" else qualityRegex.find(element.text())?.destructured?.component1().toString()

                    if (!loadExtractor(href, link, callback)) {
                        callback.invoke(
                            ExtractorLink(
                                this.name,
                                if (qual == "null") this.name else "${this.name} - " + qual + "p",
                                href,
                                page.url,
                                getQualityFromName(qual),
                                element.attr("href").contains(".m3u8")
                            )
                        )
                    }
                }
            }

            with(app.get(extractorUrl)) {
                val document = Jsoup.parse(this.text)
                val primaryLinks = document.select("ul.list-server-items > li.linkserver")
                //val extractedLinksList: MutableList<ExtractorLink> = mutableListOf()

                // All vidstream links passed to extractors
                primaryLinks.distinctBy { it.attr("data-video") }.forEach { element ->
                    val link = element.attr("data-video")
                    //val name = element.text()

                    // Matches vidstream links with extractors
                    extractorApis.filter { !it.requiresReferer || !isCasting }.pmap { api ->
                        if (link.startsWith(api.mainUrl)) {
                            val extractedLinks = api.getSafeUrl(link, extractorUrl)
                            if (extractedLinks?.isNotEmpty() == true) {
                                extractedLinks.forEach {
                                    callback.invoke(it)
                                }
                            }
                        }
                    }
                }
                return true
            }
        } catch (e: Exception) {
            return false
        }
    }
}
