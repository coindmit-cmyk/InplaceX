package com.mirkori.inplacex.platform.localization

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameLocalizationCatalogTest {
    @Test
    fun `game catalogs have matching keys and placeholders`() {
        assertEquals(GameCatalog.ru.keys.sorted(), GameCatalog.en.keys.sorted())

        GameCatalog.ru.keys.forEach { key ->
            assertEquals(
                "placeholder mismatch for $key",
                placeholders(GameCatalog.ru.values.getValue(key)),
                placeholders(GameCatalog.en.values.getValue(key)),
            )
        }
    }

    @Test
    fun `every scoped localization key resolves in both languages`() {
        val usedKeys = scopedComposeSources()
            .flatMap { source -> localizationKeyPattern.findAll(source).map { it.groupValues[1] } }
            .toSet()

        assertTrue("expected scoped localization keys", usedKeys.isNotEmpty())
        AppLanguage.values().forEach { language ->
            val strings = StaticLocalizationProvider.forLanguage(language)
            usedKeys.forEach { key ->
                assertFalse(
                    "$language falls back to key $key",
                    strings.text(key) == key,
                )
            }
        }
    }

    @Test
    fun `catalog and scoped screens contain no mojibake or hard coded phrases`() {
        val sources = scopedComposeSources()
        val values = GameCatalog.ru.values.values + GameCatalog.en.values.values

        (sources + values).forEach { text ->
            assertFalse("replacement character found", text.contains('\uFFFD'))
            assertFalse("likely UTF-8 mojibake found", mojibakePattern.containsMatchIn(text))
        }

        sources.forEach { source ->
            val withoutComments = lineCommentPattern.replace(
                blockCommentPattern.replace(source, ""),
                "",
            )
            val directTextLiterals = (directTextPattern.findAll(withoutComments) +
                directContentDescriptionPattern.findAll(withoutComments))
                .map { it.groupValues[1] }
                .map(::withoutInterpolation)
                .filter { literal -> literal.count(Char::isLetter) > 1 }
                .toList()
            assertTrue(
                "hard-coded Text literals found: $directTextLiterals",
                directTextLiterals.isEmpty(),
            )

            val phraseLiterals = stringLiteralPattern.findAll(withoutComments)
                .map { it.value.removeSurrounding("\"") }
                .map(::withoutInterpolation)
                .filter(cyrillicPattern::containsMatchIn)
                .toList()
            assertTrue(
                "hard-coded phrase literals found: $phraseLiterals",
                phraseLiterals.isEmpty(),
            )
        }
    }

    @Test
    fun `all same digits explanation is complete in ru and en`() {
        assertEquals(
            "Нельзя вводить комбинацию из одинаковых цифр",
            StaticLocalizationProvider.forLanguage(AppLanguage.RU)
                .text("game.validation.all_same_digits"),
        )
        assertEquals(
            "The combination cannot contain only one repeated digit",
            StaticLocalizationProvider.forLanguage(AppLanguage.EN)
                .text("game.validation.all_same_digits"),
        )
    }

    private fun scopedComposeSources(): List<String> = scopedSourcePaths.map { relativePath ->
        val candidates = listOf(
            File("src/main/java/$relativePath"),
            File("InplaceX-android/app/src/main/java/$relativePath"),
        )
        candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Cannot locate scoped source: $relativePath")
    }

    private fun placeholders(value: String): Set<String> =
        placeholderPattern.findAll(value).map { it.value }.toSet()

    private fun withoutInterpolation(value: String): String =
        simpleInterpolationPattern.replace(
            blockInterpolationPattern.replace(value, ""),
            "",
        )

    private companion object {
        val scopedSourcePaths = listOf(
            "com/mirkori/inplacex/ui/GameScreen.kt",
            "com/mirkori/inplacex/ui/screens/game/GameFieldDebugScreen.kt",
            "com/mirkori/inplacex/ui/screens/game/GameFieldScreen.kt",
            "com/mirkori/inplacex/ui/screens/game/presentation/GamePresentationComponents.kt",
            "com/mirkori/inplacex/ui/screens/race/RaceGameScreen.kt",
            "com/mirkori/inplacex/ui/screens/race_setup/RaceSetupScreen.kt",
        )
        val placeholderPattern = Regex("""\{[A-Za-z0-9_]+}""")
        val localizationKeyPattern = Regex("\"((?:game|top)\\.[^\"]+)\"")
        val blockCommentPattern = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val lineCommentPattern = Regex("""//.*""")
        val directTextPattern = Regex(
            """Text\(\s*(?:text\s*=\s*)?"((?:\\.|[^"\\])*)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val directContentDescriptionPattern = Regex(
            """contentDescription\s*=\s*"((?:\\.|[^"\\])*)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val stringLiteralPattern = Regex(""""(?:\\.|[^"\\])*"""")
        val blockInterpolationPattern = Regex("""\$\{[^}]+}""")
        val simpleInterpolationPattern = Regex("""\$[A-Za-z_][A-Za-z0-9_.]*""")
        val cyrillicPattern = Regex("""[А-Яа-яЁё]""")
        val mojibakePattern = Regex("""[\u00C2\u00C3\u00D0\u00D1]""")
    }
}
