Заменить файл:
app/src/main/java/com/mirkori/inplacex/ui/screens/game/GameFieldScreen.kt

Что сохранено из дизайна:
- вся модульная структура экрана
- размеры блоков от высоты/ширины экрана
- TopModule / GameInfoModule / VariantsModule / InputModule / DigitsModule / CheckModule
- общий layout PvE

Что добавлено:
- реактивная таблица
- клик по ячейке
- N / M / Y реально ставятся
- Y фиксирует цифру ровно в нужной позиции
- поле ввода собирается из YES по колонкам
- debug-секрет и статус
- реальная проверка попытки
- сброс матча
