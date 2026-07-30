package com.mirkori.inplacex.platform.ads

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.mirkori.inplacex.platform.logging.AppLog
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.compose.Banner
import com.yandex.mobile.ads.compose.BannerEvents
import com.yandex.mobile.ads.compose.BannerSize
import com.yandex.mobile.ads.compose.rememberBannerAdState

@Composable
fun YandexGameBanner(
    adUnitId: String,
    onLoaded: () -> Unit,
    onFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (adUnitId.isBlank()) return
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val state = rememberBannerAdState(
            adSize = BannerSize.Sticky(width = maxWidth),
            events = BannerEvents(
                onAdLoaded = {
                    AppLog.info(
                        tag = "AdRuntime",
                        message = "Banner loaded",
                        attributes = mapOf("provider" to "OWNER_YANDEX"),
                    )
                    onLoaded()
                },
                onAdFailedToLoad = {
                    AppLog.warn(
                        tag = "AdRuntime",
                        message = "Banner load failed",
                        attributes = mapOf("provider" to "OWNER_YANDEX"),
                    )
                    onFailed()
                },
            ),
        )
        LaunchedEffect(state, adUnitId) {
            state.loadAd(AdRequest.Builder(adUnitId).build())
        }
        Banner(
            state = state,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
