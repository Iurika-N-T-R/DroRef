# DroRef

Free, open-source reference board for Android.

Inspired by [PureRef](https://www.pureref.com). DroRef is an independent app written from scratch and is not affiliated with PureRef or Idyllic Pixel. **On PC, use the original: https://www.pureref.com**

Boards move between the two as `.pur` files: *File → Open .pur*, *Save*, *Save as .pur*.
From PureRef 2, save with *Save as → Legacy PureRef scene* first.

## Using it
- The canvas fills the screen (dot background by default; grid or plain in the menu, under View). Round buttons: menu and scene name top-left, undo, redo and focus mode top-right, add bottom-right.
- The menu is a panel of icon tiles grouped by purpose (Add, Board, File, View); search, settings and about sit in its header.
- **Add** images and videos from the gallery, paste them, paste a link, use *Share → DroRef*, or **drag and drop** them from another app (gallery, browser, files) in split screen: they land where you drop them.
- To drag an image **out** to another app, select it, then long-press it again and drag.
- Drag empty space to pan, pinch to zoom, double-tap to focus.
- Tap to select. Drag to move; pinch or twist with two fingers to scale and rotate.
- Long-press an item to add it to the selection. Long-press empty space and drag to select a rectangle; release without dragging for the canvas panel.
- What's selected gets a short toolbar of icons (long-press one to see its name); "more" opens everything else, grouped: Transform, Arrange, Align, Size, Layers & groups, Item.
- *Always on top* floats the board over your drawing app. Its bar has opacity, transparent-to-touch (for tracing), background off (only the images float), and a pin to show it only over one app (Android asks once for usage access).

Everything PureRef has, and what's done here: [FEATURES.md](FEATURES.md)

## Build
Open this folder in Android Studio and press Run, or from a terminal:

    ./gradlew assembleDebug

The APK lands in `app/build/outputs/apk/debug/`.

Test the `.pur` reader and writer:

    ./gradlew testDebugUnitTest -PpurDir=/tmp/pur

That writes `kt_edit.pur` into the folder. Check it with real PureRef: `PureRef -c 'load;/tmp/pur/kt_edit.pur' -c 'exportScene;/tmp/pur/out.png' -c exit`

The launcher icon comes from `art/icon.png`: run `python3 -I tools/icon.py` to regenerate every size.

Translations live in one table, `tools/strings.py`. Edit it and run `python3 tools/strings.py` to regenerate every `strings.xml`.
`tools/ui.py` drives an emulator for quick UI checks (tap by label, screenshot).

## Release
Download the APK from [Releases](../../releases), or build it:

    ./gradlew assembleRelease   # signed APK  -> app/build/outputs/apk/release/
    ./gradlew bundleRelease     # signed AAB for Google Play -> app/build/outputs/bundle/release/

Signing reads `keystore.properties` (git-ignored): `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.
Without it, release builds are unsigned, which is what F-Droid expects (it signs with its own key).
Store listing text and screenshots for F-Droid live in `fastlane/metadata/android/`.

## Credits
- `.pur` format: ported from [FyorDev/PureRef-format](https://github.com/FyorDev/PureRef-format) (MIT)
- Video loops idea: [lettucegoblin/AnimRef](https://github.com/lettucegoblin/AnimRef) (MIT)
- Icons: [Lucide](https://lucide.dev) (ISC, see `third_party/LUCIDE_LICENSE.txt`); `tools/icons.py` converts them

## License
MIT
