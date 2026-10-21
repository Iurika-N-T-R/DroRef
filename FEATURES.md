# DroRef vs PureRef 2.1

Checklist built from the [PureRef handbook](https://www.pureref.com/handbook/), the [PureRef 2 announcement](https://www.pureref.com/blog/pureref2/) and PureRef's own menu names.
✅ done · 🔜 not yet · ➖ desktop-only (Android alternative in brackets)

## Adding
- ✅ Import images and videos (＋, Ctrl+I) · ✅ paste image (Ctrl+V) · ✅ paste/share a URL → that image, or every image on the page
- ✅ Share → DroRef from gallery/browser (Android's drag & drop) · ✅ everything is embedded in the scene
- 🔜 Manage images (downscale / convert / link vs embed)

## Selecting & editing
- ✅ Tap select · ✅ rectangle select (long-press empty space, drag) · ✅ add/remove from selection (long-press item) · ✅ select all
- ✅ Move / scale / rotate, alone or as a group (one or two fingers) · ✅ snapped rotate (sticks at 45° steps)
- ✅ Duplicate · ✅ delete · ✅ undo / redo (history size in settings)
- ✅ Crop (handles + move) · ✅ reset cropping · 🔜 pan / zoom the image inside the crop
- ✅ Flip horizontal / vertical · ✅ reset transform · ✅ opacity · ✅ toggle grayscale · ✅ bilinear / nearest sampling
- ✅ Lock / unlock · ✅ send to back / bring to front · ✅ replace image · ✅ open source · ✅ rename
- ✅ Copy / cut / paste items (Ctrl+C / X / V), also between scenes · ✅ image comments (badge on the image, shown when selected)
- 🔜 Axis-snapped and neighbor-snapped move · ➖ relink missing images (always embedded)

## Arrange & align
- ✅ Arrange optimal / by name / addition / order / path / randomly (repeat = reversed); a selected group arranges its contents
- ✅ Align left / right / top / bottom (push, padding from settings) · ✅ stack · ✅ distribute horizontal / vertical
- ✅ Normalize height / width / size / area / scale, from first or average · ✅ new images selected and packed

## Groups & hierarchy
- ✅ Group (Ctrl+G) / ungroup (Ctrl+Shift+G) · ✅ group background color · ✅ groups resize to fit, empty groups vanish
- ✅ Locked groups: tapping a child selects the group, double-tap opens it · ✅ drag items into / out of groups
- ✅ Parent (P) / unparent: children move with and draw above their parent
- ✅ Hierarchy (Ctrl+J): tree, tap to focus, long-press to rename / reorder / parent to… / unparent / lock / delete

## Navigation & canvas
- ✅ Pan, pinch zoom, mouse wheel · ✅ double-tap focus (again = back) · ✅ focus canvas · ✅ next / previous
- ✅ Reset camera / zoom · ✅ lock canvas (Ctrl+R) · ✅ grayscale canvas · ✅ background dots (default) / grid with major lines / plain · ✅ snap to grid
- ✅ Full-screen canvas with floating buttons and a pill toolbar · ✅ focus mode 👁: UI hidden, look only (from VizRef)
- ✅ Color picker + image coordinates (RGB / HSV / HEX, tap to copy)
- ✅ Slideshow (Alt+S): interval and order (ordered / shuffle / random) in settings; panning pauses it

## Notes
- ✅ Rich text: bold, italic, underline, strikethrough, size, color, highlight, font, alignment, bullets, numbered lists, indenting
- ✅ Checklists (tap a box on a selected note to tick it) · ✅ links (tap to open) · ✅ auto size · ✅ color presets
- ✅ New note parents to the selected item; auto-parent pasted notes (setting)
- ✅ Auto size or fixed wrap width (narrow / medium / wide) · 🔜 justify

## Drawing
- ✅ Pen, line, rectangle, ellipse, eraser · ✅ color, width, solid / dotted / arrow
- ✅ Drawing becomes an item (parented to the selected image); double-tap a drawing to keep editing it
- 🔜 Shift-style constraints (straight angles, squares, circles)

## GIF & video
- ✅ GIF: play / pause, frame step with frame number, scrub, speed, frame → image, several GIFs at once
- ✅ Video loops (from AnimRef): muted, A–B loop, speed, frame → image; crop / rotate / fade like images (Android 10+, older shows a still)
- ➖ YouTube embeds

## Save, open, export
- ✅ Open .pur (Ctrl+O / Ctrl+L) · ✅ Save (Ctrl+S) writes back to the opened .pur · ✅ Save as .pur (Ctrl+Shift+S)
- ✅ Legacy format, which PureRef 2.1.3 opens (checked with PureRef's own `exportScene`). Drawings are saved as images, videos as a still, groups flattened.
- ✅ From PureRef 2: *Save as → Legacy PureRef scene*, then open it here · 🔜 PureRef 2 native format (not public)
- ✅ My scenes: new / open / rename / duplicate / delete, always autosaved, last one reopens
- ✅ Export scene or selection as PNG · ✅ export images as files: originals when uncropped, cropped ones as PNG or JPG

## Window modes
- ✅ Always on top: a floating window over any app, moved / resized from its bar, its own camera
- ✅ Overlay mode (background solid / glass / none) · ✅ app opacity (100 / 80 / 60 / 40 %) · ✅ transparent to touch (tracing)
- ✅ Always on top of… (📌): pick an app, the floating board shows only while that app is in front (uses usage access)
- ➖ Always on bottom, lock window, title bar, tray icon, file associations, global shortcuts, SpaceMouse

## Settings, themes, languages
- ✅ Settings: theme, language, undo size, pack new (and by what), bring to front, double-tap focus, image borders,
  alignment padding, default sampling, slideshow interval / order, drag into groups, auto-parent notes, image quality
- ✅ Themes: dark / light / glass · ✅ English, Français, Español, 日本語, 한국어, 简体中文, 繁體中文 (PureRef's set)
- ✅ Command palette (Ctrl+Shift+P): search and run every command, bar action and setting
- ✅ Export / import all settings as a file
- ✅ Keyboard shortcuts for tablets / Chromebooks, following PureRef's where they make sense
- 🔜 Custom theme colors, rebinding shortcuts
