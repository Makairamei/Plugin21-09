package com.sad25kag.Anichinmoe

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder

/**
 * Odysee / LBRY — Anichin embeds `https://odysee.com/$/embed/@chan:id/name:id?r=…&signature=…`.
 *
 * One JSON-RPC call is enough, no page scraping and no auth token:
 *
 *     POST https://api.na-backend.odysee.com/api/v1/proxy?m=get
 *     {"jsonrpc":"2.0","method":"get","params":{"uri":"lbry://<embed path>","save_file":false}}
 *     → {"result":{"streaming_url":"https://player.odycdn.com/v6/streams/<claim_id>/<sd6>.mp4"}}
 *
 * The proxy accepts the concise claim ids the embed uses (`@Tristargym:e/<name>:a5`) and also
 * the bare claim name, so no separate `m=resolve` step is required. A deleted claim answers
 * with an error object, which simply means nothing gets emitted.
 *
 * The generated media URL is byte-for-byte the one Odysee's own player assigns to `<video src>`.
 * The CDN only answers with `Referer: https://odysee.com/`; without it the response is
 * `401 this content cannot be accessed at the moment`.
 */
class Odysee : ExtractorApi() {
    override var name = "Odysee"
    override var mainUrl = "https://odysee.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val path = embedPath(url) ?: return

        val body = runCatching {
            app.post(
                "$API_URL?m=get",
                headers = mapOf(
                    "Accept" to "application/json",
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/",
                ),
                requestBody = """
                    {"jsonrpc":"2.0","method":"get","params":{"uri":"lbry://$path","save_file":false}}
                """.trimIndent().toRequestBody("application/json".toMediaType()),
            ).text
        }.getOrNull() ?: return

        val streamUrl = Regex(""""streaming_url"\s*:\s*"(https?://[^"]+)"""")
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.takeIf { it.startsWith("http", true) }
            ?: return

        val headers = mapOf(
            "User-Agent" to ANICHIN_UA,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "Accept" to "*/*",
        )

        if (streamUrl.contains(".m3u8", true)) {
            emitHlsVariants(
                source = name,
                streamUrl = streamUrl,
                referer = "$mainUrl/",
                callback = callback,
                headers = headers,
            )
        } else {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
        }
    }

    /** `…/$/embed/@chan:id/name:id?r=…` → `@chan:id/name:id`; also handles plain claim URLs. */
    private fun embedPath(url: String): String? {
        val withoutQuery = url.substringBefore("?").substringBefore("#")
        val raw = if (withoutQuery.contains("/embed/", true)) {
            withoutQuery.substringAfterLast("/embed/")
        } else {
            withoutQuery.substringAfter("odysee.com", "").trimStart('/')
        }

        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        val cleaned = decoded.trim().trim('/').removePrefix("$").trim()
        if (cleaned.isBlank()) return null

        // Keep the hand built JSON body well formed for exotic claim names.
        return cleaned.replace("\\", "").replace("\"", "")
    }

    private companion object {
        const val API_URL = "https://api.na-backend.odysee.com/api/v1/proxy"
    }
}
