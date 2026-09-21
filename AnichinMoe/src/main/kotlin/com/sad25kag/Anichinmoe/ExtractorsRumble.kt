package com.sad25kag.Anichinmoe

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Rumble — Betbet discovery + multi-quality, **capped at 1080p**.
 * 1440/2160 dropped (unplayable on many devices). 1080 kept.
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

        val body = response.text.replace("\\/", "/")
        val headers = mapOf(
            "User-Agent" to RUMBLE_UA,
            "Referer" to mainUrl,
            "Origin" to mainUrl,
            "Accept" to "*/*",
        )

        // The adaptive master of the video (https://rumble.com/hls-vod/<video>/playlist.m3u8).
        // Guessing it from the embed id does not work, it has to be read from the page.
        val vodPlaylist = Regex(
            """https?://rumble\.com/hls-vod/[A-Za-z0-9]+/playlist\.m3u8""",
            RegexOption.IGNORE_CASE
        ).find(body)?.value

        val scriptData = response.document.selectFirst("script:containsData(mp4)")?.data()
            ?.substringAfter("{\"mp4")
            ?.substringBefore("\"evt\":{")
            .orEmpty()
            .replace("\\/", "/")

        val m3u8s = linkedSetOf<String>()
        val mp4s = linkedMapOf<Int, String>()

        fun absorbUrl(raw: String, qualityHint: String? = null) {
            val cleaned = raw.replace("\\/", "/").trim()
            if (!cleaned.startsWith("http")) return
            if (isJunkStreamUrl(cleaned)) return
            // Segment/chunk URLs also carry "chunklist.m3u8" in their query, never a playlist.
            if (cleaned.contains("r_range=", true) || cleaned.contains("r_file=", true)) return
            if (!cleaned.contains("rumble", true) &&
                !cleaned.contains(".m3u8", true) &&
                !cleaned.contains(".mp4", true)
            ) return

            when {
                cleaned.contains(".m3u8", true) -> m3u8s.add(cleaned)
                cleaned.contains(".mp4", true) -> {
                    val q = when {
                        !qualityHint.isNullOrBlank() -> {
                            val fromName = getQualityFromName(qualityHint)
                            if (fromName > 0) normalizePlayQuality(fromName)
                            else normalizePlayQuality(
                                qualityHint.filter { it.isDigit() }.toIntOrNull() ?: 0
                            )
                        }
                        else -> {
                            val h = Regex("""(\d{3,4})""")
                                .findAll(cleaned)
                                .mapNotNull { it.groupValues[1].toIntOrNull() }
                                .filter { it in 144..2160 }
                                .maxOrNull()
                            normalizePlayQuality(h ?: 0)
                        }
                    }
                    // Cap: never store 1440/2160 entries
                    if (!isPlayableQuality(q) && q > 0) return
                    if (q > 0) mp4s[q] = cleaned
                    else mp4s.putIfAbsent(Qualities.Unknown.value, cleaned)
                }
            }
        }

        if (scriptData.isNotBlank()) {
            val regex = """"url":"(.*?)"|h":(.*?)\}""".toRegex()
            for (match in regex.findAll(scriptData)) {
                absorbUrl(match.groupValues[1])
            }
            // "1080":{"url":"..."} / "720":{"url":"..."}
            Regex(""""(\d{3,4})"\s*:\s*\{[^}]*?"url"\s*:\s*"(https?://[^"]+)"""")
                .findAll(scriptData)
                .forEach { absorbUrl(it.groupValues[2], it.groupValues[1] + "p") }
        }

        Regex(""""(\d{3,4})"\s*:\s*\{[^}]*?"url"\s*:\s*"(https?://[^"]+)"""")
            .findAll(body)
            .forEach { absorbUrl(it.groupValues[2], it.groupValues[1] + "p") }

        Regex(""""url"\s*:\s*"(https?://[^"]+)"""")
            .findAll(body)
            .forEach { absorbUrl(it.groupValues[1]) }

        Regex("""https?://[^"'\\s<>]+rumble[^"'\\s<>]+\.(?:m3u8|mp4)[^"'\\s<>]*""", RegexOption.IGNORE_CASE)
            .findAll(body)
            .forEach { absorbUrl(it.value) }

        var emitted = false

        // Adaptive master first: one entry that the player can switch quality on.
        if (vodPlaylist != null && !isJunkStreamUrl(vodPlaylist)) m3u8s.add(vodPlaylist)

        // Prefer explicit ladder: 1080 → 720 → 480 → 360 (no 1440)
        val preferredOrder = listOf(
            Qualities.P1080.value,
            Qualities.P720.value,
            Qualities.P480.value,
            Qualities.P360.value,
            Qualities.P240.value,
        )

        preferredOrder.forEach { want ->
            val stream = mp4s[want] ?: return@forEach
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = stream,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.referer = mainUrl
                    this.quality = want
                    this.headers = headers
                }
            )
            emitted = true
        }
        // any other ≤1080 mp4 not in preferred list
        mp4s.entries
            .filter { (q, _) -> isPlayableQuality(q) && q !in preferredOrder }
            .sortedByDescending { it.key }
            .forEach { (q, stream) ->
                callback(
                    newExtractorLink(name, name, stream, ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                        this.quality = if (q > 0) q else Qualities.Unknown.value
                        this.headers = headers
                    }
                )
                emitted = true
            }

        // HLS expand — keep ≤1080 only (must include 1080 if master has it)
        for (master in m3u8s) {
            val links = runCatching {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = master,
                    referer = mainUrl,
                    headers = headers,
                )
            }.getOrElse { emptyList() }
                .filterNot { isJunkStreamUrl(it.url, it.name) }
                .map { normalizePlayQuality(it.quality) to it }
                .filter { (q, _) -> isPlayableQuality(q) }
                .sortedByDescending { it.first }

            if (links.isNotEmpty()) {
                // Dedupe by quality bucket — one link per rung, 1080 first
                val byQ = linkedMapOf<Int, Pair<Int, com.lagradost.cloudstream3.utils.ExtractorLink>>()
                links.forEach { (q, link) ->
                    val key = if (q > 0) q else Qualities.Unknown.value
                    if (!byQ.containsKey(key)) byQ[key] = q to link
                }
                byQ.values
                    .sortedByDescending { it.first }
                    .forEach { (q, link) ->
                        callback(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = link.url,
                                type = link.type ?: ExtractorLinkType.M3U8,
                            ) {
                                this.referer = link.referer.ifBlank { mainUrl }
                                this.quality = q
                                this.headers = if (link.headers.isNotEmpty()) link.headers else headers
                            }
                        )
                        emitted = true
                    }
            } else if (!emitted) {
                // Only if nothing else: adaptive master tagged 1080 for ranking
                callback(
                    newExtractorLink(name, name, master, ExtractorLinkType.M3U8) {
                        this.referer = mainUrl
                        this.quality = Qualities.P1080.value
                        this.headers = headers
                    }
                )
                emitted = true
            }
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
