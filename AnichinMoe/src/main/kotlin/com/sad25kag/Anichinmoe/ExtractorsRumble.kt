package com.sad25kag.Anichinmoe

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Rumble — reads the ladder straight out of the embed page player config.
 *
 * The embed page carries `m.f["<id>"] = {"u":{…},"ua":{…}}`, with:
 *  - `ua.tar.<height>.url` → `…/SS6iz.baa.tar?r_file=chunklist.m3u8&r_range=…`
 *    A VOD HLS chunklist. Its segments are **muxed** MPEG-TS (PMT advertises 0x1B H.264
 *    + 0x0F AAC on the same PID pair), so every rung plays with sound. This is where the
 *    240/360/480/720/1080 options come from.
 *  - `ua.mp4.<height>.url` → progressive rungs, still used by older uploads.
 *  - `u.hls.url` / `ua.hls.auto.url` → `https://rumble.com/hls-vod/<id>/playlist.m3u8`,
 *    the adaptive master.
 *  - `ua.timeline.<n>.url` → a ~320x134 sprite clip for the scrub bar (11 kbps, 1.7 MB
 *    for a 20 min episode). It is never the episode and must not be emitted.
 *
 * NOTE: `rumble.com` is DNS-blocked by several Indonesian ISPs (the site itself says
 * "Rumble … harus setting DNS"). The media CDN (`hugh.cdn.rumble.cloud`) is not blocked,
 * but this page is, so no plugin change can rescue a hijacked resolver.
 */
private const val RUMBLE_UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageUrl = normalizeEmbed(url)
        // Rumble answers 403 to desktop user agents, the embed page only loads on a mobile one.
        val response = runCatching {
            app.get(
                pageUrl,
                referer = referer ?: "$mainUrl/",
                headers = mapOf(
                    "User-Agent" to RUMBLE_UA,
                    "Referer" to "$mainUrl/",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.9",
                ),
            )
        }.getOrNull() ?: return

        // The player config is a JS object with escaped slashes.
        val body = response.text.replace("\\/", "/")
        val headers = mapOf(
            "User-Agent" to RUMBLE_UA,
            "Referer" to mainUrl,
            "Origin" to mainUrl,
            "Accept" to "*/*",
        )

        // `"timeline"` holds the scrub-bar sprite clip, not the episode. Both shapes are
        // matched exactly (`{"url":…}` for `u`, `{"1080":{"url":…}}` for `ua`) so an empty
        // timeline object cannot run away and blacklist a real rung.
        val previewUrls = buildSet {
            listOf(
                Regex(""""timeline"\s*:\s*\{\s*"url"\s*:\s*"(https?://[^"]+)""""),
                Regex(""""timeline"\s*:\s*\{\s*"\d{3,4}"\s*:\s*\{\s*"url"\s*:\s*"(https?://[^"]+)""""),
            ).forEach { regex ->
                regex.findAll(body).forEach { add(it.groupValues[1]) }
            }
        }

        val seen = HashSet<String>()
        val hlsRungs = sortedMapOf<Int, String>()
        val mp4Rungs = sortedMapOf<Int, String>()
        var adaptiveMaster: String? = null

        fun absorb(raw: String, hint: Int?) {
            val cleaned = raw.replace("\\/", "/").trim()
            if (!cleaned.startsWith("http")) return
            if (cleaned in previewUrls) return
            if (isJunkStreamUrl(cleaned)) return
            // Only the chunklist is a playlist, `r_file=media-n.ts` pulls are single segments.
            if (cleaned.contains("r_file=", true) && !cleaned.contains("r_file=chunklist", true)) return

            val quality = hint?.let { normalizePlayQuality(it) } ?: Qualities.Unknown.value

            when {
                cleaned.contains(".m3u8", true) -> {
                    if (cleaned.contains("/hls-vod/", true)) {
                        if (adaptiveMaster == null) adaptiveMaster = cleaned
                    } else if (seen.add(cleaned)) {
                        hlsRungs.putIfAbsent(quality, cleaned)
                    }
                }
                cleaned.contains(".mp4", true) -> {
                    if (seen.add(cleaned)) mp4Rungs.putIfAbsent(quality, cleaned)
                }
            }
        }

        // Height-keyed loggers first, they are the ones carrying the real resolution.
        Regex(""""(?:(\d{3,4}))"\s*:\s*\{\s*"url"\s*:\s*"(https?://[^"]+)"""")
            .findAll(body)
            .forEach { absorb(it.groupValues[2], it.groupValues[1].toIntOrNull()) }

        // Single-rung loggers (`"tar":{"url":…}` / `"mp4":{"url":…}`) carry no height of their own.
        Regex(""""(?:tar|mp4)"\s*:\s*\{\s*"url"\s*:\s*"(https?://[^"]+)"""")
            .findAll(body)
            .forEach { absorb(it.groupValues[1], null) }

        // `u.hls.url` and `ua.hls.auto.url`.
        Regex(""""hls"\s*:\s*(?:"auto"\s*:\s*)?\{\s*"url"\s*:\s*"(https?://[^"]+)"""")
            .findAll(body)
            .forEach { absorb(it.groupValues[1], null) }

        suspend fun emit(stream: String, quality: Int, type: ExtractorLinkType, linkName: String = name) {
            callback(
                newExtractorLink(
                    source = name,
                    name = linkName,
                    url = stream,
                    type = type,
                ) {
                    this.referer = mainUrl
                    this.quality = quality
                    this.headers = headers
                }
            )
        }

        // Every rung of the ladder, best first. The `.tar` chunklists are muxed, so they sound.
        hlsRungs.entries
            .sortedByDescending { it.key }
            .filter { isPlayableQuality(it.key) }
            .forEach { (quality, stream) -> emit(stream, quality, ExtractorLinkType.M3U8) }

        mp4Rungs.entries
            .sortedByDescending { it.key }
            .filter { isPlayableQuality(it.key) }
            .forEach { (quality, stream) -> emit(stream, quality, ExtractorLinkType.VIDEO) }

        // Adaptive master as a safety net, and the only entry when no rungs are published.
        val master = adaptiveMaster
        if (master != null && !isJunkStreamUrl(master)) {
            val topRung = hlsRungs.keys.filter { isPlayableQuality(it) }.maxOrNull()
            emit(
                stream = master,
                quality = topRung?.takeIf { it > 0 } ?: Qualities.P1080.value,
                type = ExtractorLinkType.M3U8,
                linkName = "$name (Auto)",
            )
        }
    }

    private fun normalizeEmbed(url: String): String {
        if (url.contains("/embed/", true)) return url
        val id = Regex("""/(?:v)/([a-zA-Z0-9]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.getOrNull(1)
            ?: Regex("""[?&]v=([a-zA-Z0-9]+)""").find(url)?.groupValues?.getOrNull(1)
            ?: return url
        return "$mainUrl/embed/$id/"
    }
}
