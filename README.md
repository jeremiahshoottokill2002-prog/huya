# CloudyCam 🎥

A tiny, fully offline Android camera app with real-time GPU lens effects
(Fisheye / Concave), zoom presets, front/back camera switch, and video
recording. No bulky libraries — just CameraX and raw OpenGL ES shaders.
Built APK is roughly 5–8 MB.

## How to get your APK (no Android Studio needed)

1. Go to **github.com** in your browser and create a new repository
   (e.g. `CloudyCam`). Make it Public (Actions are free and unlimited
   on public repos).

2. Click **"uploading an existing file"** (or Add file → Upload files)
   and upload ALL the files in this folder, **keeping the folder
   structure exactly the same**. The easiest way: drag the whole
   unzipped folder contents into the upload box.

   ⚠️ Important: the `.github/workflows/build.yml` file must end up at
   exactly that path. If the GitHub web uploader skips the hidden
   `.github` folder, create it manually: Add file → Create new file →
   type `.github/workflows/build.yml` as the name → paste the contents.

3. Commit the upload. GitHub Actions starts building automatically.

4. Click the **Actions** tab → click the running workflow → wait for
   the green check (about 3–5 minutes).

5. Scroll down to **Artifacts** → download **CloudyCam-APK** → unzip it
   → transfer `app-debug.apk` to your phone and install it.
   (You may need to allow "Install from unknown sources.")

## Using the app

- **✨ button** — cycles 8 modes: Normal → Fisheye → Concave → Wide →
  Mirror → B&W → Crisp → Smooth
  - **Crisp** sharpens the image — great for talking/performance videos
  - **Smooth** softens grain and noise — great for older/weaker cameras
- **🔍 button** — cycles zoom: 1.0x → 1.5x → 2.0x → 3.0x
- **🎞 button** — recording quality: HD → FHD → SD (lower = smaller
  files and easier on old cameras; the app auto-falls back if your
  camera doesn't support a level)
- **🔄 button** — switch front/back camera
- **● REC** — start/stop recording. Videos save to `Movies/CloudyCam`
  in your gallery.

Everything runs 100% on-device. No internet, no accounts, no costs.

## Current limitation (honest note)

The shader effect is applied to the **live preview** in real time, but
recordings capture the **clean camera feed** (no effect baked in).
Baking effects into the recorded file requires an EGL → MediaCodec
render pipeline — that's the natural Phase 2. The code is structured so
this can be added without rewriting anything.

## Adding your own effects (Phase 2 ideas)

Open `CameraRenderer.kt`, find `FRAGMENT_SHADER`, and add a new
`uMode` branch. Then add the effect name to `effectNames` in
`MainActivity.kt`. That's it — two files, two small edits.
