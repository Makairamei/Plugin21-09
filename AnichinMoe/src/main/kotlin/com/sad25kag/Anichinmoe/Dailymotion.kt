package com.sad25kag.Anichinmoe

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Dailymotion — **one** playable master link with sound.
 *
 * Never emit quality-expanded leaves (those become silent "Dailymotion 1080p").
 * Referer = geo player x95ee (TESTINGCF / Betbet working path).
 */
class Geodailymotion : Dailymotion() {
    override var name = "Dailymotion"
    override var mainUrl = "https://geo.dailymotion.com"
}

open class Dailymotion : ExtractorApi() {
    override var mainUrl = "https://www.dailymotion.com"
    override var name = "Dailymotion"
    override val requiresReferer = false

    private val baseUrl = "https://www.dailymotion.com"
    private val geoBaseUrl = "https://geo.dailymotion.com"
    private val defaultPlayerId = "x95ee"
    private val videoIdRegex = "^[kx][a-zA-Z0-9]+$".toRegex()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // If caller already passed a DM master m3u8, emit once — do not expand
        if (url.contains(".m3u8", true) && isAudioSeparateMasterHost(url)) {
            val id = getVideoId(url) ?: getGeoAccessId(url) ?: "x"
            emitMasterOnly(url, id, callback)
            return
        }

        val id = getVideoId(url)
            ?: getGeoAccessId(url)
            ?: Regex("""(?:video[=/]|url=)([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)
            ?: return

        if (!resolveMetadataVideo(id, referer, subtitleCallback, callback)) {
            resolveGeoPlayer(id, referer, subtitleCallback, callback)
        }
    }

    private fun playerEmbedUrl(videoId: String): String =
        "$geoBaseUrl/player/$defaultPlayerId.html?video=$videoId"

    private suspend fun resolveGeoPlayer(
        accessId: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val embedUrl = playerEmbedUrl(accessId)
        val embedder = URLEncoder.encode(referer ?: "https://anichin.moe/", "UTF-8")
        val metadataUrl = "$geoBaseUrl/video/$accessId.json?legacy=true&embedder=$embedder"
        val response = runCatching {
            app.get(
                metadataUrl,
                referer = embedUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to embedUrl,
                    "Origin" to baseUrl,
                    "Accept" to "application/json,text/plain,*/*",
                )
            ).text
        }.getOrNull() ?: return false

        val json = runCatching { JSONObject(response) }.getOrNull() ?: return false
        emitSubtitles(json, subtitleCallback)
        val master = extractAutoMaster(json) ?: return false
        val canonicalId = json.optString("id").trim().takeIf { it.matches(videoIdRegex) } ?: accessId
        emitMasterOnly(master, canonicalId, callback)
        return true
    }

    private suspend fun resolveMetadataVideo(
        id: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val embedUrl = playerEmbedUrl(id)
        val metaDataUrl = "$baseUrl/player/metadata/video/$id"
        val response = runCatching {
            app.get(
                metaDataUrl,
                referer = embedUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to embedUrl,
                    "Origin" to baseUrl,
                    "Accept" to "application/json,text/plain,*/*",
                )
            ).text
        }.getOrNull() ?: return false

        val json = runCatching { JSONObject(response) }.getOrNull()
        if (json != null) emitSubtitles(json, subtitleCallback)

        val master = when {
            json != null -> extractAutoMaster(json)
            else -> Regex(""""url"\s*:\s*"([^"]+\.m3u8[^"]*)"""")
                .find(response)?.groupValues?.getOrNull(1)?.replace("\\/", "/")
        }

        val canonical = json?.optString("id")?.trim().orEmpty()
        if (master.isNullOrBlank() && canonical.isNotBlank() && canonical != id && canonical.matches(videoIdRegex)) {
            return resolveMetadataVideo(canonical, referer, subtitleCallback, callback)
        }
        if (master.isNullOrBlank()) return false

        val playId = canonical.takeIf { it.matches(videoIdRegex) } ?: id
        emitMasterOnly(master, playId, callback)
        return true
    }

    /** Only the "auto" HLS master — never discrete quality keys that become silent leaves. */
    private fun extractAutoMaster(json: JSONObject): String? {
        val qualities = json.optJSONObject("qualities") ?: return null
        val auto = qualities.optJSONArray("auto") ?: return null
        for (i in 0 until auto.length()) {
            val item = auto.optJSONObject(i) ?: continue
            val type = item.optString("type").lowercase()
            val url = item.optString("url").trim().replace("\\/", "/")
            if (url.isBlank()) continue
            if (type.contains("mpegurl") || type.contains("x-mpegurl") || url.contains(".m3u8", true)) {
                return url
            }
        }
        return null
    }

    private suspend fun emitSubtitles(json: JSONObject, subtitleCallback: (SubtitleFile) -> Unit) {
        val subtitles = json.optJSONObject("subtitles") ?: return
        for (lang in subtitles.keys().asSequence().toList()) {
            val value = subtitles.opt(lang)
            val entries = when (value) {
                is JSONArray -> value
                is JSONObject -> value.optJSONArray("data") ?: JSONArray().put(value)
                else -> JSONArray()
            }
            for (index in 0 until entries.length()) {
                val item = entries.optJSONObject(index) ?: continue
                val label = item.optString("label", lang).ifBlank { lang }
                val urlsArr = item.optJSONArray("urls")
                if (urlsArr != null) {
                    for (urlIndex in 0 until urlsArr.length()) {
                        val subUrl = urlsArr.optString(urlIndex).trim()
                        if (subUrl.isNotBlank()) subtitleCallback(newSubtitleFile(label, subUrl))
                    }
                } else {
                    val subUrl = item.optString("url").trim()
                    if (subUrl.isNotBlank()) subtitleCallback(newSubtitleFile(label, subUrl))
                }
            }
        }
    }

    private fun getGeoAccessId(url: String): String? {
        val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        return listOf(
            Regex("""(?i)[?&]video=([A-Za-z0-9]+)"""),
            Regex("""(?i)[?&]url=([A-Za-z0-9]+)"""),
            Regex("""(?i)/video/([A-Za-z0-9]+)\.json"""),
            Regex("""(?i)/video/([A-Za-z0-9]+)"""),
        ).firstNotNullOfOrNull { it.find(decoded)?.groupValues?.getOrNull(1) }
            ?.takeIf { it.matches(videoIdRegex) }
    }

    private fun getVideoId(url: String): String? {
        val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        val id = when {
            decoded.contains("dai.ly", true) -> URI(decoded).path.trim('/').substringBefore("/")
            decoded.contains("geo.dailymotion.com", true) -> getGeoAccessId(decoded).orEmpty()
            decoded.contains("dailymotion.com", true) -> URI(decoded).path.substringAfterLast("/")
            else -> getGeoAccessId(decoded).orEmpty()
        }
        return if (id.matches(videoIdRegex)) id else null
    }

    /** Exactly one link named "Dailymotion" — adaptive master with AUDIO.
     * quality = P1080 so CloudStream auto-play ranks it first (track picker still inside player).
     */
    private suspend fun emitMasterOnly(
        streamLink: String,
        videoId: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val embedUrl = playerEmbedUrl(videoId)
        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = streamLink,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = embedUrl
                // Rank high for auto-play; single entry → no multi "Dailymotion 720/480" clones
                this.quality = Qualities.P1080.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to embedUrl,
                    "Origin" to baseUrl,
                    "Accept" to "*/*",
                )
            }
        )
    }
}
