package com.sad25kag.Anichinmoe

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

internal const val ANICHIN_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/**
 * Normalize raw height / CS quality int into standard buckets.
 * Heights 800–1399 → 1080 (covers 818 StreamRuby, 900–1080, etc.).
 * ≥1400 → 1440 (will be dropped by isPlayableQuality).
 */
internal fun normalizePlayQuality(raw: Int): Int {
    if (raw <= 0) return Qualities.Unknown.value
    // Already a known CS bucket
    if (raw == Qualities.P2160.value || raw == Qualities.P1440.value ||
        raw == Qualities.P1080.value || raw == Qualities.P720.value ||
        raw == Qualities.P480.value || raw == Qualities.P360.value ||
        raw == Qualities.P240.value || raw == Qualities.P144.value
    ) return raw

    return when {
        raw >= 2000 -> Qualities.P2160.value
        raw >= 1400 -> Qualities.P1440.value
        raw >= 800 -> Qualities.P1080.value
        raw >= 700 -> Qualities.P720.value
        raw >= 500 -> Qualities.P480.value
        raw >= 400 -> Qualities.P480.value
        raw >= 300 -> Qualities.P360.value
        raw >= 200 -> Qualities.P240.value
        else -> raw
    }
}

/**
 * Playable cap: allow Unknown + everything ≤1080.
 * Drop 1440 / 2160 (Rumble ultra often fails on device).
 *
 * IMPORTANT: compare against known enum buckets, not only raw 1080,
 * so P1080 always passes and P1440 always fails.
 */
internal fun isPlayableQuality(q: Int): Boolean {
    if (q <= 0 || q == Qualities.Unknown.value) return true
    val n = normalizePlayQuality(q)
    return when (n) {
        Qualities.P144.value,
        Qualities.P240.value,
        Qualities.P360.value,
        Qualities.P480.value,
        Qualities.P720.value,
        Qualities.P1080.value -> true
        Qualities.P1440.value,
        Qualities.P2160.value -> false
        else -> n in 1..1080
    }
}

internal fun qualityLabel(q: Int): String {
    return when (normalizePlayQuality(q)) {
        Qualities.P2160.value -> "2160p"
        Qualities.P1440.value -> "1440p"
        Qualities.P1080.value -> "1080p"
        Qualities.P720.value -> "720p"
        Qualities.P480.value -> "480p"
        Qualities.P360.value -> "360p"
        Qualities.P240.value -> "240p"
        Qualities.P144.value -> "144p"
        else -> if (q > 0) "${q}p" else "Auto"
    }
}

/** I-frame-only / junk playlists (i1/i2/i3) */
internal fun isJunkStreamUrl(url: String, nameHint: String = ""): Boolean {
    val u = url.lowercase()
    val n = nameHint.lowercase().trim()
    if (n.matches(Regex("""i\d+"""))) return true
    if (n.matches(Regex("""iframe.*"""))) return true
    return u.contains("iframe") ||
        u.contains("/iframes") ||
        u.contains("iframes-") ||
        u.contains("i-frame") ||
        u.contains("iframe-stream") ||
        Regex("""/(?:i|iframe)s?\d*(?:/|\.m3u8)""").containsMatchIn(u)
}

/**
 * Dailymotion masters use separate EXT-X-MEDIA AUDIO groups.
 * Expanding leaves → silent video-only.
 */
internal fun isAudioSeparateMasterHost(url: String): Boolean {
    val u = url.lowercase()
    return u.contains("dailymotion.com") ||
        u.contains("dmcdn.net") ||
        u.contains("cdndirector.dailymotion") ||
        u.contains("dai.ly") ||
        (u.contains("geo.dailymotion") && u.contains(".m3u8"))
}

internal fun isTurboHost(sourceOrName: String, url: String = ""): Boolean {
    val v = "$sourceOrName $url".lowercase()
    return v.contains("turbo") || v.contains("turbovid") || v.contains("turboviplay")
}

internal fun cleanServerLabel(raw: String): String {
    return raw
        .replace(Regex("""\[.*?\]"""), "")
        .replace(Regex("""\(.*?\)"""), "")
        .replace(Regex("""(?i)\s*setting\s*dns\s*"""), "")
        .trim()
        .ifBlank { "Anichin" }
}

/**
 * Emit HLS variants.
 * - DM-class: master only (sound)
 * - others: expand, drop junk + >1080, name = source only
 */
internal suspend fun emitHlsVariants(
    source: String,
    streamUrl: String,
    referer: String,
    callback: (ExtractorLink) -> Unit,
    headers: Map<String, String> = mapOf(
        "User-Agent" to ANICHIN_UA,
        "Referer" to referer,
        "Origin" to (Regex("""^(https?://[^/]+)""").find(referer)?.value ?: referer),
        "Accept" to "*/*",
    ),
    preferMaster: Boolean = false,
    masterOnly: Boolean = false,
    /** Soft cap for this host (e.g. Turbo ≤720). Null = global ≤1080. */
    maxQuality: Int? = null,
) {
    val cleanSource = cleanServerLabel(source)
    val forceMasterOnly =
        masterOnly || isAudioSeparateMasterHost(streamUrl) ||
            (preferMaster && isAudioSeparateMasterHost(streamUrl))

    if (forceMasterOnly) {
        callback(
            newExtractorLink(cleanSource, cleanSource, streamUrl, ExtractorLinkType.M3U8) {
                this.referer = referer
                // P1080 so CS auto-play ranks DM/adaptive near top without multi labels
                this.quality = Qualities.P1080.value
                this.headers = headers
            }
        )
        return
    }

    val cap = maxQuality ?: 1080

    val links = runCatching {
        M3u8Helper.generateM3u8(
            source = cleanSource,
            streamUrl = streamUrl,
            referer = referer,
            headers = headers,
        )
    }.getOrElse { emptyList() }
        .filterNot { isJunkStreamUrl(it.url, it.name) }
        .map { link -> normalizePlayQuality(link.quality) to link }
        .filter { (q, _) ->
            when {
                q <= 0 || q == Qualities.Unknown.value -> true
                !isPlayableQuality(q) -> false
                q > cap -> false
                else -> true
            }
        }
        .sortedByDescending { it.first }

    if (links.isEmpty()) {
        // Fallback adaptive master — mark 1080 for ranking, player picks track
        callback(
            newExtractorLink(cleanSource, cleanSource, streamUrl, ExtractorLinkType.M3U8) {
                this.referer = referer
                this.quality = Qualities.P1080.value.coerceAtMost(cap).let {
                    if (cap < 1080) Qualities.P720.value else Qualities.P1080.value
                }
                this.headers = headers
            }
        )
        return
    }

    links.forEach { (q, link) ->
        if (isJunkStreamUrl(link.url, link.name)) return@forEach
        if (isAudioSeparateMasterHost(link.url)) return@forEach
        callback(
            newExtractorLink(
                source = cleanSource,
                name = cleanSource,
                url = link.url,
                type = link.type ?: ExtractorLinkType.M3U8,
            ) {
                this.referer = link.referer.ifBlank { referer }
                this.quality = q
                this.headers = if (link.headers.isNotEmpty()) link.headers else headers
            }
        )
    }
}
