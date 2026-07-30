package com.mirkori.inplacex.platform.ads

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener as GoogleAdListener
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.huawei.hms.ads.AdListener as HuaweiAdListener
import com.huawei.hms.ads.BannerAdSize
import com.huawei.hms.ads.banner.BannerView
import com.mirkori.inplacex.platform.logging.AppLog

@Composable
fun AdMobGameBanner(
    adUnitId: String,
    consentDecision: AdConsentDecision,
    onLoaded: () -> Unit,
    onFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (adUnitId.isBlank() || consentDecision == AdConsentDecision.UNDECIDED) return
    val context = LocalContext.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val widthDp = maxWidth.value.toInt().coerceAtLeast(1)
        val adView = remember(adUnitId, widthDp) {
            AdView(context).apply {
                this.adUnitId = adUnitId
                setAdSize(AdSize.getLargeAnchoredAdaptiveBannerAdSize(context, widthDp))
                adListener = object : GoogleAdListener() {
                    override fun onAdLoaded() {
                        AppLog.info(
                            tag = "AdRuntime",
                            message = "Banner loaded",
                            attributes = mapOf("provider" to "COMPANY_ADMOB"),
                        )
                        onLoaded()
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        AppLog.warn(
                            tag = "AdRuntime",
                            message = "Banner load failed",
                            attributes = mapOf("provider" to "COMPANY_ADMOB"),
                        )
                        onFailed()
                    }
                }
                loadAd(buildAdMobRequest(consentDecision))
            }
        }
        DisposableEffect(adView) {
            onDispose(adView::destroy)
        }
        AndroidView(
            factory = { adView },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun HuaweiGameBanner(
    adUnitId: String,
    consentDecision: AdConsentDecision,
    onLoaded: () -> Unit,
    onFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (adUnitId.isBlank() || consentDecision == AdConsentDecision.UNDECIDED) return
    val context = LocalContext.current
    val bannerView = remember(adUnitId) {
        BannerView(context).apply {
            adId = adUnitId
            bannerAdSize = BannerAdSize.BANNER_SIZE_SMART
            adListener = object : HuaweiAdListener() {
                override fun onAdLoaded() {
                    AppLog.info(
                        tag = "AdRuntime",
                        message = "Banner loaded",
                        attributes = mapOf("provider" to "COMPANY_HUAWEI"),
                    )
                    onLoaded()
                }

                override fun onAdFailed(errorCode: Int) {
                    AppLog.warn(
                        tag = "AdRuntime",
                        message = "Banner load failed",
                        attributes = mapOf("provider" to "COMPANY_HUAWEI"),
                    )
                    onFailed()
                }
            }
            loadAd(buildHuaweiAdRequest(consentDecision))
        }
    }
    DisposableEffect(bannerView) {
        onDispose(bannerView::destroy)
    }
    AndroidView(
        factory = { bannerView },
        modifier = modifier.fillMaxWidth(),
    )
}
