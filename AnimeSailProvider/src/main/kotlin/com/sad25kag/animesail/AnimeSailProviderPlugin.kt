package com.sad25kag.animesail

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeSailProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeSailProvider())

        // Host extractors
        registerExtractorAPI(MixDropBz())
        registerExtractorAPI(MixDropAg())
        registerExtractorAPI(MixDropTo())
        registerExtractorAPI(MixDropClub())
        registerExtractorAPI(MixDropCom())
        registerExtractorAPI(MixDropSi())
        registerExtractorAPI(MixDropMiiiix())
        registerExtractorAPI(MixDropNet())
        registerExtractorAPI(Mp4UploadFix())
        registerExtractorAPI(Mp4UploadOrg())
        registerExtractorAPI(Pixeldrain())
        registerExtractorAPI(PixeldrainTo())
        registerExtractorAPI(Krakenfiles())
        registerExtractorAPI(DoodStreamSail()) // Dodo / doply — single
    }
}
