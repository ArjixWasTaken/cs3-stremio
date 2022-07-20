package com.lagradost.cloudstream3.stremioproviders

import com.lagradost.cloudstream3.*
import com.fasterxml.jackson.annotation.*
import com.fasterxml.jackson.core.*
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.node.*
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.fasterxml.jackson.module.kotlin.*
import java.net.URLEncoder


@Suppress("UNCHECKED_CAST")
private fun <T> ObjectMapper.convert(k: kotlin.reflect.KClass<*>, fromJson: (JsonNode) -> T, toJson: (T) -> String, isUnion: Boolean = false) = registerModule(SimpleModule().apply {
    addSerializer(k.java as Class<T>, object : StdSerializer<T>(k.java as Class<T>) {
        override fun serialize(value: T, gen: JsonGenerator, provider: SerializerProvider) = gen.writeRawValue(toJson(value))
    })
    addDeserializer(k.java as Class<T>, object : StdDeserializer<T>(k.java as Class<T>) {
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext) = fromJson(p.readValueAsTree())
    })
})

val manifestMapper = jacksonObjectMapper().apply {
    propertyNamingStrategy = PropertyNamingStrategy.LOWER_CAMEL_CASE
    setSerializationInclusion(JsonInclude.Include.NON_NULL)
    configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    convert(ResourceElement::class, { ResourceElement.fromJson(it) }, { it.toJson() }, true)
}

data class Manifest (
    val id: String? = null,
    val version: String? = null,
    val description: String? = null,
    val name: String? = null,
    val resources: List<ResourceElement>? = null,
    val types: List<String>? = null,
    val catalogs: List<Catalog>? = null,
    val idPrefixes: List<String>? = null
) {
    fun toJson() = manifestMapper.writeValueAsString(this)

    companion object {
        fun fromJson(json: String) = manifestMapper.readValue<Manifest>(json)
    }
}

data class Extra(
    val name: String?,
    val isRequired: Boolean = false,
    val options: List<String> = listOf(),
    val optionsLimit: Int = 1
)


data class Catalog (
    val name: String? = null,
    val type: String? = null,
    val id: String? = null,
    val extra: List<Extra> = listOf()
)

sealed class ResourceElement {
    class ResourceClassValue(val value: ResourceClass) : ResourceElement()
    class StringValue(val value: String)               : ResourceElement()

    fun toJson(): String = manifestMapper.writeValueAsString(when (this) {
        is ResourceClassValue -> this.value
        is StringValue        -> this.value
    })

    companion object {
        fun fromJson(jn: JsonNode): ResourceElement = when (jn) {
            is ObjectNode -> ResourceClassValue(manifestMapper.treeToValue(jn)!!)
            is TextNode   -> StringValue(manifestMapper.treeToValue(jn)!!)
            else          -> throw IllegalArgumentException()
        }
    }
}

data class ResourceClass (
    val name: String? = null,
    val types: List<String>? = null,
    val idPrefixes: List<String>? = null
)



data class Videos (
    @JsonProperty("id") val id: String,
    @JsonProperty("title") val title: String?,
    @JsonProperty("thumbnail") val thumbnail: String?
)


data class ItemMetadata (
    @JsonProperty("id") val id: String,
    @JsonProperty("type") val type: String,
    @JsonProperty("name") val name: String?,
    @JsonProperty("cast") val cast: List<String>?,
    @JsonProperty("logo") val logo: String?,
    @JsonProperty("genres") val genres: List<String>?,
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("videos") val videos: List<Videos> = listOf(),
    @JsonProperty("runtime") val runtime: String?,
    @JsonProperty("director") val director: List<String>?,
    @JsonProperty("background") val background: String?,
    @JsonProperty("description") val description: String?,
    @JsonProperty("posterShape") val posterShape: String?
)




class StremioPluginAPI(private val manifestUrl: String, override val name: String): MainAPI() {
    val manifest: Manifest by lazy { Manifest.fromJson(app.get(this.manifestUrl).text) }
    override val mainUrl = manifestUrl.replace("/manifest.json", "")

    // Required manifest info.
    val id by lazy { manifest.id }
    val version by lazy { manifest.version }
    val description by lazy { manifest.description }
    val resources by lazy { manifest.resources }
    val types by lazy { manifest.types }

    val catalogs by lazy { manifest.catalogs }
    override var hasMainPage = false

    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.Anime,
        TvType.ONA,
        TvType.Cartoon,
        TvType.Documentary,
        TvType.TvSeries
    )

    override fun getMainPage(): HomePageResponse {
        // If the plugin does not provide a catalogue then return.
        if (this.catalogs?.isNullOrEmpty() != false) throw ErrorLoadingException()

        // Else use the plugin's API to search.
        val items = ArrayList<HomePageList>()

        data class m(@JsonProperty("metas") val metas: List<ItemMetadata>?)

        this.catalogs?.forEach { catalogue ->
            // this was a pain to figure out, fuck the stremio docs
            // just fuck them
            var params = catalogue.extra.map { extra ->
                if (extra.isRequired && extra.options.isNotEmpty() && extra.name != "search") {
                    if (extra.optionsLimit > 1) {
                        "${extra.name}[]=" + URLEncoder.encode(extra.options.subList(0, extra.optionsLimit - 1).joinToString(","), "utf8")
                    } else "${extra.name}=" + URLEncoder.encode(
                        extra.options.first(), "utf8"
                    )
                }
            }.joinToString("&")

            if (params.isNotEmpty()) params = "/$params"

            val catalogueLink = "${this.mainUrl}/catalog/${catalogue.type?.replace(" ", "%20")}/${catalogue.id}$params.json"

            val response: m = app.get(catalogueLink).mapped()

            items.add(HomePageList(catalogue.name ?: "${catalogue.id} - ${catalogue.type}", response.metas?.map {
                TvSeriesSearchResponse(
                    it.name.toString(),
                    "https://stremio.plugin-:+:-${this.id}-:+:-${it.id}-:+:-${it.type}-:+:-",  // -:+:- is my discriminator
                    this.name,
                    TvType.TvSeries,
                    it.poster,
                    null, null
                )
            } ?: arrayListOf()))
        }

        if (items.isEmpty()) throw ErrorLoadingException()
        return HomePageResponse(items)
    }


    override fun search(query: String): List<SearchResponse> {
        // https://learn-programming.baby-supernode.xyz/catalog/channel/learnprogramming.json?name=search&value=python
        data class m(@JsonProperty("metas") val metas: List<ItemMetadata>?)
        val items = ArrayList<SearchResponse>()
        this.catalogs?.forEach { catalogue ->
            if (catalogue.extra.none { it.name != null && it.name == "search" }) return@forEach
            var params = "search=${URLEncoder.encode(query, "utf8")}"

            for (extra in catalogue.extra) {
                if (extra.isRequired && extra.options.isNotEmpty() && extra.name != "search") {
                    if (extra.optionsLimit > 1) {
                        params += "&${extra.name}[]=" + URLEncoder.encode(extra.options.subList(0, extra.optionsLimit - 1).joinToString(","), "utf8")
                    } else params += "&${extra.name}=" + URLEncoder.encode(
                        extra.options.first(), "utf8"
                    )
                }
            }

            val results = app.get("${this.mainUrl}/catalog/${catalogue.type?.replace(" ", "%20")}/${URLEncoder.encode(catalogue.id, "utf8")}/$params.json").mapped<m>().metas
            results?.map {
                TvSeriesSearchResponse(
                    it.name.toString(),
                    "https://stremio.plugin-:+:-${this.id}-:+:-${it.id}-:+:-${it.type}-:+:-",  // -:+:- is my discriminator
                    this.name,
                    TvType.TvSeries,
                    it.poster,
                    null,
                    null
                )
            }?.let { items.addAll(it) }
        }
        return items
    }

    override fun load(url: String): LoadResponse {
        data class m(@JsonProperty("meta") val meta: ItemMetadata?)

        if (this.resources.isNullOrEmpty() || this.resources!!.none { (it is ResourceElement.ResourceClassValue && it.value.name == "meta") || (it is ResourceElement.StringValue && it.value == "meta") }) throw ErrorLoadingException()
        val match = Regex("""-:\+:-(.*?)-:\+:-(.*?)-:\+:-(.*?)-:\+:-""").find(url) ?: throw ErrorLoadingException()
        val (id, infoId, type) = match.destructured


        val meta = app.get("${this.mainUrl}/meta/$type/$infoId.json").mapped<m>().meta


        return newTvSeriesLoadResponse(meta?.name.toString(), url, TvType.TvSeries, meta?.videos?.map {
            TvSeriesEpisode(
                it.title,
                null,
                null,
                "https://stremio.plugin-:+:-${this.id}-:+:-${infoId}-:+:-${it.id}-:+:-",
                it.thumbnail,
                null
            )
        } ?: listOf())
    }
}
