// Prisma-style artistic filters: Swift port of src/filters.js.
// Pure Swift (Foundation only) so it is unit-tested on any platform against the JS reference.
import Foundation

/// An opaque image as tightly packed 8-bit RGBA bytes (row-major, no padding).
public struct PixelImage: Sendable, Equatable {
    public let width: Int
    public let height: Int
    public var rgba: [UInt8]

    public init(width: Int, height: Int, rgba: [UInt8]) {
        precondition(width > 0 && height > 0 && rgba.count == width * height * 4, "rgba must hold width*height*4 bytes")
        self.width = width
        self.height = height
        self.rgba = rgba
    }
}

/// Text-to-image prompt ingredients for a filter.
public struct PromptSpec: Sendable {
    public let medium: String
    public let texture: String
    public let palette: String
    public let background: String
}

public struct StyleFilter: Sendable, Identifiable {
    public let id: String
    public let name: String
    public let tagline: String
    public let prompt: PromptSpec
    let render: @Sendable (PixelImage, Int) -> PixelImage
}

public enum PrismaError: Error, Equatable {
    case unknownFilter(String)
}

public enum Prisma {
    public static let filters: [StyleFilter] = [
        StyleFilter(
            id: "udnie", name: "Udnie", tagline: "Swirling blue & gold modernist impasto",
            prompt: PromptSpec(
                medium: "the style of an expressive modernist oil painting",
                texture: "heavy impasto brushstrokes and dynamic swirling curves",
                palette: "deep cobalt and ultramarine blues with burnished gold and amber",
                background: "thickly painted canvas"),
            render: { udnie($0, $1) }),
        StyleFilter(
            id: "wave", name: "Wave", tagline: "Japanese woodblock print in indigo ink",
            prompt: PromptSpec(
                medium: "the style of a traditional Japanese ukiyo-e woodblock print",
                texture: "bold black woodblock ink lines, carved wood grain and flat color fields",
                palette: "rich indigo and Prussian blue with cream paper and touches of vermilion",
                background: "fibrous washi parchment"),
            render: { wave($0, $1) }),
        StyleFilter(
            id: "mosaic", name: "Mosaic", tagline: "Fractured tile tessellation with grout lines",
            prompt: PromptSpec(
                medium: "the style of an abstract geometric tile mosaic",
                texture: "fractured tessellated color tiles separated by distinct dark grout borders",
                palette: "saturated terracotta, cobalt, ochre and emerald",
                background: "rough stone and grout"),
            render: { mosaic($0, $1) }),
        StyleFilter(
            id: "candy", name: "Candy", tagline: "Neon pop art with comic halftones",
            prompt: PromptSpec(
                medium: "the style of a neon pop-art comic illustration",
                texture: "fluid graphic outlines, flat cel shading and halftone comic dots",
                palette: "hot pink, electric cyan and lemon yellow with deep violet",
                background: "glossy printed comic paper"),
            render: { candy($0, $1) }),
        StyleFilter(
            id: "gothic", name: "Gothic", tagline: "Cathedral stained glass with heavy lead",
            prompt: PromptSpec(
                medium: "the style of a gothic cathedral stained glass window",
                texture: "heavy dark leaded glass lines dividing translucent glowing segments with glass reflections",
                palette: "jewel-toned ruby, sapphire, emerald and amber",
                background: "backlit stained glass set in dark stone tracery"),
            render: { stainedGlass($0, $1) }),
        StyleFilter(
            id: "tears", name: "Tears", tagline: "Ink cross-hatching & watercolor on parchment",
            prompt: PromptSpec(
                medium: "the style of a high-contrast pen-and-ink drawing with watercolor washes",
                texture: "dense ink cross-hatching, confident contour lines and blooming watercolor washes",
                palette: "sepia and iron-gall black ink with muted watercolor accents",
                background: "aged textured parchment"),
            render: { sketch($0, $1) }),
    ]

    public static func filter(_ id: String) throws -> StyleFilter {
        guard let f = filters.first(where: { $0.id == id }) else { throw PrismaError.unknownFilter(id) }
        return f
    }

    /// Apply a filter. `intensity` (0...1) blends with the original.
    public static func apply(_ id: String, to image: PixelImage, intensity: Double = 1, seed: Int = 1) throws -> PixelImage {
        var styled = try filter(id).render(image, seed)
        if intensity >= 1 { return styled }
        let t = clamp01(intensity)
        for i in stride(from: 0, to: styled.rgba.count, by: 4) {
            for c in 0..<3 {
                let v = mix(Double(image.rgba[i + c]), Double(styled.rgba[i + c]), t)
                styled.rgba[i + c] = UInt8(max(0, min(255, v.rounded(.toNearestOrEven)))) // like Uint8ClampedArray
            }
        }
        return styled
    }

    /// Build a text-to-image prompt following the Prisma-style rendering template.
    public static func buildPrompt(_ id: String, subject: String) throws -> String {
        let f = try filter(id)
        var subj = subject.trimmingCharacters(in: .whitespacesAndNewlines)
        while let last = subj.last, last == "." || last.isWhitespace { subj.removeLast() }
        if subj.isEmpty { subj = "the scene" }
        let p = f.prompt
        return "A dramatic \(f.name)-style artistic rendering of \(subj). "
            + "Rendered in \(p.medium) with \(p.texture). "
            + "Features a color palette dominated by \(p.palette), high-contrast chiaroscuro lighting, and bold visual contours. "
            + "Textured background reminiscent of \(p.background), with vibrant, expressive art stylization applied uniformly across all elements."
    }
}

// MARK: - Math / noise helpers

@inline(__always) func clamp01(_ v: Double) -> Double { v < 0 ? 0 : (v > 1 ? 1 : v) }
@inline(__always) func mix(_ a: Double, _ b: Double, _ t: Double) -> Double { a + (b - a) * t }
@inline(__always) func smoothstep(_ e0: Double, _ e1: Double, _ x: Double) -> Double {
    let t = clamp01((x - e0) / (e1 - e0))
    return t * t * (3 - 2 * t)
}
/// JavaScript's Math.round: halves round toward +∞.
@inline(__always) func jsRound(_ v: Double) -> Double { (v + 0.5).rounded(.down) }
@inline(__always) func jsMod(_ a: Double, _ b: Double) -> Double { a.truncatingRemainder(dividingBy: b) }

@inline(__always) func hash2(_ x: Int, _ y: Int, _ seed: Int) -> Double {
    let x32 = Int32(truncatingIfNeeded: x), y32 = Int32(truncatingIfNeeded: y), s32 = Int32(truncatingIfNeeded: seed)
    var h = x32 &* 374_761_393 &+ y32 &* 668_265_263 &+ s32 &* 1_442_695_041
    h = (h ^ Int32(bitPattern: UInt32(bitPattern: h) >> 13)) &* 1_274_126_177
    h ^= Int32(bitPattern: UInt32(bitPattern: h) >> 16)
    return Double(UInt32(bitPattern: h)) / 4_294_967_296.0
}

@inline(__always) func valueNoise(_ x: Double, _ y: Double, _ seed: Int) -> Double {
    let xi = x.rounded(.down), yi = y.rounded(.down)
    let xf = x - xi, yf = y - yi
    let u = xf * xf * (3 - 2 * xf), v = yf * yf * (3 - 2 * yf)
    let ix = Int(xi), iy = Int(yi)
    let a = hash2(ix, iy, seed), b = hash2(ix + 1, iy, seed)
    let c = hash2(ix, iy + 1, seed), d = hash2(ix + 1, iy + 1, seed)
    return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v
}

func fbm(_ x: Double, _ y: Double, _ seed: Int, _ octaves: Int = 4) -> Double {
    var sum = 0.0, amp = 0.5, f = 1.0, norm = 0.0
    for i in 0..<octaves {
        sum += amp * valueNoise(x * f, y * f, seed + i * 101)
        norm += amp
        amp *= 0.5
        f *= 2
    }
    return sum / norm
}

// MARK: - Color helpers (channels 0...1)

typealias RGB = (Double, Double, Double)

func rgbToHsv(_ r: Double, _ g: Double, _ b: Double) -> RGB {
    let mx = max(r, g, b), mn = min(r, g, b), d = mx - mn
    var h = 0.0
    if d > 1e-6 {
        if mx == r { h = jsMod((g - b) / d, 6) } else if mx == g { h = (b - r) / d + 2 } else { h = (r - g) / d + 4 }
        h *= 60
        if h < 0 { h += 360 }
    }
    return (h, mx == 0 ? 0 : d / mx, mx)
}

func hsvToRgb(_ h: Double, _ s: Double, _ v: Double) -> RGB {
    let c = v * s
    let hp = jsMod(jsMod(h, 360) + 360, 360) / 60
    let x = c * (1 - abs(jsMod(hp, 2) - 1))
    let rgb: RGB
    if hp < 1 { rgb = (c, x, 0) } else if hp < 2 { rgb = (x, c, 0) } else if hp < 3 { rgb = (0, c, x) }
    else if hp < 4 { rgb = (0, x, c) } else if hp < 5 { rgb = (x, 0, c) } else { rgb = (c, 0, x) }
    let m = v - c
    return (rgb.0 + m, rgb.1 + m, rgb.2 + m)
}

func hex(_ s: String) -> [Double] {
    let chars = Array(s.dropFirst())
    return (0..<3).map { Double(Int(String(chars[$0 * 2...$0 * 2 + 1]), radix: 16)!) / 255 }
}

func gradientAt(_ stops: [(Double, [Double])], _ tIn: Double) -> [Double] {
    let t = clamp01(tIn)
    for i in 1..<stops.count where t <= stops[i].0 {
        let (t0, c0) = stops[i - 1], (t1, c1) = stops[i]
        let span = t1 - t0
        let k = (t - t0) / (span == 0 ? 1 : span)
        return [mix(c0[0], c1[0], k), mix(c0[1], c1[1], k), mix(c0[2], c1[2], k)]
    }
    return stops[stops.count - 1].1
}

func hueDist(_ a: Double, _ b: Double) -> Double {
    let d = jsMod(abs(a - b), 360)
    return d > 180 ? 360 - d : d
}

// MARK: - Image plane helpers

func toRGB(_ img: PixelImage) -> [Double] {
    let n = img.width * img.height
    var out = [Double](repeating: 0, count: n * 3)
    for i in 0..<n {
        out[i * 3] = Double(img.rgba[i * 4]) / 255
        out[i * 3 + 1] = Double(img.rgba[i * 4 + 1]) / 255
        out[i * 3 + 2] = Double(img.rgba[i * 4 + 2]) / 255
    }
    return out
}

func toImage(_ rgb: [Double], _ w: Int, _ h: Int) -> PixelImage {
    let n = w * h
    var data = [UInt8](repeating: 255, count: n * 4)
    for i in 0..<n {
        data[i * 4] = UInt8(jsRound(clamp01(rgb[i * 3]) * 255))
        data[i * 4 + 1] = UInt8(jsRound(clamp01(rgb[i * 3 + 1]) * 255))
        data[i * 4 + 2] = UInt8(jsRound(clamp01(rgb[i * 3 + 2]) * 255))
    }
    return PixelImage(width: w, height: h, rgba: data)
}

func luminance(_ rgb: [Double], _ n: Int) -> [Double] {
    var out = [Double](repeating: 0, count: n)
    for i in 0..<n { out[i] = 0.2126 * rgb[i * 3] + 0.7152 * rgb[i * 3 + 1] + 0.0722 * rgb[i * 3 + 2] }
    return out
}

/// Separable box blur, applied `passes` times, on an interleaved plane.
func blur(_ src: [Double], _ w: Int, _ h: Int, _ radius: Double, channels: Int = 3, passes: Int = 2) -> [Double] {
    let r = max(0, Int(jsRound(radius)))
    if r == 0 { return src }
    var a = src
    var b = [Double](repeating: 0, count: src.count)
    let norm = 1.0 / Double(2 * r + 1)
    func pass(_ from: UnsafeMutableBufferPointer<Double>, _ to: UnsafeMutableBufferPointer<Double>, horizontal: Bool) {
        let len = horizontal ? w : h
        let lines = horizontal ? h : w
        for line in 0..<lines {
            for c in 0..<channels {
                @inline(__always) func idx(_ k: Int) -> Int {
                    let kk = k < 0 ? 0 : (k >= len ? len - 1 : k)
                    return (horizontal ? line * w + kk : kk * w + line) * channels + c
                }
                var sum = 0.0
                for k in -r...r { sum += from[idx(k)] }
                for k in 0..<len {
                    to[idx(k)] = sum * norm
                    sum += from[idx(k + r + 1)] - from[idx(k - r)]
                }
            }
        }
    }
    a.withUnsafeMutableBufferPointer { pa in
        b.withUnsafeMutableBufferPointer { pb in
            for _ in 0..<passes {
                pass(pa, pb, horizontal: true)
                pass(pb, pa, horizontal: false)
            }
        }
    }
    return a
}

func sobel(_ lum: [Double], _ w: Int, _ h: Int) -> [Double] {
    var out = [Double](repeating: 0, count: w * h)
    lum.withUnsafeBufferPointer { l in
        @inline(__always) func at(_ x: Int, _ y: Int) -> Double {
            l[(y < 0 ? 0 : (y >= h ? h - 1 : y)) * w + (x < 0 ? 0 : (x >= w ? w - 1 : x))]
        }
        for y in 0..<h {
            for x in 0..<w {
                let gx = -at(x - 1, y - 1) - 2 * at(x - 1, y) - at(x - 1, y + 1)
                    + at(x + 1, y - 1) + 2 * at(x + 1, y) + at(x + 1, y + 1)
                let gy = -at(x - 1, y - 1) - 2 * at(x, y - 1) - at(x + 1, y - 1)
                    + at(x - 1, y + 1) + 2 * at(x, y + 1) + at(x + 1, y + 1)
                out[y * w + x] = (gx * gx + gy * gy).squareRoot()
            }
        }
    }
    return out
}

/// Grayscale max filter (thickens line masks).
func dilate(_ mask: [Double], _ w: Int, _ h: Int, _ radius: Double) -> [Double] {
    let r = Int(jsRound(radius))
    if r <= 0 { return mask }
    var tmp = [Double](repeating: 0, count: w * h)
    var out = [Double](repeating: 0, count: w * h)
    for y in 0..<h {
        for x in 0..<w {
            var m = 0.0
            for k in -r...r {
                let v = mask[y * w + min(w - 1, max(0, x + k))]
                if v > m { m = v }
            }
            tmp[y * w + x] = m
        }
    }
    for y in 0..<h {
        for x in 0..<w {
            var m = 0.0
            for k in -r...r {
                let v = tmp[min(h - 1, max(0, y + k)) * w + x]
                if v > m { m = v }
            }
            out[y * w + x] = m
        }
    }
    return out
}

struct Voronoi {
    var index: [Int]
    var d1: [Double]
    var border: [Double]
    var sx: [Double]
    var sy: [Double]
    var count: Int
}

/// Jittered-grid Voronoi: nearest seed, distance to it, and distance to the cell border.
func voronoi(_ w: Int, _ h: Int, _ cell: Int, _ seed: Int) -> Voronoi {
    let cols = (w + cell - 1) / cell + 1
    let rows = (h + cell - 1) / cell + 1
    var sx = [Double](repeating: 0, count: cols * rows)
    var sy = [Double](repeating: 0, count: cols * rows)
    for gy in 0..<rows {
        for gx in 0..<cols {
            let i = gy * cols + gx
            sx[i] = (Double(gx) + 0.1 + 0.8 * hash2(gx, gy, seed)) * Double(cell)
            sy[i] = (Double(gy) + 0.1 + 0.8 * hash2(gx, gy, seed + 7)) * Double(cell)
        }
    }
    var index = [Int](repeating: 0, count: w * h)
    var d1 = [Double](repeating: 0, count: w * h)
    var border = [Double](repeating: 0, count: w * h)
    for y in 0..<h {
        let gy0 = y / cell
        for x in 0..<w {
            let gx0 = x / cell
            var best = Double.infinity, second = Double.infinity, bi = 0
            for oy in -1...1 {
                let gy = gy0 + oy
                if gy < 0 || gy >= rows { continue }
                for ox in -1...1 {
                    let gx = gx0 + ox
                    if gx < 0 || gx >= cols { continue }
                    let i = gy * cols + gx
                    let dx = sx[i] - Double(x), dy = sy[i] - Double(y)
                    let d = dx * dx + dy * dy
                    if d < best { second = best; best = d; bi = i } else if d < second { second = d }
                }
            }
            let p = y * w + x
            index[p] = bi
            let a = best.squareRoot()
            d1[p] = a
            border[p] = (second.squareRoot() - a) * 0.5
        }
    }
    return Voronoi(index: index, d1: d1, border: border, sx: sx, sy: sy, count: cols * rows)
}

func cellAverages(_ rgb: [Double], _ vor: Voronoi, _ n: Int) -> [Double] {
    var sums = [Double](repeating: 0, count: vor.count * 4)
    for p in 0..<n {
        let c = vor.index[p] * 4
        sums[c] += rgb[p * 3]; sums[c + 1] += rgb[p * 3 + 1]; sums[c + 2] += rgb[p * 3 + 2]; sums[c + 3] += 1
    }
    var avg = [Double](repeating: 0, count: vor.count * 3)
    for i in 0..<vor.count {
        let k = sums[i * 4 + 3] == 0 ? 1 : sums[i * 4 + 3]
        avg[i * 3] = sums[i * 4] / k; avg[i * 3 + 1] = sums[i * 4 + 1] / k; avg[i * 3 + 2] = sums[i * 4 + 2] / k
    }
    return avg
}

/// Feature size relative to a ~600px reference so filters look the same at any resolution.
@inline(__always) func scaleOf(_ w: Int, _ h: Int) -> Double { Double(max(w, h)) / 600 }

// MARK: - Filters

let udnieStops: [(Double, [Double])] = [
    (0.0, hex("#0a1030")), (0.22, hex("#16347a")), (0.42, hex("#1f63b0")),
    (0.58, hex("#3c8ca0")), (0.72, hex("#d29a2e")), (0.88, hex("#f5c85a")), (1.0, hex("#fff2c8")),
]

func udnie(_ src: PixelImage, _ seed: Int) -> PixelImage {
    let w = src.width, h = src.height, s = scaleOf(w, h)
    let rgb = toRGB(src)

    var ang = [Double](repeating: 0, count: w * h)
    let f = 1 / (110 * s)
    for y in 0..<h { for x in 0..<w { ang[y * w + x] = fbm(Double(x) * f, Double(y) * f, seed, 3) * Double.pi * 4 } }

    let amp = 7 * s
    var warped = [Double](repeating: 0, count: w * h * 3)
    for y in 0..<h {
        for x in 0..<w {
            let a = ang[y * w + x]
            let xx = min(w - 1, max(0, Int(jsRound(Double(x) + amp * cos(a)))))
            let yy = min(h - 1, max(0, Int(jsRound(Double(y) + amp * sin(a)))))
            let q = (yy * w + xx) * 3, p = (y * w + x) * 3
            warped[p] = rgb[q]; warped[p + 1] = rgb[q + 1]; warped[p + 2] = rgb[q + 2]
        }
    }
    let soft = blur(warped, w, h, 2 * s)
    let lum = luminance(soft, w * h)

    var noise = [Double](repeating: 0, count: w * h)
    let cellN = max(1, Int(jsRound(1.5 * s)))
    for y in 0..<h { for x in 0..<w { noise[y * w + x] = hash2(x / cellN, y / cellN, seed + 3) } }
    let len = max(4, Int(jsRound(9 * s)))
    var lic = [Double](repeating: 0, count: w * h)
    for y in 0..<h {
        for x in 0..<w {
            var sum = 0.0, cnt = 0
            for dir in [1.0, -1.0] {
                var px = Double(x), py = Double(y)
                for _ in 0..<len {
                    // Int() truncates toward zero exactly like JS `px | 0`.
                    let ix = Int(px), iy = Int(py)
                    if ix < 0 || iy < 0 || ix >= w || iy >= h { break }
                    sum += noise[iy * w + ix]; cnt += 1
                    let a = ang[iy * w + ix]
                    px += dir * cos(a); py += dir * sin(a)
                }
            }
            lic[y * w + x] = sum / Double(cnt == 0 ? 1 : cnt)
        }
    }

    let edge = sobel(lum, w, h)
    var out = [Double](repeating: 0, count: w * h * 3)
    for y in 0..<h {
        for x in 0..<w {
            let p = y * w + x
            let l = clamp01((lum[p] - 0.5) * 1.45 + 0.5)
            let pal = gradientAt(udnieStops, l)
            let hsv = rgbToHsv(soft[p * 3], soft[p * 3 + 1], soft[p * 3 + 2])
            let own = hsvToRgb(hsv.0, min(1, hsv.1 * 1.6), hsv.2)
            let ownArr = [own.0, own.1, own.2]
            let xa = max(0, x - 1), ya = max(0, y - 1), xb = min(w - 1, x + 1), yb = min(h - 1, y + 1)
            let ridge = (lic[yb * w + xb] - lic[ya * w + xa]) * 2.2
            let stroke = 0.78 + 0.45 * lic[p] - ridge
            let ink = 1 - smoothstep(0.25, 0.9, edge[p]) * 0.65
            for c in 0..<3 { out[p * 3 + c] = mix(pal[c], ownArr[c], 0.28) * stroke * ink }
        }
    }
    return toImage(out, w, h)
}

let waveInks = [hex("#12142a"), hex("#1c3066"), hex("#345c96"), hex("#8cacc4"), hex("#ece2c8")]

func wave(_ src: PixelImage, _ seed: Int) -> PixelImage {
    let w = src.width, h = src.height, s = scaleOf(w, h)
    let soft = blur(toRGB(src), w, h, 1.5 * s)
    let lum = luminance(soft, w * h)
    let edge = sobel(blur(lum, w, h, 1 * s, channels: 1), w, h)
    let lines = edge.map { smoothstep(0.18, 0.32, $0) }
    let thick = dilate(lines, w, h, 0.8 * s)
    let vermilion = hex("#b4523a"), ochre = hex("#d4aa62")

    var out = [Double](repeating: 0, count: w * h * 3)
    for y in 0..<h {
        for x in 0..<w {
            let p = y * w + x
            let l = clamp01((lum[p] - 0.5) * 1.3 + 0.5)
            let level = l < 0.2 ? 0 : (l < 0.4 ? 1 : (l < 0.6 ? 2 : (l < 0.8 ? 3 : 4)))
            var col = waveInks[level]
            let hsv = rgbToHsv(soft[p * 3], soft[p * 3 + 1], soft[p * 3 + 2])
            if hsv.1 > 0.35 && hsv.2 > 0.3 && (hsv.0 < 55 || hsv.0 > 330) { col = level <= 2 ? vermilion : ochre }
            let fx = Double(x), fy = Double(y)
            let grain = 1 + 0.045 * sin(fx * 0.09 / s + fbm(fx / (40 * s), fy / (8 * s), seed, 2) * 14)
            let paper = 0.9 + 0.1 * fbm(fx / (2 * s), fy / (30 * s), seed + 5, 3)
            let speck = hash2(x, y, seed + 9) > 0.995 ? 0.85 : 1
            let k = grain * paper * speck
            let t = thick[p]
            for c in 0..<3 { out[p * 3 + c] = mix(col[c] * k, waveInks[0][c], t) }
        }
    }
    return toImage(out, w, h)
}

func mosaic(_ src: PixelImage, _ seed: Int) -> PixelImage {
    let w = src.width, h = src.height, s = scaleOf(w, h), n = w * h
    let rgb = toRGB(src)
    let cell = max(5, Int(jsRound(13 * s)))
    let vor = voronoi(w, h, cell, seed)
    let avg = cellAverages(rgb, vor, n)
    let grout = 1.1 * s + 0.6
    let groutCol = hex("#2a2622")
    let fc = Double(cell)

    var out = [Double](repeating: 0, count: n * 3)
    for y in 0..<h {
        for x in 0..<w {
            let p = y * w + x
            let ci = vor.index[p]
            let hsv = rgbToHsv(avg[ci * 3], avg[ci * 3 + 1], avg[ci * 3 + 2])
            let ss = min(1, hsv.1 * 1.45 + 0.05)
            let vv = clamp01((hsv.2 - 0.5) * 1.25 + 0.52)
            let jitter = 0.92 + 0.16 * hash2(ci, 0, seed + 11)
            let tile = hsvToRgb(hsv.0 + (hash2(ci, 1, seed) - 0.5) * 10, ss, vv)
            let tileArr = [tile.0, tile.1, tile.2]
            let dx = (Double(x) - vor.sx[ci]) / fc, dy = (Double(y) - vor.sy[ci]) / fc
            let bevel = 1 - 0.28 * (dx + dy) - 0.15 * smoothstep(0.4, 0.9, vor.d1[p] / fc)
            let grit = 0.95 + 0.1 * hash2(x, y, seed + 13)
            let g = 1 - smoothstep(grout * 0.6, grout + 0.8, vor.border[p])
            for c in 0..<3 { out[p * 3 + c] = mix(tileArr[c] * jitter * bevel * grit, groutCol[c] * grit, g) }
        }
    }
    return toImage(out, w, h)
}

func candy(_ src: PixelImage, _ seed: Int) -> PixelImage {
    let w = src.width, h = src.height, s = scaleOf(w, h)
    let soft = blur(toRGB(src), w, h, 2 * s)
    let lum = luminance(soft, w * h)
    let edge = sobel(lum, w, h)
    let lines = edge.map { smoothstep(0.12, 0.24, $0) }
    let thick = dilate(lines, w, h, 1.2 * s)
    let outline = hex("#2a0a3c")
    let dot = 6 * s + 2

    var out = [Double](repeating: 0, count: w * h * 3)
    for y in 0..<h {
        for x in 0..<w {
            let p = y * w + x
            let hsv = rgbToHsv(soft[p * 3], soft[p * 3 + 1], soft[p * 3 + 2])
            var hh = jsRound(hsv.0 / 30) * 30 + 8 * sin(hsv.0 * 0.05 + Double(seed))
            var ss = min(1, jsRound((hsv.1 * 1.9 + 0.25) * 3) / 3)
            let vv = min(1, jsRound((hsv.2 * 1.2 + 0.08) * 4) / 4)
            if ss < 0.3 { hh = 300 + 40 * vv }
            ss = max(ss, 0.55)
            let c3 = hsvToRgb(hh, ss, max(vv, 0.25))
            var col = [c3.0, c3.1, c3.2]
            if vv < 0.62 {
                let u = Double(x + y) / (dot * 1.414), v = Double(x - y) / (dot * 1.414)
                let fx = u - u.rounded(.down) - 0.5, fy = v - v.rounded(.down) - 0.5
                let r = (fx * fx + fy * fy).squareRoot()
                let radius = 0.15 + 0.5 * (0.62 - vv)
                let inDot = 1 - smoothstep(radius - 0.05, radius + 0.05, r)
                for c in 0..<3 { col[c] = mix(col[c], outline[c], inDot * 0.7) }
            }
            let t = thick[p]
            for c in 0..<3 { out[p * 3 + c] = mix(col[c], outline[c], t) }
        }
    }
    return toImage(out, w, h)
}

let jewels: [(Double, [Double])] = [
    (350, hex("#9e0f2a")), (28, hex("#c8650f")), (48, hex("#e0aa1a")), (130, hex("#0f7a3c")),
    (178, hex("#0f8a8c")), (218, hex("#123a9e")), (265, hex("#4a1e8c")), (300, hex("#7a1a6e")),
]

func stainedGlass(_ src: PixelImage, _ seed: Int) -> PixelImage {
    let w = src.width, h = src.height, s = scaleOf(w, h), n = w * h
    let rgb = toRGB(src)
    let cell = max(10, Int(jsRound(30 * s)))
    let fc = Double(cell)
    let vor = voronoi(w, h, cell, seed)
    // Split every Voronoi cell by tonal band so glass pieces follow the subject's contours.
    let bands = 5
    let tone = luminance(blur(rgb, w, h, 3.5 * s), n)
    var seg = (0..<n).map { vor.index[$0] * bands + min(bands - 1, Int(tone[$0] * Double(bands))) }
    let segCount = vor.count * bands
    // Fold slivers (under ~8% of a cell) into the cell's dominant piece.
    var area = [Int](repeating: 0, count: segCount)
    for p in 0..<n { area[seg[p]] += 1 }
    let minArea = fc * fc * 0.08
    for p in 0..<n where Double(area[seg[p]]) < minArea {
        let base = vor.index[p] * bands
        var best = base
        for b in 1..<bands where area[base + b] > area[best] { best = base + b }
        seg[p] = best
    }
    var sums = [Double](repeating: 0, count: segCount * 4)
    for p in 0..<n {
        let k = seg[p] * 4
        sums[k] += rgb[p * 3]; sums[k + 1] += rgb[p * 3 + 1]; sums[k + 2] += rgb[p * 3 + 2]; sums[k + 3] += 1
    }
    // Lead came along piece boundaries, only where tone really changes.
    let toneEdge = sobel(tone, w, h)
    var split = [Double](repeating: 0, count: n)
    for y in 0..<h {
        for x in 0..<w {
            let p = y * w + x
            let boundary = (x + 1 < w && seg[p + 1] != seg[p]) || (y + 1 < h && seg[p + w] != seg[p])
            if boundary { split[p] = smoothstep(0.04, 0.1, toneEdge[p]) }
        }
    }
    let splitLead = blur(dilate(split, w, h, 0.9 * s), w, h, 0.6, channels: 1, passes: 1)
    let lead = 2.2 * s + 1
    let leadCol = hex("#121014"), clearPale = hex("#e8dcae"), clearSmoky = hex("#3c4a6e")

    var glass = [Double](repeating: 0, count: segCount * 3)
    for i in 0..<segCount {
        let k = sums[i * 4 + 3]
        if k == 0 { continue }
        let hsv = rgbToHsv(sums[i * 4] / k, sums[i * 4 + 1] / k, sums[i * 4 + 2] / k)
        let col: [Double]
        if hsv.1 < 0.12 {
            col = hsv.2 > 0.55 ? clearPale : clearSmoky
        } else {
            var best = jewels[0]
            for j in jewels where hueDist(j.0, hsv.0) < hueDist(best.0, hsv.0) { best = j }
            col = best.1
        }
        let bright = 0.55 + hsv.2 * 0.9
        for c in 0..<3 { glass[i * 3 + c] = col[c] * bright }
    }

    var out = [Double](repeating: 0, count: n * 3)
    for y in 0..<h {
        for x in 0..<w {
            let p = y * w + x
            let si = seg[p]
            let fx = Double(x), fy = Double(y)
            let glow = 1.3 - 0.55 * smoothstep(0, 0.8, vor.d1[p] / fc)
            let ripple = 0.88 + 0.24 * fbm(fx / (6 * s), fy / (6 * s), seed + (si % 7), 3)
            let band = sin((fx + fy * 0.6) / (55 * s) + fbm(fx / (200 * s), fy / (200 * s), seed, 2) * 4)
            let spec = smoothstep(0.86, 0.99, band) * 0.35
            let leadMask = max(1 - smoothstep(lead * 0.6, lead + 0.8, vor.border[p]), clamp01(splitLead[p] * 1.6))
            let sheen = leadMask > 0.5 && leadMask < 0.85 ? 0.12 : 0
            for c in 0..<3 {
                let g = glass[si * 3 + c] * glow * ripple + spec
                out[p * 3 + c] = mix(g, leadCol[c] + sheen, leadMask)
            }
        }
    }
    // Dark vignette like the stone frame around a window.
    for y in 0..<h {
        for x in 0..<w {
            let dx = Double(x) / Double(w) - 0.5, dy = Double(y) / Double(h) - 0.5
            let v = 1 - 0.55 * smoothstep(0.3, 0.75, (dx * dx + dy * dy).squareRoot())
            let p = (y * w + x) * 3
            out[p] *= v; out[p + 1] *= v; out[p + 2] *= v
        }
    }
    return toImage(out, w, h)
}

func sketch(_ src: PixelImage, _ seed: Int) -> PixelImage {
    let w = src.width, h = src.height, s = scaleOf(w, h), n = w * h
    let rgb = toRGB(src)
    let lum = luminance(blur(rgb, w, h, 0.8 * s), n)
    let g1 = blur(lum, w, h, 1 * s, channels: 1)
    let g2 = blur(lum, w, h, 3 * s, channels: 1)
    let wash = blur(rgb, w, h, 7 * s)
    let parch = hex("#eedfba"), inkCol = hex("#2a1c16")
    let sp = 5.5 * s + 1.5
    @inline(__always) func lineAt(_ u: Double, _ wobble: Double) -> Double {
        let f = (u + wobble) / sp
        let d = abs(f - f.rounded(.down) - 0.5)
        return 1 - smoothstep(0.08, 0.2, d)
    }

    var out = [Double](repeating: 0, count: n * 3)
    for y in 0..<h {
        for x in 0..<w {
            let p = y * w + x
            let fx = Double(x), fy = Double(y)
            let l = clamp01((lum[p] - 0.5) * 1.35 + 0.52)
            let wob = (fbm(fx / (25 * s), fy / (25 * s), seed, 2) - 0.5) * 6 * s
            var hatch = 0.0
            if l < 0.75 { hatch = max(hatch, lineAt(Double(x + y), wob) * smoothstep(0.75, 0.55, l)) }
            if l < 0.5 { hatch = max(hatch, lineAt(Double(x - y), wob) * smoothstep(0.5, 0.32, l)) }
            if l < 0.28 { hatch = max(hatch, lineAt(fx * 1.414, wob) * smoothstep(0.28, 0.12, l)) }
            let contour = smoothstep(0.012, 0.05, -(g1[p] - g2[p]))
            let ink = max(contour, hatch * 0.75)

            let mottle = 0.88 + 0.12 * fbm(fx / (70 * s), fy / (70 * s), seed + 2, 4)
            let fiber = 0.97 + 0.03 * hash2(x, y, seed + 4)
            let dx = fx / Double(w) - 0.5, dy = fy / Double(h) - 0.5
            let vig = 1 - 0.3 * smoothstep(0.35, 0.72, (dx * dx + dy * dy).squareRoot())

            let hsv = rgbToHsv(wash[p * 3], wash[p * 3 + 1], wash[p * 3 + 2])
            let pig3 = hsvToRgb(hsv.0, min(1, hsv.1 * 1.3), jsRound(hsv.2 * 4) / 4 * 0.5 + 0.5)
            let pig = [pig3.0, pig3.1, pig3.2]
            let blot = smoothstep(0.35, 0.65, fbm(fx / (45 * s), fy / (45 * s), seed + 6, 4))
            let alpha = 0.6 * blot * smoothstep(0.08, 0.3, hsv.1)

            for c in 0..<3 {
                let paper = parch[c] * mottle * fiber * vig
                let washed = paper * mix(1, pig[c], alpha)
                out[p * 3 + c] = mix(washed, inkCol[c], ink)
            }
        }
    }
    return toImage(out, w, h)
}

// MARK: - Sample scene

/// Procedural demo scene (sunset over mountains and sea, with a boat). Port of src/sample.js.
public enum SampleScene {
    public static func render(width: Int = 640, height: Int = 480) -> PixelImage {
        var data = [UInt8](repeating: 255, count: width * height * 4)
        let W = Double(width), H = Double(height)
        let sunX = W * 0.68, sunY = H * 0.42, sunR = H * 0.11
        let horizon = H * 0.58
        func ridge(_ x: Double, _ f: Double, _ a: Double, _ o: Double) -> Double {
            o + a * (sin(x * f) * 0.6 + sin(x * f * 2.3 + 1.7) * 0.3 + sin(x * f * 5.1) * 0.1)
        }
        func lerp(_ a: Double, _ b: Double, _ t: Double) -> Double { a + (b - a) * t }
        // Uint8ClampedArray store: clamp, then round half to even.
        func byte(_ v: Double) -> UInt8 { v.isNaN || v <= 0 ? 0 : (v >= 255 ? 255 : UInt8(v.rounded(.toNearestOrEven))) }

        for yi in 0..<height {
            for xi in 0..<width {
                let x = Double(xi), y = Double(yi)
                let t = y / horizon
                var r = lerp(70, 250, t), g = lerp(40, 170, t * t), b = lerp(140, 90, t)
                let ds = hypot(x - sunX, y - sunY)
                if ds < sunR {
                    r = 255; g = 230; b = 150
                } else if ds < sunR * 2.2 {
                    let k = 1 - (ds - sunR) / (sunR * 1.2)
                    r = lerp(r, 255, k * 0.6); g = lerp(g, 200, k * 0.6); b = lerp(b, 120, k * 0.4)
                }
                let far = ridge(x, 0.012, H * 0.07, H * 0.45)
                let near = ridge(x + 300, 0.008, H * 0.09, H * 0.53)
                if y > far { r = 95; g = 70; b = 120 }
                if y > near { r = 45; g = 50; b = 85 }
                if y > horizon {
                    let d = (y - horizon) / (H - horizon)
                    r = lerp(40, 10, d); g = lerp(80, 40, d); b = lerp(140, 90, d)
                    let wv = sin(x * 0.05 + y * 0.35) * sin(y * 0.9)
                    if wv > 0.75 { r += 60; g += 70; b += 80 }
                    if abs(x - sunX) < sunR * (1 - d * 0.5) && sin(y * 1.3 + x * 0.1) > 0.2 { r = 250; g = 190; b = 110 }
                }
                let bx = W * 0.3, by = horizon + H * 0.12, bw = W * 0.12
                if y > by && y < by + H * 0.03 && abs(x - bx) < bw * (1 - (y - by) / (H * 0.06)) { r = 120; g = 40; b = 30 }
                if y < by && y > by - H * 0.16 && x > bx - 2 && x < bx + (by - y) * -0.45 + bw * 0.6 { r = 240; g = 235; b = 220 }
                let i = (yi * width + xi) * 4
                data[i] = byte(r); data[i + 1] = byte(g); data[i + 2] = byte(b)
            }
        }
        return PixelImage(width: width, height: height, rgba: data)
    }
}
