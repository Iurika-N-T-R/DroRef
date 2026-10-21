# DroRef

Free, open-source reference board for Android.

Inspired by [PureRef](https://www.pureref.com). DroRef is an independent app written from scratch and is not affiliated with PureRef or Idyllic Pixel. **On PC, use the original: https://www.pureref.com**

Boards move between the two as `.pur` files: *File → Open .pur*, *Save*, *Save as .pur*.
From PureRef 2, save with *Save as → Legacy PureRef scene* first.

## Using it
- The canvas fills the screen (dot background by default; grid or plain in ☰ → Canvas). Floating buttons: ☰ menu and scene name top-left, ↶ ↷ and 👁 focus mode top-right, ＋ bottom-right. UI inspired by VizRef.
- **＋** adds images and videos. You can also paste them, paste a link, or use *Share → DroRef* from any app.
- Drag empty space to pan, pinch to zoom, double-tap to focus.
- Tap to select. Drag to move; pinch or twist with two fingers to scale and rotate.
- Long-press an item to add it to the selection. Long-press empty space and drag to select a rectangle; release without dragging for the canvas menu.
- The bar at the bottom has what fits the selection: crop, flip, arrange, align, group, GIF/video controls, and *More…*.
- *Always on top* floats the board over your drawing app. Its bar has opacity, transparent-to-touch (for tracing), no-background (only the images float), and 📌 to show it only over one app (Android asks once for usage access).

Everything PureRef has, and what's done here: [FEATURES.md](FEATURES.md)

## Build
Open this folder in Android Studio and press Run, or from a terminal:

    ./gradlew assembleDebug

The APK lands in `app/build/outputs/apk/debug/`.

Test the `.pur` reader and writer:

    ./gradlew testDebugUnitTest -PpurDir=/tmp/pur

That writes `kt_edit.pur` into the folder. Check it with real PureRef: `PureRef -c 'load;/tmp/pur/kt_edit.pur' -c 'exportScene;/tmp/pur/out.png' -c exit`

Translations live in one table, `tools/strings.py`. Edit it and run `python3 tools/strings.py` to regenerate every `strings.xml`.
`tools/ui.py` drives an emulator for quick UI checks (tap by label, screenshot).

## Credits
- `.pur` format: ported from [FyorDev/PureRef-format](https://github.com/FyorDev/PureRef-format) (MIT)
- Video loops idea: [lettucegoblin/AnimRef](https://github.com/lettucegoblin/AnimRef) (MIT)

## License
MIT
