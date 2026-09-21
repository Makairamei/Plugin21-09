package com.sad25kag.Anichinmoe

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnichinProvider : Plugin() {
    override fun load(context: Context) {
        Anichin.context = context
        registerMainAPI(Anichin())

        // Core hosts
        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(Geodailymotion())
        registerExtractorAPI(Odnoklassniki())
        registerExtractorAPI(OkRuSSL())
        registerExtractorAPI(OkRuHTTP())
        registerExtractorAPI(Rumble())

        // StreamRuby family (multi-quality m3u8)
        registerExtractorAPI(StreamRuby())
        registerExtractorAPI(StreamRubyCom())
        registerExtractorAPI(StreamRubyNet())
        registerExtractorAPI(Rubyvidhub())

        // VidGuard
        registerExtractorAPI(Vidguardto())

        // Earnvids / VidHide family
        registerExtractorAPI(Morencius())
        registerExtractorAPI(Earnvids())
        registerExtractorAPI(Smoothpre())
        registerExtractorAPI(Dhtpre())
        registerExtractorAPI(Peytonepre())

        // RpmShare / rpmvid
        registerExtractorAPI(Rpmshare())
        registerExtractorAPI(RpmshareSub())
        registerExtractorAPI(Rpmplay())
        registerExtractorAPI(Rpmvid())

        // New Player / Abyss
        registerExtractorAPI(Abyssplayer())
        registerExtractorAPI(AbyssplayerRoot())

        // StreamHG
        registerExtractorAPI(Hgcloud())
        registerExtractorAPI(Hanerix())
        registerExtractorAPI(Streamhg())
        registerExtractorAPI(StreamhgSub())

        // Doods
        registerExtractorAPI(DoodPlaymogo())
        registerExtractorAPI(DoodMyvidplay())

        // TurboVIP direct HLS
        registerExtractorAPI(Turbovidhls())

        // D-Tube
        registerExtractorAPI(Dtube())

        // Anichin proxy (OK.ru / Dailymotion wrapper)
        registerExtractorAPI(AnichinPlayerProxy())

        // StreamWish / NewPlayr
        registerExtractorAPI(Newplayr())
        registerExtractorAPI(NewplayrSub())
        registerExtractorAPI(StreamWish())
        registerExtractorAPI(StreamWishSub())
    }
}
