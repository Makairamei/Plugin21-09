package com.sad25kag.animesail

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * MixDrop family — AnimeSail mirrors (m1xdrop / mixdrop.*).
 */
open class MixDropBase : ExtractorApi() {
    override var name = "MixDrop"
    override var mainUrl = "https://mixdrop.ag"
    override val requiresReferer = false

    override fun getExtractorUrl(id: String): String = "$mainUrl/e/$id"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = Regex("""/(?:e|f)/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)
        val embedUrl = if (id != null) "$mainUrl/e/$id" else url.replace("/f/", "/e/")

        val response = runCatching {
            app.get(
                embedUrl,
                referer = referer ?: "$mainUrl/",
                headers = mapOf("User-Agent" to USER_AGENT),
            )
        }.getOrNull() ?: return

        val scriptChunks = linkedSetOf<String>()
        scriptChunks.add(response.text)
        response.document.select("script").forEach { script ->
            val data = script.data().trim()
            if (data.isBlank()) return@forEach
            if (data.contains("MDCore", true) ||
                data.contains("wurl", true) ||
                data.contains("furl", true) ||
                data.contains("eval(function(p,a,c,k,e,d)")
            ) {
                scriptChunks.add(data)
            }
            if (data.contains("eval(function(p,a,c,k,e,d)")) {
                runCatching { getAndUnpack(data) }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { scriptChunks.add(it) }
            }
        }
        runCatching { getAndUnpack(response.text) }.getOrNull()?.let { scriptChunks.add(it) }

        val streamRegexes = listOf(
            Regex("""(?:MDCore\.)?wurl\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:MDCore\.)?furl\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](//[^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE),
        )

        fun normalizeCandidate(raw: String): String? {
            val normalized = raw
                .replace("\\/", "/")
                .replace("&amp;", "&")
                .replace("\\u0026", "&")
                .trim()
            val absolute = when {
                normalized.startsWith("http://", true) || normalized.startsWith("https://", true) -> normalized
                normalized.startsWith("//") -> "https:$normalized"
                else -> return null
            }
            return absolute.takeIf {
                it.contains(".mp4", true) || it.contains(".m3u8", true)
            }
        }

        val candidates = linkedSetOf<String>()
        scriptChunks.forEach { blob ->
            streamRegexes.forEach { regex ->
                regex.findAll(blob).forEach { m ->
                    m.groupValues.getOrNull(1)?.let(::normalizeCandidate)?.let(candidates::add)
                }
            }
        }
        val streamUrl = candidates.firstOrNull() ?: return
        val type = if (streamUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = type,
            ) {
                this.referer = embedUrl
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to embedUrl,
                    "Origin" to mainUrl,
                    "Accept" to "*/*",
                )
            }
        )
    }
}

class MixDropBz : MixDropBase() {
    override var mainUrl = "https://m1xdrop.bz"
}

class MixDropAg : MixDropBase() {
    override var mainUrl = "https://mixdrop.ag"
}

class MixDropTo : MixDropBase() {
    override var mainUrl = "https://mixdrop.to"
}

class MixDropClub : MixDropBase() {
    override var mainUrl = "https://mixdrop.club"
}

class MixDropCom : MixDropBase() {
    override var mainUrl = "https://mixdrop.com"
}

class MixDropSi : MixDropBase() {
    override var mainUrl = "https://mixdrop.si"
}

/** Current AnimeSail MixDrop mirror host (2026). */
class MixDropMiiiix : MixDropBase() {
    override var mainUrl = "https://miiiixdrop.net"
}

class MixDropNet : MixDropBase() {
    override var mainUrl = "https://mixdrop.net"
}

/** Mp4Upload */
open class Mp4UploadFix : ExtractorApi() {
    override var name = "Mp4Upload"
    override var mainUrl = "https://www.mp4upload.com"
    override val requiresReferer = true

    private val idMatch = Regex("""mp4upload\.com/(?:embed-)?([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
    private val srcRegex = Regex("""player\.src\(\s*\{[\w\W]*?src:\s*["'](.*?)["']""", RegexOption.IGNORE_CASE)
    private val srcRegex2 = Regex("""player\.src\(\s*["'](.*?)["']""", RegexOption.IGNORE_CASE)
    private val srcRegex3 = Regex("""src:\s*["'](https?://[^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE)

    override fun getExtractorUrl(id: String): String = "$mainUrl/embed-$id.html"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = idMatch.find(url)?.groupValues?.getOrNull(1)
        val realUrl = id?.let { "$mainUrl/embed-$it.html" } ?: url
        val watchReferer = "$mainUrl/"

        val response = runCatching {
            app.get(
                realUrl,
                referer = referer ?: watchReferer,
                headers = mapOf("User-Agent" to USER_AGENT),
            )
        }.getOrNull() ?: return

        val scriptBlobs = linkedSetOf<String>()
        scriptBlobs.add(response.text)
        runCatching { getAndUnpack(response.text) }.getOrNull()?.let { scriptBlobs.add(it) }
        response.document.select("script").forEach { script ->
            val data = script.data().trim()
            if (data.isNotBlank()) {
                scriptBlobs.add(data)
                if (data.contains("eval(function(p,a,c,k,e,d)")) {
                    runCatching { getAndUnpack(data) }.getOrNull()?.let { scriptBlobs.add(it) }
                }
            }
        }

        val streamUrl = scriptBlobs.firstNotNullOfOrNull { blob ->
            srcRegex.find(blob)?.groupValues?.getOrNull(1)?.trim()
                ?: srcRegex2.find(blob)?.groupValues?.getOrNull(1)?.trim()
                ?: srcRegex3.find(blob)?.groupValues?.getOrNull(1)?.trim()
        }?.takeIf { it.isNotBlank() } ?: return

        val quality = scriptBlobs.firstNotNullOfOrNull { blob ->
            Regex("""height\s*[:=]\s*["']?(\d{3,4})""", RegexOption.IGNORE_CASE)
                .find(blob)?.groupValues?.getOrNull(1)?.toIntOrNull()
        } ?: Qualities.Unknown.value

        val fixedStream = when {
            streamUrl.startsWith("http", true) -> streamUrl
            streamUrl.startsWith("//") -> "https:$streamUrl"
            else -> return
        }

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = fixedStream,
                type = ExtractorLinkType.VIDEO,
            ) {
                this.referer = watchReferer
                this.quality = quality
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to watchReferer,
                    "Origin" to mainUrl,
                    "Accept" to "*/*",
                )
            }
        )
    }
}

class Mp4UploadOrg : Mp4UploadFix() {
    override var mainUrl = "https://mp4upload.org"
}

/**
 * Pixeldrain — MUST emit raw media URL (`/api/file/{id}`), never `/u/{id}` HTML page.
 * `?download` sets Content-Disposition: attachment → CloudStream often shows
 * "Host tidak support pemutaran langsung / Buka di Tab Baru".
 * Plain `/api/file/{id}` returns video/mp4 + Range (206) → Exo plays in-app.
 */
open class Pixeldrain : ExtractorApi() {
    override var name = "Pixel"
    override var mainUrl = "https://pixeldrain.com"
    override val requiresReferer = false

    override fun getExtractorUrl(id: String): String = "$mainUrl/u/$id"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        // Reject if already a bare non-pixel url
        val id = Companion.extractPixelId(url) ?: return

        val info = runCatching {
            app.get(
                "https://pixeldrain.com/api/file/$id/info",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json",
                    "Referer" to "https://pixeldrain.com/u/$id",
                ),
            ).text
        }.getOrNull().orEmpty()

        val fileName = Regex(""""name"\s*:\s*"([^"]+)"""").find(info)?.groupValues?.getOrNull(1).orEmpty()
        val quality = when {
            fileName.contains("2160", true) || fileName.contains("4k", true) -> Qualities.P2160.value
            fileName.contains("1440", true) -> Qualities.P1440.value
            fileName.contains("1080", true) -> Qualities.P1080.value
            fileName.contains("720", true) -> Qualities.P720.value
            fileName.contains("480", true) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }

        // Stream URL — NO ?download (attachment breaks in-app player)
        val direct = "https://pixeldrain.com/api/file/$id"
        val page = "https://pixeldrain.com/u/$id"

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = direct,
                type = ExtractorLinkType.VIDEO,
            ) {
                this.referer = page
                this.quality = quality
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to page,
                    "Origin" to "https://pixeldrain.com",
                    "Accept" to "*/*",
                )
            }
        )
    }

    companion object {
        /**
         * AnimeSail wraps Pixel as popup:
         * `…/popup/?url=https%3A%2F%2Fpixeldrain.com%2Fu%2F{id}&token=…`
         * Must URL-decode before matching `/u/{id}`.
         */
        fun extractPixelId(url: String): String? {
            var u = url.trim()
            // Decode repeatedly (popup may be double-encoded / &#038;)
            repeat(3) {
                u = u.replace("&#038;", "&").replace("&amp;", "&")
                val dec = runCatching { java.net.URLDecoder.decode(u, "UTF-8") }.getOrNull()
                if (dec != null && dec != u) u = dec else return@repeat
            }
            // If still a popup wrapper, pull nested url=
            if (u.contains("url=", true) && u.contains("pixeldrain", true)) {
                val nested = u.substringAfter("url=", "").substringBefore("&").trim()
                if (nested.isNotBlank()) {
                    u = runCatching { java.net.URLDecoder.decode(nested, "UTF-8") }.getOrDefault(nested)
                }
            }
            return Regex(
                """pixeldrain\.com/(?:u|api/file)/([A-Za-z0-9_-]+)""",
                RegexOption.IGNORE_CASE,
            ).find(u)?.groupValues?.getOrNull(1)
                ?: Regex("""/(?:u|api/file)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
                    .find(u)?.groupValues?.getOrNull(1)
                    ?.takeIf { u.contains("pixel", true) }
        }

        /** Build in-app playable link (shared by provider loadLinks). */
        fun streamUrl(id: String): String = "https://pixeldrain.com/api/file/$id"
        fun pageUrl(id: String): String = "https://pixeldrain.com/u/$id"
    }
}

class PixeldrainTo : Pixeldrain() {
    override var mainUrl = "https://pixeldrain.to"
}

/** Krakenfiles */
class Krakenfiles : ExtractorApi() {
    override var name = "Kraken"
    override var mainUrl = "https://krakenfiles.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val page = runCatching {
            app.get(
                url,
                referer = referer ?: "$mainUrl/",
                headers = mapOf("User-Agent" to USER_AGENT),
            )
        }.getOrNull() ?: return

        val body = page.text
        val candidates = linkedSetOf<String>()
        page.document.select("source[src], video[src], [data-src-url]").forEach { el ->
            listOf("src", "data-src-url").forEach { a ->
                el.attr(a).takeIf { it.startsWith("http") }?.let { candidates.add(it) }
            }
        }
        Regex("""https?://[^"'\\\s<>]+\.(?:mp4|m3u8)[^"'\\\s<>]*""", RegexOption.IGNORE_CASE)
            .findAll(body)
            .forEach { candidates.add(it.value.replace("\\/", "/")) }

        candidates.forEach { stream ->
            val type = if (stream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(
                newExtractorLink(name, name, stream, type) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to mainUrl,
                        "Accept" to "*/*",
                    )
                }
            )
        }
    }
}

/**
 * Dodo = Doodstream. ONE working host only (no multi-host spam).
 * Quality left Unknown — site label "1080p" is often lie; stream is typically ~720.
 */
class DoodStreamSail : ExtractorApi() {
    override var name = "Dodo"
    override var mainUrl = "https://dood.watch"
    override val requiresReferer = true

    private val passMd5 = Regex("""(/pass_md5/[^"'\\\s]+)""")
    private val tokenRe = Regex("""[?&]token=([A-Za-z0-9]+)""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = Regex("""/(?:e|d|v)/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)
            ?: return

        // Prefer host from input URL first, then a short fallback list (NOT 15 hosts)
        val preferredHost = runCatching {
            java.net.URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrNull()

        val hosts = linkedSetOf<String>().apply {
            preferredHost?.let { add(it.trimEnd('/')) }
            addAll(
                listOf(
                    "https://doply.net",
                    "https://dood.watch",
                    "https://dood.ws",
                    "https://dood.li",
                    "https://rasa-cintaku-semakin-berantai.xyz",
                    "https://myvidplay.com",
                    "https://d000d.com",
                )
            )
        }

        for (host in hosts) {
            val embed = "$host/e/$id"
            val page = runCatching {
                app.get(
                    embed,
                    referer = referer ?: "$host/",
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to (referer ?: "$host/")),
                ).text
            }.getOrNull() ?: continue

            val md5Path = passMd5.find(page)?.groupValues?.getOrNull(1) ?: continue
            val md5Url = if (md5Path.startsWith("http")) md5Path else "$host$md5Path"
            val base = runCatching {
                app.get(
                    md5Url,
                    referer = embed,
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to embed),
                ).text.trim()
            }.getOrNull() ?: continue

            if (!base.startsWith("http")) continue

            val token = tokenRe.find(page)?.groupValues?.getOrNull(1).orEmpty()
            val stream = when {
                token.isNotBlank() && !base.contains("token=") ->
                    "$base${if (base.contains("?")) "&" else "?"}token=$token&expiry=${System.currentTimeMillis()}"
                else -> base
            }

            // Single emit — Unknown quality (don't fake 1080 from mirror label)
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = stream,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.referer = embed
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to embed,
                        "Accept" to "*/*",
                    )
                }
            )
            return
        }
    }
}
