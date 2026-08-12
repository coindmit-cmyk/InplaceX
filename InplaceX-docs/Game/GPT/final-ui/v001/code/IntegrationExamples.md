# Integration Examples

Ниже — направление интеграции. Codex должен адаптировать snippets к текущему файлу, сохранить state mapping и тестовые tags.

## 1. Adaptive board weights

```kotlin
BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val compactHeight = maxHeight < 650.dp
    val metrics = remember(uiState.parameters.codeLength, compactHeight) {
        finalGameFieldMetrics(uiState.parameters.codeLength, compactHeight)
    }

    Row(
        modifier = Modifier.fillMaxWidth().weight(1f),
        horizontalArrangement = Arrangement.spacedBy(FinalUiDimens.SectionGap),
    ) {
        WarmPanel(
            modifier = Modifier.weight(metrics.attemptsWeight).fillMaxHeight(),
            shapeRadius = 16.dp,
        ) { /* attempts */ }
        WarmPanel(
            modifier = Modifier.weight(metrics.matrixWeight).fillMaxHeight(),
            shapeRadius = 16.dp,
        ) { /* matrix */ }
    }
}
```

## 2. Structured attempt rows

Не передавать presentation data как заранее собранную строку, если можно сохранить `guess` и `score` отдельно.

```kotlin
val attempts = uiState.match.attempts
LazyColumn(...) {
    itemsIndexed(attempts) { index, attempt ->
        CompactAttemptRow(
            guess = attempt.guess,
            score = attempt.score,
            latest = index == attempts.lastIndex,
            contentDescription = strings.text("game.attempt.row_description")
                .replace("{guess}", attempt.guess)
                .replace("{score}", attempt.score.toString()),
            textSize = metrics.attemptTextSize,
            modifier = Modifier
                .height(metrics.attemptRowHeight)
                .testTag("game-attempt-${index + 1}"),
        )
    }
}
```

Если новый localization key нецелесообразен, semantics можно собрать без изменения видимого текста; бизнес-логика от строки зависеть не должна.

## 3. Matrix sizing

```kotlin
val columns = uiState.parameters.codeLength
val cellSize = minOf(
    (maxWidth - metrics.matrixGap * (columns - 1)) / columns,
    (maxHeight - metrics.matrixGap * 9) / 10,
)
```

Сохраняется существующий `analysisVisualFor(...)`. Он маппится в `AnalysisCellVisualState`, не меняя факты/marks.

## 4. Top info strip

Вместо четырёх вложенных surfaces использовать одну Row:

```text
[Гонка] | [Ходы 0/20] | [Общее 05:59] | [Ход 00:01]
```

Vertical divider 1dp, alpha 0.34; одинаковый baseline value.

## 5. Segmented tools

Parent `WarmPanel`/warm group radius 14dp; четыре `WarmSegmentButton` с `weight(1f)`. Выбор и auto-mode callbacks остаются прежними.

## 6. Production ad

`AppBottomAd` не должен помещать реальный `YandexGameBanner` в фиолетовый декоративный row. Для `content != null` использовать neutral clipping/container; стилизованный AD placeholder оставлять только для debug/no-content.
