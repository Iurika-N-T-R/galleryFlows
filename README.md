# Gallery Flow

An Android gallery that browses your **real folder tree**: Folder › Subfolder › Subfolder › … › Images.

Classic galleries (Fossify, Simple Gallery…) show one flat list of every folder that contains pictures. Gallery Flow starts from the folders you choose, and every screen shows that folder's subfolders (with a cover and a media count for the whole subtree), then its own pictures and videos. A clickable breadcrumb takes you back up any number of levels.

No dependencies, ~2 MB, Android 11+.

## Features

- **Browsing**: roots you pick (internal storage and SD card), breadcrumb, recursive search, sort by name / modified date / date taken / size / random, filter images / videos / GIF, grid or list, separate column counts for folders and media, pinned folders, folder shortcuts on the home screen, hide (`.nomedia`) / exclude folders, show hidden files
- **Viewer**: pinch and double-tap zoom, swipe between media, swipe down to close, animated GIFs, built-in video player (resume position, loop, autoplay, brightness / volume gestures), slideshow, details overlay, properties (EXIF, GPS)
- **Editor**: crop (free, original, 1:1, 4:3, 3:4, 16:9, 9:16), rotate, flip, brightness / contrast / saturation; replace the original or save a copy, keeping the EXIF dates and GPS
- **Files**: share, copy, move, rename, hide, delete, multi-select, lossless JPEG rotation, resize, print, show on map, set as wallpaper / contact photo, open with
- **Trash**: one per storage volume (deleting is instant, even for large videos), restore, auto-purge after 30 days
- **Security**: app lock, protection of hidden items and of delete / move operations (fingerprint, face or device PIN)
- **Integration**: "Open with Gallery Flow" from other apps (browse the neighbouring files from there), image / video picker for other apps
- Settings export / import (JSON)

## Build

```sh
JAVA_HOME=/path/to/jdk-21 ./gradlew assembleRelease
```

Release signing reads `keystore.properties` (git-ignored) at the project root:

```
storeFile=/path/to/galleryflow-release.jks
storePassword=…
keyAlias=galleryflow
keyPassword=…
```

Without that file, release builds come out unsigned.

## Permissions

- `MANAGE_EXTERNAL_STORAGE` (all files access): needed to walk real folders on internal storage and the SD card, and to copy, move or delete files in them. The app asks for it on first launch.
- `USE_BIOMETRIC`: for the optional app lock.
