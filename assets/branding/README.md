# Иконка и баннер VideoGallery

## Версия 3 (актуальная)

Иконка и баннер теперь векторные и строятся из геометрии кота `PopcornCatLoader` (те же координаты путей, система 160×160): один талисман на весь продукт. Фон `#1C1B1F`, за котом большой Play-треугольник с оранжевым градиентом (`#FF9800` → `#F4511E`), кот отделён от него тёмным ореолом (силуэт кота, обведённый тёмным контуром), ведро попкорна красно-бело-синее (красные полосы по краям, синяя по центру).

Мастера SVG:

- [videogallery-icon-v3.svg](videogallery-icon-v3.svg), viewBox 108×108 (геометрия adaptive-иконки, безопасная зона 66×66);
- [videogallery-banner-v3.svg](videogallery-banner-v3.svg), viewBox 320×180, эмблема по центру, без текста.

Ресурсы Android (VectorDrawable, та же геометрия):

- [ic_launcher_background.xml](../../app/src/main/res/drawable/ic_launcher_background.xml), [ic_launcher_foreground.xml](../../app/src/main/res/drawable/ic_launcher_foreground.xml);
- [ic_launcher.xml](../../app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml), `<adaptive-icon>`;
- [banner.xml](../../app/src/main/res/drawable/banner.xml), 320×180 dp.

Старые `mipmap-*dpi/ic_launcher.png` и `drawable-xhdpi/banner.png` удалены, чтобы не перекрывать векторные ресурсы. PNG v1 и v2 оставлены как история. Вид в лаунчере ТВ и сборка APK не проверялись.

## История: версия 2

Исходник: [videogallery-icon-v2.png](videogallery-icon-v2.png), 1254×1254 px, сгенерирован встроенным `imagegen` 2026-11-03 с v1 как визуальным референсом. Киноплёнка в форме Play, довольный кот с попкорном, янтарный акцент `AppColors.Accent` (`#FFB300`), тёмные детали `AppColors.OnAccent` (`#1A1200`) и фон в гамме `AppColors.Background` (`#1C1B1F`). Светлые тёплые детали и мягкие оттенки — часть сгенерированного изображения.

Тот же персонаж воспроизведён геометрией Compose в [PopcornCatLoader.kt](../../app/src/main/java/dev/gamspi/videogallery/ui/components/PopcornCatLoader.kt): треугольные уши, закрытые довольные глаза, круглые светлые щёки, хвост и полосатое ведро.

### Промпт иконки (полный)

```text
Use case: logo-brand. Create a production square 1024x1024 or larger Android TV launcher icon for VideoGallery, version 2. Input image is a reference for the folded filmstrip forming a right-facing Play sign: retain that recognizable folded triangular ribbon and its few large sprocket holes, recolor it from turquoise/blue to warm amber. Combine it with a cute simple cartoon amber cat peeking from behind the Play ribbon, holding a small popcorn bucket in front. Cat identity to match a Compose loading mascot: broad rounded head, pointed triangular ears, amber fur, darker inner ears, two closed happy crescent eyes, tiny dark triangular nose, two round light amber muzzle cheeks, short whiskers, plump seated body and a curved tail to one side. Bucket is tapered with bold alternating amber and pale cream vertical stripes, a thick rim, and several large rounded popcorn kernels. Play silhouette and cat face must BOTH be legible when reduced to 48 pixels. Flat polished vector-like shapes, bold silhouette, minimal large details, restrained warm shading only to clarify the ribbon fold. Exact main palette #FFB300 amber, #1A1200 dark details, pale warm amber highlights; uniform full-bleed background #1C1B1F. Center the whole emblem, generous safe margins at least 12 percent on all four edges, no exterior rounded app tile border, no text, no letters, no watermark, no stars or extra decoration. One icon only, no mockup, no size comparison sheet.
```

### Баннер

Ресурс баннера (`drawable-xhdpi/banner.png`, удалён в v3) создан отдельным вызовом встроенного `imagegen` с v2 как референсом; исходный результат 1672×941 px уменьшен до 640×360 px. Это xhdpi-ресурс для 320×180 dp, с тем же знаком и свободным тёмным пространством по бокам, без текста.

Промпт баннера (полный):

```text
Use case: logo-brand. Create a wide 16:9 Android TV launcher banner, 1536x864 or larger, for VideoGallery using the provided v2 launcher icon as the exact visual identity reference. Keep the same amber folded filmstrip forming a right-facing Play sign with three large sprocket holes, the same happy amber cat with closed crescent eyes, pointed ears, round pale cheeks, curved tail and striped popcorn bucket nestled within the sign. Preserve this emblem as one single bold intact recognizable mark; do not redesign the cat. Put the complete emblem centered in the banner, about 75 percent of canvas height with ample quiet dark negative space to the left and right. Exact dark charcoal background #1C1B1F across the entire canvas, amber #FFB300 and warm cream details, restrained shading on film folds, crisp polished vector-like illustration. No text, no letters, no border, no watermark, no extra decoration. Readable at 320x180. One finished full-bleed banner.
```

### Размеры и подключение

Историческая справка: ресурсы ниже удалены из приложения в версии 3, ссылки на них не действуют.

| Ресурс (удалён) | Размер PNG |
|---|---|
| `mipmap-mdpi/ic_launcher.png` | 48×48 px |
| `mipmap-hdpi/ic_launcher.png` | 72×72 px |
| `mipmap-xhdpi/ic_launcher.png` | 96×96 px |
| `mipmap-xxhdpi/ic_launcher.png` | 144×144 px |
| `mipmap-xxxhdpi/ic_launcher.png` | 192×192 px |
| `drawable-xhdpi/banner.png` | 640×360 px |

PNG уменьшены с `System.Drawing`, `HighQualityBicubic`, `HighQuality` pixel offset и `TileFlipXY` по краям. Все пять иконок получены непосредственно из исходника v2. Это квадратная непрозрачная иконка; отдельных adaptive/monochrome слоёв нет.

В [AndroidManifest.xml](../../app/src/main/AndroidManifest.xml) сохранены существующие ссылки `android:icon="@mipmap/ic_launcher"` и `android:banner="@drawable/banner"`; файл в этой задаче не редактировался.

Проверены размеры PNG и вид файлов 48 px, 192 px и баннера. Мелкие детали кота в 48 px упрощаются; основной знак Play и силуэт кота сохраняются. Сборка APK, упаковка ресурсов и вид в лаунчере ТВ в этой задаче не проверялись.

## История: версия 1

[videogallery-icon-v1.png](videogallery-icon-v1.png), 1254×1254 px, сохранён без изменений. Сгенерирован встроенным `imagegen` 2026-11-03.

Исходный промпт: квадратная иконка Android TV для VideoGallery; киноплёнка образует знак Play, бирюзово-синий градиент на тёмно-синем фоне, крупный силуэт, безопасные поля, без текста и внешних скруглений.

В ресурсах приложения v1 заменена версией v2.
