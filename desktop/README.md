# Gumdrop Gala for Windows

A desktop build of the same game: it reuses the engine and all game code from `:app`, with a thin Java2D layer in
`src/main/kotlin/android` standing in for the Android APIs (canvas, bitmaps, view, audio, preferences).

* Run from source: `gradle :desktop:run`
* Portable app: GitHub Actions → **Build Gumdrop Gala for Windows** → artifact `GumdropGala-windows-portable`
  (unzip, double-click `GumdropGala.exe`). An `.msi` installer is built too when WiX is available.
* Saves live in `%APPDATA%\GumdropGala`.
* Controls: mouse = touch (click, drag to swap), wheel scrolls the level map, **Esc** = back, **F11** = full screen.
* `GumdropGala --screenshots <dir>` renders the main screens headlessly to PNG (used as a CI smoke test).

Differences from Android: no vibration (the settings rows are hidden), backdrops are `.jpg` (Java has no WebP decoder),
and the window is letter-boxed to a portrait play area.
