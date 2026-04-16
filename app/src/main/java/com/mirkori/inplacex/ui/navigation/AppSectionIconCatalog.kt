package com.mirkori.inplacex.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.ui.graphics.vector.ImageVector

data class SectionIconSpec(
    val sourcePath: String,
    val fallbackIcon: ImageVector,
)

object AppSectionIconCatalog {
    fun spec(section: AppSection): SectionIconSpec {
        return when (section) {
            AppSection.HOME -> SectionIconSpec(
                sourcePath = "image/icon/section_home.svg",
                fallbackIcon = Icons.Outlined.Home
            )

            AppSection.SOCIAL -> SectionIconSpec(
                sourcePath = "image/icon/section_social.svg",
                fallbackIcon = Icons.Outlined.Groups
            )

            AppSection.COMPANY -> SectionIconSpec(
                sourcePath = "image/icon/section_tournaments.svg",
                fallbackIcon = Icons.Outlined.EmojiEvents
            )

            AppSection.SHOP -> SectionIconSpec(
                sourcePath = "image/icon/section_shop.svg",
                fallbackIcon = Icons.Outlined.ShoppingBag
            )

            AppSection.PROFILE -> SectionIconSpec(
                sourcePath = "image/icon/section_profile.svg",
                fallbackIcon = Icons.Outlined.Person
            )
        }
    }
}
