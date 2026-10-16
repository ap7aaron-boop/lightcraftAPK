# LightCraft for Android (first milestone)

A small native Android shell (`MainActivity`) that runs the LightCraft **web build**
(`apps/lightcraft-web`, the same egui UI compiled to WebAssembly) inside a WebView, served from the
app's own assets at `https://appassets.androidplatform.net/` (a secure context, so OPFS, workers and
WebAssembly work).

What the shell adds on top of the web build:

- **Import:** *File > Import Photos...* opens Android's file/photo picker (`onShowFileChooser`).
- **Export:** the web build "downloads" files through a blob link; the shell catches that and saves the
  file with MediaStore: images to `Pictures/LightCraft`, everything else to `Downloads/LightCraft`.
  No storage permission is needed.
- **Offline:** nothing is fetched from the network; the app has no INTERNET permission.

## Build

The APK is built by GitHub Actions (`.github/workflows/android.yml`): it builds the web bundle with
`cargo xtask web`, copies it into `app/src/main/assets/www/`, and runs `gradle assembleDebug`. The
debug-signed APK is attached to the run as an artifact (Actions > Android APK > latest run).

Locally (needs the Rust toolchain, `wasm-bindgen-cli`, JDK 17, Android SDK 34, Gradle 8.10):

```sh
cargo xtask web
mkdir -p android/app/src/main/assets/www && cp -r target/web-site/. android/app/src/main/assets/www/
cd android && gradle assembleDebug
```

## Limits (same as the web build, see docs/web.md)

No print layouts, contact-sheet PDF, control channel/MCP or JPEG XL export; the interface is the desktop
layout (no touch-specific layout yet); the library lives in the WebView's storage, so use
*File > Back Up Library...* now and then. A native Rust build for Android is a later milestone.

## License

This directory is part of LightCraft and under the same terms (MIT OR Apache-2.0). It contains no
ArtCraft logos or other brand assets.
