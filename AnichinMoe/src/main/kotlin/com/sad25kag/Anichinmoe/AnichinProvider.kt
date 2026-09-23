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

        // VidGuard — every mirror host needs its own registration, `loadExtractor` only
        // matches an API whose mainUrl is a prefix of the link (listeamed.net is what
        // Anichin actually embeds for "Vidguard").
        registerExtractorAPI(Vidguardto())
        registerExtractorAPI(Vidguardto1())
        registerExtractorAPI(Vidguardto2())
        registerExtractorAPI(Vidguardto3())

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

        // Odysee / LBRY
        registerExtractorAPI(Odysee())

        // Anichin proxy (OK.ru / Dailymotion wrapper)
        registerExtractorAPI(AnichinPlayerProxy())

        // StreamWish / NewPlayr
        registerExtractorAPI(Newplayr())
        registerExtractorAPI(NewplayrSub())
        registerExtractorAPI(StreamWish())
        registerExtractorAPI(StreamWishSub())
    }
}
