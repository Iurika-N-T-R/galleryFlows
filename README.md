# galleryFlows

Free, open-source gallery for Android that browses your **real folder tree**: Folder › Subfolder › Subfolder › … › Images.

[![license](https://img.shields.io/badge/license-MIT-blue?style=flat-square)](LICENSE)
[![release](https://img.shields.io/github/v/release/Iurika-N-T-R/galleryFlows?style=flat-square)](../../releases/latest)

Most galleries show one flat list of every folder that holds pictures: `Camera`, `Screenshots`, `Wallpapers`… and you never see how they are nested. galleryFlows starts from the folders you choose and lets you walk down them level by level, like a file manager that only shows your media.

galleryFlows is an independent app written from scratch, with no dependencies. It is not affiliated with Fossify or Simple Mobile Tools. It has no internet permission: nothing leaves your phone.

## Using it
**Main screen**: your favorites, the folders you added (DCIM and Pictures at first), and the trash. **⋮ → Ajouter un dossier** adds any folder from internal storage or the SD card.

**Inside a folder**
- Subfolders come first, as cards with a cover and the number of media in the **whole subtree**; then the folder's own pictures and videos. Folders with no media anywhere below them are left out.
- A folder with nothing of its own but a single subfolder opens straight into that subfolder (as many levels down as it goes); Back skips them on the way up too.
- The **breadcrumb** (`Galerie › Pictures › Media › Selfgrow`) jumps back to any level; Back goes up one level and keeps your scroll position.
- **Search** looks through the current folder and everything below it, for files or (a setting) for folders.
- **Long press** a media to select several: bookmark, share, copy, move, delete. Copy and move open "Sélectionner la destination": every folder with media as a card with its cover (recent destinations first), a search field, the eye for hidden folders; a tap picks the folder, "Autre dossier" browses to any other.
- **Long press** a folder: pin it at the top, rename, hide it (`.nomedia`), exclude it, add it to the main screen, or create a home-screen shortcut to it.

**Bookmarks** (the bookmark button next to the search bar opens the sidebar): long-press a folder, or select images, then "Ajouter à un bookmark" to put them in a bookmark such as *Art* or *Références*, new or existing. Opening a bookmark shows only its folders and images; from there you browse into them as usual, and Back comes back to the bookmark.

**⋮ menu**: sort (name, location, dimensions, size, modified date, date taken, random, or custom: long-press items and put them first; ascending or descending), filter (images, videos, GIF, RAW, SVG, portrait shots), grid or list, 1 to 20 columns for folders and for media, show hidden files, new folder, shuffle, settings.

**Viewer**
- Swipe left / right for the next / previous media, swipe down to close, pinch or double-tap to zoom (optionally to 1:1), turn with two fingers, tap to show or hide the bars. Zoomed in, the visible part is reloaded at the file's full resolution.
- Bottom bar: you pick its buttons (favorite, edit, share, delete, properties by default).
- **⋮**: everything else: copy, move, rename, rotate (lossless for JPEG), resize, edit with another app, hide, open with, set as wallpaper / contact photo, show on map, print, slideshow, home-screen shortcut, orientation (system, portrait, landscape, or following the media).
- RAW photos show their embedded preview when Android can't decode them; SVG drawings are rendered by the system WebView.
- **Videos**: play / pause, seek bar, resume where you stopped, loop and autoplay options; drag up / down on the left half for brightness, on the right half for volume.

**Editor**: crop (free, original, 1:1, 4:3, 3:4, 16:9, 9:16), rotate, flip, brightness / contrast / saturation. Replace the original or save a copy; the date taken and GPS position are kept.

**Trash**: deleted media go to a hidden trash folder on their own storage (so deleting a large video is instant). Restore puts them back where they were; anything older than 30 days is removed for good.

**Settings**: accent and background colors, English instead of French, date and time format, thumbnail size (fast / balanced / best quality), thumbnails (square crop, animated GIFs, square / rounded / circle styles), horizontal scrolling, pull to refresh, videos, fullscreen (max brightness, black background, auto-hide, tap the sides to change media, brightness by vertical drag, swipe down to close, notch, details overlay, slideshow speed), zoom, viewer action bar, security (lock the app, hidden items, or delete / move operations behind your fingerprint, face or PIN), file operations, trash, thumbnail cache, export / import of favorites and of all settings.

Thumbnails are kept in the app's own storage (up to 1 GB, least recently used dropped first), not in its cache, which Android empties whenever storage runs low.

**With other apps**: "Open with galleryFlows" opens a file and lets you swipe through the rest of its folder; apps that ask for a picture or a video can use galleryFlows as the picker.

## Install
Download the APK from the [latest release](../../releases/latest). Needs Android 11+.

On first launch, allow **access to all files**: galleryFlows reads folders directly (internal storage and SD card) instead of going through the system media index, which is what makes the folder tree possible.

## Build
```
./gradlew assembleRelease
```
Needs JDK 17–21 and the Android SDK (platform 36).
Release builds are signed only if a `keystore.properties` file sits at the project root (git-ignored); otherwise they come out unsigned:
```
storeFile=/path/to/galleryflows-release.jks
storePassword=…
keyAlias=galleryflows
keyPassword=…
```

## Permissions
- `MANAGE_EXTERNAL_STORAGE` (all files access): browse real folders, and copy, move, rename or delete files in them.
- `USE_BIOMETRIC`: the optional app lock.

## License
galleryFlows is MIT-licensed (see [LICENSE](LICENSE)).
