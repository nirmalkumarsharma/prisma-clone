# Prisma Clone

Prisma-style (c. 2016) artistic filters for photos. Every image is fully re-textured into one bold visual identity, while its composition and main shapes are kept.

It runs on four platforms, all sharing one filter design:

| Platform | Where | Stack |
|---|---|---|
| Web | `index.html`, `src/` | Plain JS, Web Worker, no build step |
| Android | `android/` | Kotlin, Jetpack Compose, minSdk 29 |
| iOS | `ios/` | Swift, SwiftUI, iOS 16+ |
| CLI | `cli/` | Node, no dependencies |

![All six filters applied to the built-in sample scene](docs/preview.png)

*Top row: Udnie, Wave, Mosaic. Bottom row: Candy, Gothic, Tears.*

## Filters

| Filter | Look | How it's made |
|---|---|---|
| **Udnie** | Swirling blue and gold modernist impasto | A noise flow field warps the image. Line-integral convolution draws brushstrokes along the flow, and embossing raises the paint ridges. Tones map to a cobalt→gold gradient. |
| **Wave** | Japanese woodblock print | Five flat indigo ink levels with vermilion/ochre accents, bold black key lines from edge detection, wood grain, and fibrous washi paper. |
| **Mosaic** | Geometric tile tessellation | Jittered Voronoi tiles in saturated averaged colors, with domed bevels, per-tile jitter, and dark grout. |
| **Candy** | Neon pop-art comic | Posterized neon hues, thick violet outlines, and halftone dots in the shadows. |
| **Gothic** | Cathedral stained glass | Voronoi cells are split along tonal contours so the glass follows the subject. Jewel-tone glass, heavy lead came, inner glow, light streaks, and a stone vignette. |
| **Tears** | Ink sketch with watercolor | Pen contours from a difference of Gaussians, three layers of cross-hatching by tone, and blotchy watercolor washes on aged parchment. |

These are hand-built image-processing pipelines, not a neural network. They run on the device, they're deterministic for a given seed, and they need no model weights or GPU.

## Web app

ES modules need to be served over HTTP:

```bash
npm start
```

Then open http://localhost:8080. You can upload, drag and drop, or paste an image, or start from the built-in sample. Pick a filter from the live thumbnail strip, set the intensity, hold **compare** to see the original, **re-roll** the strokes and tiles, and download a PNG. Filtering runs in a Web Worker so the page stays responsive, and a newer request cancels one still running. Images are scaled down to 1200 px on the long side. Browsers without module workers fall back to filtering on the main thread.

### Prompt builder

Type a subject to get a text-to-image prompt in the selected style, following this template:

> A dramatic [Filter]-style artistic rendering of [Subject]. Rendered in [Medium] with [Texture]. Features a color palette dominated by [Colors], high-contrast chiaroscuro lighting, and bold visual contours. Textured background reminiscent of [Background], with vibrant, expressive art stylization applied uniformly across all elements.

## Android

The app is in `android/`. The filter engine is the pure-Kotlin `prismakit` module, and the Compose UI is in `app`.

- Pick a photo with the system photo picker (no permissions needed), or share an image into the app from any other app.
- Live filter thumbnails, an intensity slider, press-and-hold to compare, and re-roll.
- **Save** writes a PNG to *Pictures/Prisma Clone*. **Share** opens the system share sheet.
- Prompt builder with copy to clipboard.

Open `android/` in Android Studio, or build from the command line:

```bash
cd android && ./gradlew :app:assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/`. Tests:

```bash
cd android && ./gradlew :prismakit:test :app:verifyPaparazziDebug
```

`:app:verifyPaparazziDebug` renders the real Compose UI on the JVM and compares it with the snapshots in `app/src/test/snapshots/`. After an intended UI change, re-record them with `:app:recordPaparazziDebug`.

## iOS

The app is in `ios/`. The filter engine is the `PrismaKit` Swift package, and the SwiftUI app is in `PrismaApp`. It has the same features as Android: PhotosPicker, live thumbnails, intensity, press-and-hold compare, re-roll, **Save** to Photos (asks for add-only access), **Share**, and the prompt builder.

The Xcode project is generated from `ios/project.yml` with [XcodeGen](https://github.com/yonaskolb/XcodeGen):

```bash
brew install xcodegen
```

```bash
cd ios && xcodegen generate && open PrismaClone.xcodeproj
```

To run on a device, choose your team under *Signing & Capabilities*. Engine tests run on macOS or Linux:

```bash
swift test --package-path ios/PrismaKit
```

## How the platforms stay in sync

`src/filters.js` is the reference implementation. `android/prismakit` and `ios/PrismaKit` are line-for-line ports that copy its hash, noise and rounding rules exactly. `npm run fixtures` writes the JS engine's output for a fixed scene to `fixtures/`, and the Kotlin and Swift test suites check their own output against those files. The sample scene must match byte for byte. Each filter must match within a tiny tolerance, because a last-digit floating-point difference can flip an occasional edge pixel. If you change a filter, change it in all three places, then run `npm run fixtures` and every platform's tests. CI does all of this on each push.

## CLI

```bash
node cli/prisma.js list
```

```bash
node cli/prisma.js gothic photo.png out.png --intensity 0.9 --seed 3
```

```bash
node cli/prisma.js all --sample
```

```bash
node cli/prisma.js prompt wave "a lighthouse on a cliff during a storm"
```

The CLI reads 8-bit PNGs. The web app reads any format the browser supports.

## Library

```js
import { applyFilter, buildPrompt, FILTERS } from './src/filters.js';
const out = applyFilter('udnie', imageData, { intensity: 1, seed: 1 }); // { width, height, data }
```

`src/filters.js` has no DOM dependency, so it works in browsers, workers and Node.

## Tests

```bash
npm test
```
