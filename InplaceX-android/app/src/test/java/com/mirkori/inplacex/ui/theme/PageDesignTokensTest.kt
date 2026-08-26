package com.mirkori.inplacex.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageDesignTokensTest {
    @Test fun primaryAndSecondaryTextStayReadableOnBothCreamSurfaces() {
        for (surface in listOf(PageColors.Cream, PageColors.CreamSecondary)) {
            for (text in listOf(PageColors.Text, PageColors.TextSecondary)) {
                assertTrue(contrast(text, surface) >= 4.5f)
            }
        }
        assertTrue(contrast(Color.White, PageColors.Primary) >= 4.5f)
        assertTrue(contrast(Color.White, PageColors.Friends) >= 4.5f)
        assertTrue(contrast(PageColors.Text, PageColors.Company) >= 4.5f)
        assertTrue(contrast(PageColors.Text, PageColors.Shop) >= 4.5f)
    }

    @Test fun pageSizingMatchesOwnerContractWithoutChangingGameplay() {
        assertEquals(16.dp, PageDimens.Margin)
        assertEquals(12.dp, PageDimens.Gap)
        assertEquals(48.dp, PageDimens.TouchTarget)
        assertEquals(24.dp, PageDimens.HeroRadius)
        assertEquals(20.dp, PageDimens.CardRadius)
        assertEquals(14.dp, FinalUiDimens.PanelRadius)
        assertEquals(10.dp, FinalUiDimens.ButtonRadius)
    }

    private fun contrast(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)
}
