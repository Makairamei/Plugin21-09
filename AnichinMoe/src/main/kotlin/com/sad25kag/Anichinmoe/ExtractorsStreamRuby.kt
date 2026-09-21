package com.sad25kag.Anichinmoe

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked

/**
 * StreamRuby / rubyvidhub — multi-quality HLS.
 * Top rung is often 1920x818 → remapped to 1080p and listed first.
 */
open class StreamRuby : ExtractorApi() {
    override var name = "StreamRuby"
    override var mainUrl = "https://rubyvidhub.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedReferer = referer ?: "https://anichin.moe/"
        val id = Regex("""embed-([a-zA-Z0-9]+)\.html""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)
            ?: Regex("""/([a-zA-Z0-9]{10,})(?:\.html)?(?:\?|$)""").find(url)?.groupValues?.getOrNull(1)
            ?: return

        val response = runCatching {
            app.post(
                "$mainUrl/dl",
                data = mapOf(
                    "op" to "embed",
                    "file_code" to id,
                    "auto" to "1",
                    "referer" to "",
                ),
                referer = embedReferer,
                headers = mapOf(
                    "User-Agent" to ANICHIN_UA,
                    "Referer" to embedReferer,
                    "Origin" to mainUrl,
                ),
            )
        }.getOrNull() ?: return

        val script = when {
            !getPacked(response.text).isNullOrEmpty() -> getAndUnpack(response.text)
            else -> response.document.selectFirst("script:containsData(sources:)")?.data()
                ?: response.text
        }

        val m3u8Candidates = linkedSetOf<String>()

        fun collectM3u8(text: String) {
            Regex(
                """https?://[^\s"'\\<>]+\.m3u8[^\s"'\\<>]*""",
                RegexOption.IGNORE_CASE
            ).findAll(text)
                .map { it.value.replace("\\/", "/") }
                .forEach { m3u8Candidates.add(it) }

            Regex("""file:\s*"([^"]+\.m3u8[^"]*)"""").findAll(text)
                .map { it.groupValues[1].replace("\\/", "/") }
                .forEach { m3u8Candidates.add(it) }
        }

        collectM3u8(script)

        Regex("""file:\s*"([^"]+\.vtt[^"]*)"""").findAll(script)
            .map { it.groupValues[1].replace("\\/", "/") }
            .forEach { vtt ->
                runCatching { subtitleCallback(newSubtitleFile("id", vtt)) }
            }

        if (m3u8Candidates.isEmpty()) {
            val page = runCatching {
                app.get(url, referer = embedReferer, headers = mapOf("User-Agent" to ANICHIN_UA)).text
            }.getOrNull().orEmpty()
            val unpacked = runCatching {
                if (!getPacked(page).isNullOrEmpty()) getAndUnpack(page) else page
            }.getOrDefault(page)
            collectM3u8(unpacked)
        }

        m3u8Candidates.forEach { streamUrl ->
            emitHlsVariants(
                source = name,
                streamUrl = streamUrl,
                referer = mainUrl,
                callback = callback,
                headers = mapOf(
                    "User-Agent" to ANICHIN_UA,
                    "Referer" to mainUrl,
                    "Origin" to mainUrl,
                    "Accept" to "*/*",
                ),
            )
        }
    }
}

class StreamRubyCom : StreamRuby() {
    override var name = "StreamRuby"
    override var mainUrl = "https://streamruby.com"
}

class StreamRubyNet : StreamRuby() {
    override var name = "StreamRuby"
    override var mainUrl = "https://streamruby.net"
}
