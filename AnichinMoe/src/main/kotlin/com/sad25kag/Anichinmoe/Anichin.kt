package com.sad25kag.Anichinmoe

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import com.lagradost.cloudstream3.toNewSearchResponseList

class Anichin : MainAPI() {
    companion object {
        var context: android.content.Context? = null

        /** Main page entries that are read from the homepage widgets instead of an archive. */
        private const val HOME_SECTION_PREFIX = "home:"

        private const val MAX_TOP_LEVEL_CANDIDATES = 24
        private const val MAX_DOWNLOAD_CANDIDATES = 8
        private const val MAX_NESTED_CANDIDATES = 16
        private const val MAX_RESOLVE_DEPTH = 2
        private const val MAX_VISITED_LINKS = 48
        private const val MAX_NESTED_TEXT_BYTES = 1_500_000L
    }

    override var mainUrl = "https://anichin.moe"
    override var name = "Anichin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        // The homepage widget with the newest episode posts. The series archive sorts by the
        // series' own modified date and therefore lags behind every new release.
        "${HOME_SECTION_PREFIX}Rilisan Terbaru" to "Rilisan Terbaru",
        "${HOME_SECTION_PREFIX}Movie" to "Movie",
        "${HOME_SECTION_PREFIX}Upcoming Donghua" to "Upcoming Donghua",
        "${HOME_SECTION_PREFIX}Dropped Project" to "Dropped Project",
        "anime/?status=ongoing&type=donghua&order=update" to "Donghua Terbaru",
        "anime/?status=completed&type=donghua&sub=&order=update" to "Donghua Udah Tamat",
        "anime/?status=hiatus&type=donghua&order=update" to "Donghua Tidak Dilanjutkan",
        "anime/?type=live+action&order=update" to "Live Action",
        "anime/?type=donghua&order=title" to "Semua Donghua",
        "anime/?status=&type=movie&sub=&order=update" to "Donghua Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Homepage widgets ("Rilisan Terbaru", "Movie", …) live on / and paginate via /page/N/.
        if (request.data.startsWith(HOME_SECTION_PREFIX)) {
            val sectionTitle = request.data.removePrefix(HOME_SECTION_PREFIX).trim()
            val url = if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
            val document = app.get(url).document

            val items = document.select("div.bixbox")
                .firstOrNull { box ->
                    box.selectFirst("div.releases")?.text().orEmpty().contains(sectionTitle, true)
                }
                // Artikel di widget homepage dibungkus <div class="excstf">, jadi selector
                // "div.listupd > article" (anak langsung) mengembalikan NOL item dan section
                // ini tampil kosong walaupun situsnya berisi. Ambil div.listupd widget ini
                // lalu semua <article> di dalamnya.
                ?.selectFirst("div.listupd")
                ?.select("article")
                ?.mapNotNull { it.toSearchResult() }
                .orEmpty()

            return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = items,
                    isHorizontalImages = false,
                ),
                hasNext = items.isNotEmpty(),
            )
        }

        val document = app.get("${mainUrl}/${request.data}&page=$page").document
        val home = document.select("div.listupd > article, div.listupd > div.excstf > article")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false,
            ),
            hasNext = true,
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("div.bsx > a").attr("title").trim()
        val href = fixUrl(this.select("div.bsx > a").attr("href"))
        val posterUrl = fixUrlNull(this.select("div.bsx > a img").attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        val url = if (page <= 1) {
            "${mainUrl}/?s=$encodedQuery"
        } else {
            "${mainUrl}/page/$page/?s=$encodedQuery"
        }

        val document = app.get(url).document

        val results = document
            .select("div.listupd > article, div.listupd > div.excstf > article")
            .mapNotNull { it.toSearchResult() }

        val hasNext = document.selectFirst(
            "a.next, a.next.page-numbers, .nav-links a.next, .pagination .next"
        ) != null

        return results.toNewSearchResponseList(
            hasNext = hasNext
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url)
        val document = app.get(fixedUrl).document

        // "Rilisan Terbaru" entries are episode posts: they carry no episode list of their own.
        // Resolving the parent series keeps that release playable behind its full episode list.
        val isEpisodePost = fixedUrl.contains("-episode-", true) || fixedUrl.contains("/episode/", true)
        if (isEpisodePost && document.selectFirst(".eplister") == null) {
            val seriesUrl = parentSeriesUrl(document, fixedUrl)
            if (seriesUrl != null) {
                val seriesDocument = runCatching { app.get(seriesUrl).document }.getOrNull()
                if (seriesDocument != null) {
                    return buildLoadResponse(seriesDocument, seriesUrl, fixedUrl)
                }
            }
        }

        return buildLoadResponse(document, fixedUrl, null)
    }

    /** Breadcrumb of an episode post: Home → series → episode. */
    private fun parentSeriesUrl(document: Document, currentUrl: String): String? {
        val current = currentUrl.trimEnd('/')
        return document.select("div.ts-breadcrumb a[href], .entry-crumbs a[href]")
            .mapNotNull { element ->
                element.attr("abs:href").ifBlank { element.attr("href") }
                    .takeIf { it.isNotBlank() }
                    ?.let { normalizeAnyUrl(it, mainUrl) }
            }
            .map { it.trimEnd('/') }
            .firstOrNull { candidate ->
                candidate != current &&
                    candidate != mainUrl.trimEnd('/') &&
                    !candidate.contains("-episode-", true) &&
                    !candidate.contains("/episode/", true) &&
                    !candidate.contains("?", true) &&
                    !candidate.contains("/anime/", true) &&
                    !candidate.contains("/genre/", true) &&
                    !candidate.contains("/season/", true)
            }
    }

    private suspend fun buildLoadResponse(
        document: Document,
        url: String,
        currentEpisodeUrl: String?,
    ): LoadResponse {
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        var poster = document.select("div.ime > img").attr("src")
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type = document.selectFirst(".spe")?.text().orEmpty()

        // Safe metadata only: no episode/extractor logic changes
        val year = Regex("\\b(19|20)\\d{2}\\b")
            .find(document.text())
            ?.value
            ?.toIntOrNull()

        val tags = document.select(".genre a, .genres a, .genxed a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val tvType = if (type.contains("Movie", true)) TvType.Movie else TvType.TvSeries

        if (poster.isEmpty()) {
            poster = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        }

        if (tvType == TvType.Movie) {
            val movieHref = document.selectFirst(".eplister li > a")?.attr("href")?.let { fixUrl(it) }
                ?: currentEpisodeUrl
                ?: url

            return newMovieLoadResponse(title, movieHref, TvType.Movie, movieHref) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
                this.year = year
                this.tags = tags
            }
        }

        val episodes = document.select(".eplister li").mapNotNull { ep ->
            val link = fixUrl(ep.selectFirst("a")?.attr("href").orEmpty()).takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            // The number lives in .epl-num, .epl-title only holds the chapter name. Without it
            // CloudStream cannot order the list and the newest episode looks like it never came.
            val epNum = ep.selectFirst(".epl-num")?.text()?.trim()?.toIntOrNull()
                ?: Regex("""(?i)episode-(\d+)""").find(link)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val epTitle = ep.selectFirst(".epl-title")?.text()?.trim().orEmpty()
            val epSub = ep.selectFirst(".epl-sub span")?.text()?.trim().orEmpty()
            val epDate = ep.selectFirst(".epl-date")?.text()?.trim().orEmpty()
            val cleanTitle = epTitle
                .replace(Regex("Episode\\s*\\d+\\s*Subtitle Indonesia", RegexOption.IGNORE_CASE), "")
                .replace("Subtitle Indonesia", "")
                .trim()
            val name = buildString {
                if (epNum != null) append("Episode $epNum")
                if (cleanTitle.isNotBlank()) {
                    if (isNotEmpty()) append(" — ")
                    append(cleanTitle)
                }
                if (isNotEmpty() && epSub.isNotBlank()) append(" ($epSub Indo)")
                if (isEmpty()) append(epTitle.ifBlank { "Episode" })
            }
            val desc = if (epDate.isNotEmpty()) "Rilis: $epDate" else null

            newEpisode(link) {
                this.name = name
                this.episode = epNum
                this.posterUrl = fixUrlNull(poster)
                this.description = desc
            }
        }.reversed()

        // An episode post has no list of its own, keep the entry itself playable.
        val finalEpisodes = episodes.ifEmpty {
            currentEpisodeUrl?.let { episodeUrl ->
                listOf(
                    newEpisode(fixUrl(episodeUrl)) {
                        this.name = title.ifBlank { "Episode" }
                        this.posterUrl = fixUrlNull(poster)
                    }
                )
            }.orEmpty()
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, finalEpisodes) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = description
            this.year = year
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = fixUrl(data)
        val document = app.get(episodeUrl, referer = mainUrl).document
        // Third slot = "listed by the site itself": the mirror <option> list is authoritative,
        // so those entries are still tried when their host is newer than our host whitelist.
        val candidates = linkedSetOf<Triple<String, String, Boolean>>()
        // Thread-safe: loadLinks uses parallel amap — plain LinkedHashSet races drop hosts
        val visited = java.util.Collections.synchronizedSet(linkedSetOf<String>())
        val emitted = java.util.Collections.synchronizedSet(linkedSetOf<String>())

        fun addCandidate(value: String?, label: String = "Anichin", listed: Boolean = false) {
            if (value.isNullOrBlank()) return
            val cleanLabel = cleanServerLabel(label)
            decodeServerUrls(value).forEach { candidate ->
                candidates.add(Triple(candidate, cleanLabel, listed))
            }
        }

        // Mirror <option> first — these are the real named servers (OK.ru, Streamruby, …)
        document.select(".mobius option[value], select.mirror option[value], select option[value], option[value]").forEach { server ->
            val label = server.text().trim().ifBlank { "Anichin" }
            addCandidate(server.attr("value"), label, listed = true)
        }

        // Default iframe only if not already covered by a mirror option (avoids extra "Anichin N" junk)
        val knownUrls = candidates.map { it.first }.toHashSet()
        document.select("#pembed iframe[src], .player-embed iframe[src], .video-content iframe[src], iframe[src]").forEach { element ->
            val src = element.attr("abs:src").ifBlank { element.attr("src") }
            val norm = normalizeAnyUrl(src, episodeUrl) ?: return@forEach
            if (knownUrls.any { it.contains(norm.substringAfter("://").take(40), true) || norm.contains(it.substringAfter("://").take(40), true) }) {
                return@forEach
            }
            // Label from host, never bare "Anichin"
            val hostLabel = when {
                norm.contains("anichin-player", true) && norm.contains("ok=", true) -> "OK.ru"
                norm.contains("anichin-player", true) && norm.contains("url=", true) -> "Dailymotion"
                norm.contains("ok.ru", true) -> "OK.ru"
                norm.contains("dailymotion", true) -> "Dailymotion"
                else -> cleanServerLabel(URI(norm).host?.substringBefore(".")?.replaceFirstChar { it.uppercase() } ?: "Player")
            }
            // The site's own player container counts as "listed" too.
            val inPlayerContainer = element.parents().any {
                it.id() == "pembed" || it.hasClass("player-embed") || it.hasClass("video-content")
            }
            addCandidate(src, hostLabel, listed = inPlayerContainer)
            knownUrls.add(norm)
        }

        document.select("[data-src], [data-lazy-src], [data-url], [data-link], [data-video], [data-embed], [data-player], [data-file]").forEach { element ->
            val label = element.text().trim().ifBlank { "Player" }
            addCandidate(element.attr("data-src"), label)
            addCandidate(element.attr("data-lazy-src"), label)
            addCandidate(element.attr("data-url"), label)
            addCandidate(element.attr("data-link"), label)
            addCandidate(element.attr("data-video"), label)
            addCandidate(element.attr("data-embed"), label)
            addCandidate(element.attr("data-player"), label)
            addCandidate(element.attr("data-file"), label)
        }

        // Do NOT dump every random URL as bare "Anichin" — only known hosts, labeled by host
        extractKnownVideoUrls(document.html()).forEach { raw ->
            val host = runCatching { URI(raw).host }.getOrNull().orEmpty()
            val label = when {
                host.contains("ok.ru") -> "OK.ru"
                host.contains("dailymotion") -> "Dailymotion"
                host.contains("rubyvidhub") || host.contains("streamruby") -> "StreamRuby"
                host.contains("rumble") -> "Rumble"
                host.contains("morencius") || host.contains("earnvids") -> "Vidhide"
                host.contains("turbovid") -> "TurboVIP"
                else -> cleanServerLabel(host.substringBefore(".").ifBlank { "Player" })
            }
            candidates.add(Triple(raw, label, false))
        }

        val countedCallback: (ExtractorLink) -> Unit = fun(link: ExtractorLink) {
            if (isJunkStreamUrl(link.url, link.name)) return
            val n = link.name.trim().lowercase()
            if (n.matches(Regex("""i\d+"""))) return

            val src = link.source.trim().lowercase()
            val q = normalizePlayQuality(link.quality)

            // Global: drop 1440/2160 (Rumble ultra etc.)
            if (!isPlayableQuality(link.quality) && link.quality > 0) return
            if (q == Qualities.P1440.value || q == Qualities.P2160.value) return
            if (n.contains("1440") || n.contains("2160") || n.contains("4k")) return

            // TurboVIP: "jarak jauh" on 1080 — keep only ≤720
            if (isTurboHost(src, link.url) || isTurboHost(n)) {
                if (q >= Qualities.P1080.value) return
                if (n.contains("1080") || n.contains("1440") || n.contains("2160")) return
            }

            // Dailymotion: exactly one adaptive entry (name plain, no quality text clones)
            if (src.contains("dailymotion") || n.contains("dailymotion") || isAudioSeparateMasterHost(link.url)) {
                // Allow the single master (even if quality tagged 1080 for ranking)
                if (Regex("""\d{3,4}p""").containsMatchIn(n) && n != "dailymotion") return
                if (n.contains(" ") && (n.contains("720") || n.contains("480") || n.contains("360"))) return
            }

            if (emitted.add(link.url)) callback(link)
        }

        val topLevelCandidates = candidates
            .mapNotNull { (url, label, listed) ->
                normalizeAnyUrl(url, episodeUrl)?.let { Triple(it, label, listed) }
            }
            .filterNot { (url, _, _) -> isNoiseFrame(url) }
            // Ad networks stay out even when the page itself lists them.
            .filterNot { (url, _, _) -> isAdsOnlyUrl(url) }
            // Servers the page itself lists are always attempted — an unknown/new host used to
            // be dropped here silently, which is why a live server could look "dead".
            .filter { (url, label, listed) -> listed || isPrimaryPlaybackHost(url, label) }
            .distinctBy { it.first }
            .sortedWith(
                compareBy<Triple<String, String, Boolean>> { candidatePriority(it.first, it.second) }
                    .thenBy { it.second.lowercase() }
                    .thenBy { it.first }
            )
            .take(MAX_TOP_LEVEL_CANDIDATES)

        // Parallel resolve (amap) so slow Rumble/DNS never blocks OK.ru / StreamRuby / DM.
        // Each host isolated with try/catch; visited/emitted are synchronized.
        topLevelCandidates.amap { (url, label, _) ->
            try {
                resolveVideoCandidate(
                    url = url,
                    label = label,
                    referer = episodeUrl,
                    visited = visited,
                    subtitleCallback = subtitleCallback,
                    callback = countedCallback,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.w("Anichin", "Failed resolving server: $label -> $url", error)
            }
        }

        if (emitted.isEmpty()) {
            val downloadCandidates = document.select(".soraddlx a[href], .dlbox a[href], .download a[href], .entry-content a[href], a[href*='mirrored.to'], a[href*='apk.miuiku.com']")
                .mapNotNull { element ->
                    element.attr("abs:href").ifBlank { element.attr("href") }
                        .takeIf { it.isNotBlank() }
                        ?.let { normalizeAnyUrl(it, episodeUrl) }
                }
                .filterNot { isNoiseFrame(it) }
                .filter { isPrimaryPlaybackHost(it, "Download") }
                .distinct()
                .sortedBy { candidatePriority(it, "Download") }
                .take(MAX_DOWNLOAD_CANDIDATES)

            for (url in downloadCandidates) {
                try {
                    resolveVideoCandidate(
                        url = url,
                        label = "Download",
                        referer = episodeUrl,
                        visited = visited,
                        subtitleCallback = subtitleCallback,
                        callback = countedCallback,
                    )
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    Log.w("Anichin", "Failed resolving download: $url", error)
                }
            }
        }

        return emitted.isNotEmpty()
    }

    private suspend fun resolveVideoCandidate(
        url: String,
        label: String,
        referer: String,
        visited: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int = 0,
    ) {
        val fixed = normalizeAnyUrl(url, referer)
            ?.replace(".txt", ".m3u8")
            ?: return

        if (visited.size >= MAX_VISITED_LINKS || !visited.add(fixed) || isNoiseFrame(fixed)) return

        val labelQuality = getQualityFromName(label)
        val urlQuality = getQualityFromName(fixed)
        val directQuality = when {
            labelQuality != Qualities.Unknown.value -> labelQuality
            urlQuality != Qualities.Unknown.value -> urlQuality
            else -> qualityFromUrl(fixed)
        }

        val sourceName = cleanServerLabel(label)
        when {
            fixed.contains(".m3u8", true) -> {
                if (isJunkStreamUrl(fixed)) return
                // Dailymotion masters must stay master-only (sound). Never expand to silent 1080p leaves.
                if (isAudioSeparateMasterHost(fixed) || sourceName.contains("Dailymotion", true)) {
                    emitHlsVariants(
                        source = "Dailymotion",
                        streamUrl = fixed,
                        referer = "https://geo.dailymotion.com/player/x95ee.html",
                        callback = callback,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to "https://geo.dailymotion.com/player/x95ee.html",
                            "Origin" to "https://www.dailymotion.com",
                            "Accept" to "*/*",
                        ),
                        masterOnly = true,
                    )
                    return
                }
                emitHlsVariants(
                    source = sourceName,
                    streamUrl = fixed,
                    referer = referer,
                    callback = callback,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to referer,
                        "Accept" to "*/*",
                    ),
                )
                return
            }
            fixed.contains(".mp4", true) || fixed.contains(".webm", true) -> {
                if (isJunkStreamUrl(fixed)) return
                val q = normalizePlayQuality(directQuality)
                callback(
                    newExtractorLink(
                        source = sourceName,
                        name = sourceName,
                        url = fixed,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = referer
                        this.quality = q
                        this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)
                    }
                )
                return
            }
        }

        val dailyUrl = normalizeDailymotionUrl(fixed)
        if (dailyUrl != null) {
            try {
                loadExtractor(dailyUrl, referer, subtitleCallback, callback)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
            }
        }

        if (shouldUseExtractor(fixed)) {
            try {
                loadExtractor(fixed, referer, subtitleCallback, callback)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
            }
        }

        if (depth >= MAX_RESOLVE_DEPTH || !shouldReadNestedPage(fixed)) return

        val response = runCatching {
            app.get(
                fixed,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                )
            )
        }.getOrNull() ?: return

        val contentType = response.headers["Content-Type"].orEmpty().lowercase()
        val contentLength = response.headers["Content-Length"]?.toLongOrNull()
        if (shouldSkipBodyRead(contentType, contentLength)) return

        val body = runCatching { response.text.cleanEscaped() }.getOrNull() ?: return
        val nested = linkedSetOf<String>()
        nested.addAll(extractKnownVideoUrls(body))

        val nestedDocument = Jsoup.parse(body, fixed)
        nestedDocument.select("iframe[src], iframe[data-src], embed[src], source[src], video[src], a[href]").forEach { element ->
            element.attr("data-src")
                .ifBlank { element.attr("abs:src") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("abs:href") }
                .ifBlank { element.attr("href") }
                .takeIf { it.isNotBlank() }
                ?.let { normalizeAnyUrl(it, fixed) }
                ?.let { nested.add(it) }
        }

        val nestedCandidates = nested.asSequence()
            .filterNot { isNoiseFrame(it) }
            .filter { isPrimaryPlaybackHost(it, label) }
            .distinct()
            .take(MAX_NESTED_CANDIDATES)
            .toList()

        for (nestedUrl in nestedCandidates) {
            resolveVideoCandidate(
                url = nestedUrl,
                label = label,
                referer = fixed,
                visited = visited,
                subtitleCallback = subtitleCallback,
                callback = callback,
                depth = depth + 1,
            )
        }
    }

    private fun decodeServerUrls(value: String): List<String> {
        val decodedValues = linkedSetOf<String>()
        val rawParts = value.split("|").map { it.trim() }
        
        for (part in rawParts) {
            val cleanValue = part.htmlUnescape().cleanEscaped()
            if (cleanValue.isBlank()) continue

            decodedValues.add(cleanValue)
            runCatching { URLDecoder.decode(cleanValue, "UTF-8") }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { decodedValues.add(it.htmlUnescape().cleanEscaped()) }

            decodeBase64Value(cleanValue)
                ?.takeIf { it.isNotBlank() }
                ?.let { decodedValues.add(it.htmlUnescape().cleanEscaped()) }
        }

        val results = linkedSetOf<String>()
        decodedValues.forEach { decoded ->
            val parsed = Jsoup.parse(decoded)
            parsed.select("iframe[src], iframe[data-src], embed[src], source[src], video[src], a[href]").forEach { element ->
                element.attr("data-src")
                    .ifBlank { element.attr("src") }
                    .ifBlank { element.attr("href") }
                    .takeIf { it.isNotBlank() }
                    ?.let(results::add)
            }
            extractKnownVideoUrls(decoded).forEach(results::add)
            if (results.isEmpty()) results.add(decoded)
        }

        return results.toList()
    }

    private fun decodeBase64Value(value: String): String? {
        val normalized = value.trim()
        if (normalized.length < 8) return null

        val b64Part = if (normalized.contains("|")) normalized.substringAfterLast("|").trim() else normalized

        return runCatching { base64Decode(b64Part) }.getOrNull()
            ?: runCatching {
                val fixed = b64Part
                    .replace('-', '+')
                    .replace('_', '/')
                    .let { raw ->
                        val padding = (4 - raw.length % 4) % 4
                        raw + "=".repeat(padding)
                    }

                String(android.util.Base64.decode(fixed, android.util.Base64.DEFAULT))
            }.getOrNull()
    }

    private fun extractKnownVideoUrls(rawText: String): List<String> {
        if (rawText.isBlank()) return emptyList()

        val decodedText = rawText.cleanEscaped()
        val urls = linkedSetOf<String>()

        Jsoup.parse(decodedText).select("iframe[src], iframe[data-src], embed[src], source[src], video[src], a[href]").forEach { element ->
            element.attr("data-src")
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
                .takeIf { it.isNotBlank() }
                ?.let { normalizeKnownVideoUrl(it) }
                ?.let { urls.add(it) }
        }

        Regex("""https?:\\?/\\?/[^\"'<>\\\s]+""", RegexOption.IGNORE_CASE)
            .findAll(decodedText)
            .mapNotNull { normalizeKnownVideoUrl(it.value) }
            .forEach { urls.add(it) }

        Regex("""(?i)(?:file|url|src|embed|video|videoUrl|video_url|hls|hlsUrl|embedUrl|embed_url)\s*[:=]\s*["']([^"']+)["']""")
            .findAll(decodedText)
            .mapNotNull { normalizeKnownVideoUrl(it.groupValues[1]) }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun normalizeKnownVideoUrl(url: String): String? {
        val absolute = normalizeAnyUrl(url, mainUrl) ?: return null
        return absolute
            .takeIf { candidate -> supportedHosts.any { candidate.contains(it, ignoreCase = true) } || isDirectMediaUrl(candidate) }
            ?.let { fixUrl(it) }
    }

    private fun normalizeAnyUrl(url: String, baseUrl: String): String? {
        val fixed = url.cleanEscaped().trim('"', '\'', ' ', '\n', '\r', '\t')
        if (fixed.isBlank()) return null

        return when {
            fixed.startsWith("//") -> "https:$fixed"
            fixed.startsWith("http://", true) || fixed.startsWith("https://", true) -> fixed
            fixed.startsWith("/") -> {
                val origin = Regex("""^https?://[^/]+""").find(baseUrl)?.value ?: mainUrl
                origin.trimEnd('/') + fixed
            }
            else -> runCatching { URI(baseUrl).resolve(fixed).toString() }.getOrNull()
        }
    }

    private fun normalizeDailymotionUrl(url: String): String? {
        if (!url.contains("dailymotion.com", true) && !url.contains("dai.ly", true)) return null

        val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        val videoId = listOf(
            Regex("""(?i)[?&]video=([A-Za-z0-9]+)"""),
            Regex("""(?i)dailymotion\.com/(?:embed/)?video/([A-Za-z0-9]+)"""),
            Regex("""(?i)dai\.ly/([A-Za-z0-9]+)"""),
        ).firstNotNullOfOrNull { regex -> regex.find(decoded)?.groupValues?.getOrNull(1) }
            ?: return url

        return if (decoded.contains("geo.dailymotion.com", true)) {
            "https://geo.dailymotion.com/player/xid0t.html?video=$videoId"
        } else {
            "https://www.dailymotion.com/embed/video/$videoId"
        }
    }

    private fun shouldUseExtractor(url: String): Boolean {
        return isPrimaryPlaybackHost(url, "")
    }

    private fun isPrimaryPlaybackHost(url: String, label: String): Boolean {
        val value = "$label $url".lowercase()
        if (isNoiseFrame(url)) return false
        // Skip pure ad-label servers only when the URL itself is an ads network (keep real hosts even if label says ADS)
        if (isAdsOnlyUrl(url)) return false
        return value.contains("dailymotion.com") ||
            value.contains("geo.dailymotion.com") ||
            value.contains("dai.ly") ||
            value.contains("ok.ru") ||
            value.contains("odnoklassniki.ru") ||
            value.contains("rumble.com") ||
            value.contains("vidguard") ||
            value.contains("vidhide") ||
            value.contains("morencius") ||
            value.contains("playmogo") ||
            value.contains("myvidplay") ||
            value.contains("dood") ||
            value.contains("abyssplayer") ||
            value.contains("abyss.to") ||
            value.contains("hgcloud") ||
            value.contains("hanerix") ||
            value.contains("turbovidhls") ||
            value.contains("turboviplay") ||
            value.contains("d.tube") ||
            value.contains("anichin-player.web.id") ||
            value.contains("streamruby") ||
            value.contains("rubyvidhub") ||
            value.contains("rpmshare") ||
            value.contains("rpmplay") ||
            value.contains("rpmvid") ||
            value.contains("earnvids") ||
            value.contains("smoothpre") ||
            value.contains("dhtpre") ||
            value.contains("peytonepre") ||
            value.contains("newplayr") ||
            value.contains("streamhg") ||
            value.contains("streamwish") ||
            value.contains("acek-cdn") ||
            value.contains("acefile") ||
            value.contains("pahe") ||
            value.contains("hxfile") ||
            value.contains("blogspot") ||
            value.contains("blogger") ||
            value.contains("google.com") ||
            value.contains("archive.org") ||
            value.contains("/embed") ||
            value.contains("/player") ||
            value.contains("/v/") ||
            value.contains("/video") ||
            value.contains("/t/") ||
            value.contains(".m3u8") ||
            value.contains(".mp4")
    }

    private fun isAdsOnlyUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("wearadmiration.com") ||
            value.contains("doubleclick") ||
            value.contains("googlesyndication") ||
            value.contains("popads") ||
            value.contains("exoclick") ||
            value.contains("propellerads")
    }

    private fun candidatePriority(url: String, label: String): Int {
        val value = "$label $url".lowercase()
        // Play order: Dailymotion (adaptive) → OK.ru → StreamRuby → rest → Rumble last
        return when {
            value.contains("dailymotion") || value.contains("dai.ly") ||
                (value.contains("anichin-player") && value.contains("url=")) -> 0
            value.contains("ok.ru") || value.contains("odnoklassniki.ru") ||
                (value.contains("anichin-player") && value.contains("ok=")) -> 1
            value.contains("streamruby") || value.contains("rubyvidhub") -> 2
            value.contains("morencius") || value.contains("earnvids") || value.contains("vidhide") -> 3
            value.contains("rpmshare") || value.contains("rpmvid") || value.contains("rpmplay") -> 4
            value.contains("abyssplayer") || value.contains("newplayr") ||
                value.contains("streamhg") || value.contains("streamwish") -> 5
            value.contains("vidguard") -> 6
            value.contains("dood") || value.contains("playmogo") -> 7
            // TurboVIP often remote-fail at high Q — low priority (and 1080 filtered)
            value.contains("turbovidhls") || value.contains("turboviplay") || value.contains("turbo") -> 8
            value.contains("rumble") -> 9
            value.contains("blogger") || value.contains("blogspot") || value.contains("google") -> 10
            else -> 12
        }
    }


    private fun shouldReadNestedPage(url: String): Boolean {
        return true
    }

    private fun shouldSkipBodyRead(contentType: String, contentLength: Long?): Boolean {
        return contentType.startsWith("video/") ||
            contentType.startsWith("audio/") ||
            contentType.contains("octet-stream") ||
            contentType.contains("application/vnd.apple.mpegurl") ||
            contentType.contains("application/x-mpegurl") ||
            contentType.contains("mpegurl") ||
            (contentLength != null && contentLength > MAX_NESTED_TEXT_BYTES)
    }

    private fun isNoiseFrame(url: String): Boolean {
        val value = url.lowercase()
        return value.isBlank() ||
            value.startsWith("#") ||
            value.startsWith("javascript") ||
            value.contains("facebook.com") ||
            value.contains("twitter.com") ||
            value.contains("telegram") ||
            value.contains("whatsapp") ||
            value.contains("youtube.com") ||
            value.contains("youtu.be") ||
            value.contains("trailer") ||
            value.contains("banner") ||
            value.contains("doubleclick") ||
            value.contains("googlesyndication") ||
            value.contains("analytics") ||
            value.contains("tracking") ||
            value.contains("popads")
    }

    private fun isDirectMediaUrl(url: String): Boolean {
        return url.contains(".m3u8", true) ||
            url.contains(".mp4", true) ||
            url.contains(".webm", true)
    }

    private fun qualityFromUrl(url: String): Int {
        // Prefer explicit resolution in path/query; map 818-class to 1080
        val height = Regex("""(?:x|h|=)(\d{3,4})(?:p|[^0-9]|$)""", RegexOption.IGNORE_CASE)
            .findAll(url)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 144..2160 }
            .maxOrNull()
        if (height != null) return normalizePlayQuality(height)

        return when {
            url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
            url.contains("1080", true) || url.contains("818", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun String.cleanEscaped(): String {
        return this
            .htmlUnescape()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u003D", "=")
            .replace("\\u0026", "&")
            .replace("\\\"", "\"")
            .trim()
    }

    private val supportedHosts = listOf(
        "dailymotion.com",
        "geo.dailymotion.com",
        "dai.ly",
        "ok.ru",
        "odnoklassniki.ru",
        "anichin-player.web.id",
        "morencius.com",
        "playmogo.com",
        "myvidplay.com",
        "abyssplayer.com",
        "abyss.to",
        "hgcloud.to",
        "hanerix.com",
        "rpmshare.com",
        "rpmshare.net",
        "rpmplay.me",
        "rpmvid.com",
        "earnvids.com",
        "smoothpre.com",
        "dhtpre.com",
        "peytonepre.com",
        "newplayr.com",
        "newplayr.org",
        "streamhg.com",
        "streamhg.net",
        "streamwish.to",
        "streamwish.com",
        "turbovidhls.com",
        "turboviplay.com",
        "d.tube",
        "vidguard.to",
        "listeamed.net",
        "bembed.net",
        "vgfplay.com",
        "rumble.com",
        "rubyvidhub.com",
        "streamruby.com",
        "streamruby.net",
        "acek-cdn.com",
    )


    private fun String.htmlUnescape(): String {
        return this
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }
}