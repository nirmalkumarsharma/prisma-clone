// Prisma-style artistic filters: Kotlin port of src/filters.js.
// Pure JVM code (no Android imports) so it is unit-tested on the desktop against the JS reference.
package com.prismaclone.kit

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** An opaque image as packed 0xAARRGGBB ints (the layout of Android's Bitmap.getPixels). */
class PixelImage(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0 && argb.size == width * height) { "argb must hold width*height pixels" }
    }

    fun toRgba(): ByteArray {
        val out = ByteArray(argb.size * 4)
        for (i in argb.indices) {
            val c = argb[i]
            out[i * 4] = (c shr 16).toByte()
            out[i * 4 + 1] = (c shr 8).toByte()
            out[i * 4 + 2] = c.toByte()
            out[i * 4 + 3] = (c ushr 24).toByte()
        }
        return out
    }

    companion object {
        fun fromRgba(width: Int, height: Int, rgba: ByteArray): PixelImage {
            val px = IntArray(width * height) { i ->
                ((rgba[i * 4 + 3].toInt() and 255) shl 24) or ((rgba[i * 4].toInt() and 255) shl 16) or
                    ((rgba[i * 4 + 1].toInt() and 255) shl 8) or (rgba[i * 4 + 2].toInt() and 255)
            }
            return PixelImage(width, height, px)
        }
    }
}

/** Text-to-image prompt ingredients for a filter. */
data class PromptSpec(val medium: String, val texture: String, val palette: String, val background: String)

class StyleFilter internal constructor(
    val id: String,
    val name: String,
    val tagline: String,
    val prompt: PromptSpec,
    internal val render: (PixelImage, Int) -> PixelImage,
)

object Prisma {
    val filters: List<StyleFilter> = listOf(
        StyleFilter(
            "udnie", "Udnie", "Swirling blue & gold modernist impasto",
            PromptSpec(
                "the style of an expressive modernist oil painting",
                "heavy impasto brushstrokes and dynamic swirling curves",
                "deep cobalt and ultramarine blues with burnished gold and amber",
                "thickly painted canvas",
            ),
            ::udnie,
        ),
        StyleFilter(
            "wave", "Wave", "Japanese woodblock print in indigo ink",
            PromptSpec(
                "the style of a traditional Japanese ukiyo-e woodblock print",
                "bold black woodblock ink lines, carved wood grain and flat color fields",
                "rich indigo and Prussian blue with cream paper and touches of vermilion",
                "fibrous washi parchment",
            ),
            ::wave,
        ),
        StyleFilter(
            "mosaic", "Mosaic", "Fractured tile tessellation with grout lines",
            PromptSpec(
                "the style of an abstract geometric tile mosaic",
                "fractured tessellated color tiles separated by distinct dark grout borders",
                "saturated terracotta, cobalt, ochre and emerald",
                "rough stone and grout",
            ),
            ::mosaic,
        ),
        StyleFilter(
            "candy", "Candy", "Neon pop art with comic halftones",
            PromptSpec(
                "the style of a neon pop-art comic illustration",
                "fluid graphic outlines, flat cel shading and halftone comic dots",
                "hot pink, electric cyan and lemon yellow with deep violet",
                "glossy printed comic paper",
            ),
            ::candy,
        ),
        StyleFilter(
            "gothic", "Gothic", "Cathedral stained glass with heavy lead",
            PromptSpec(
                "the style of a gothic cathedral stained glass window",
                "heavy dark leaded glass lines dividing translucent glowing segments with glass reflections",
                "jewel-toned ruby, sapphire, emerald and amber",
                "backlit stained glass set in dark stone tracery",
            ),
            ::stainedGlass,
        ),
        StyleFilter(
            "tears", "Tears", "Ink cross-hatching & watercolor on parchment",
            PromptSpec(
                "the style of a high-contrast pen-and-ink drawing with watercolor washes",
                "dense ink cross-hatching, confident contour lines and blooming watercolor washes",
                "sepia and iron-gall black ink with muted watercolor accents",
                "aged textured parchment",
            ),
            ::sketch,
        ),
    )

    fun filter(id: String): StyleFilter =
        filters.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Unknown filter: $id")

    /** Apply a filter. [intensity] (0..1) blends with the original. */
    fun apply(id: String, image: PixelImage, intensity: Double = 1.0, seed: Int = 1): PixelImage {
        val styled = filter(id).render(image, seed)
        if (intensity >= 1.0) return styled
        val t = clamp01(intensity)
        val a = image.argb
        val b = styled.argb
        for (i in b.indices) {
            var px = 0xff shl 24
            for (shift in intArrayOf(16, 8, 0)) {
                val v = mix(((a[i] shr shift) and 255).toDouble(), ((b[i] shr shift) and 255).toDouble(), t)
                px = px or (clampByte(Math.rint(v)) shl shift) // ties-to-even, like Uint8ClampedArray
            }
            b[i] = px
        }
        return styled
    }

    /** Build a text-to-image prompt following the Prisma-style rendering template. */
    fun buildPrompt(id: String, subject: String): String {
        val f = filter(id)
        val subj = subject.trim().replace(Regex("[.\\s]+$"), "").ifEmpty { "the scene" }
        val p = f.prompt
        return "A dramatic ${f.name}-style artistic rendering of $subj. " +
            "Rendered in ${p.medium} with ${p.texture}. " +
            "Features a color palette dominated by ${p.palette}, high-contrast chiaroscuro lighting, and bold visual contours. " +
            "Textured background reminiscent of ${p.background}, with vibrant, expressive art stylization applied uniformly across all elements."
    }

    // -----------------------------------------------------------------------
    // Math / noise helpers
    // -----------------------------------------------------------------------

    private fun clamp01(v: Double) = if (v < 0) 0.0 else if (v > 1) 1.0 else v
    private fun clampByte(v: Double) = if (v < 0) 0 else if (v > 255) 255 else v.toInt()
    private fun mix(a: Double, b: Double, t: Double) = a + (b - a) * t
    private fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        val t = clamp01((x - e0) / (e1 - e0))
        return t * t * (3 - 2 * t)
    }

    /** JavaScript's Math.round: halves round toward +∞. */
    private fun jsRound(v: Double) = floor(v + 0.5)

    internal fun hash2(x: Int, y: Int, seed: Int): Double {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h.toLong() and 0xffffffffL) / 4294967296.0
    }

    private fun valueNoise(x: Double, y: Double, seed: Int): Double {
        val xi = floor(x)
        val yi = floor(y)
        val xf = x - xi
        val yf = y - yi
        val u = xf * xf * (3 - 2 * xf)
        val v = yf * yf * (3 - 2 * yf)
        val ix = xi.toInt()
        val iy = yi.toInt()
        val a = hash2(ix, iy, seed)
        val b = hash2(ix + 1, iy, seed)
        val c = hash2(ix, iy + 1, seed)
        val d = hash2(ix + 1, iy + 1, seed)
        return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v
    }

    private fun fbm(x: Double, y: Double, seed: Int, octaves: Int = 4): Double {
        var sum = 0.0
        var amp = 0.5
        var f = 1.0
        var norm = 0.0
        for (i in 0 until octaves) {
            sum += amp * valueNoise(x * f, y * f, seed + i * 101)
            norm += amp
            amp *= 0.5
            f *= 2
        }
        return sum / norm
    }

    // -----------------------------------------------------------------------
    // Color helpers (channels 0..1)
    // -----------------------------------------------------------------------

    private fun rgbToHsv(r: Double, g: Double, b: Double): DoubleArray {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        var h = 0.0
        if (d > 1e-6) {
            h = when (mx) {
                r -> ((g - b) / d) % 6
                g -> (b - r) / d + 2
                else -> (r - g) / d + 4
            }
            h *= 60
            if (h < 0) h += 360
        }
        return doubleArrayOf(h, if (mx == 0.0) 0.0 else d / mx, mx)
    }

    private fun hsvToRgb(h: Double, s: Double, v: Double): DoubleArray {
        val c = v * s
        val hp = (((h % 360) + 360) % 360) / 60
        val x = c * (1 - abs((hp % 2) - 1))
        val (r, g, b) = when {
            hp < 1 -> Triple(c, x, 0.0)
            hp < 2 -> Triple(x, c, 0.0)
            hp < 3 -> Triple(0.0, c, x)
            hp < 4 -> Triple(0.0, x, c)
            hp < 5 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = v - c
        return doubleArrayOf(r + m, g + m, b + m)
    }

    private fun hex(s: String) = DoubleArray(3) { s.substring(1 + it * 2, 3 + it * 2).toInt(16) / 255.0 }

    private class Stop(val t: Double, val c: DoubleArray)

    private fun gradientAt(stops: List<Stop>, tIn: Double): DoubleArray {
        val t = clamp01(tIn)
        for (i in 1 until stops.size) {
            if (t <= stops[i].t) {
                val s0 = stops[i - 1]
                val s1 = stops[i]
                val span = s1.t - s0.t
                val k = (t - s0.t) / (if (span == 0.0) 1.0 else span)
                return DoubleArray(3) { mix(s0.c[it], s1.c[it], k) }
            }
        }
        return stops.last().c.copyOf()
    }

    private fun hueDist(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }

    // -----------------------------------------------------------------------
    // Image plane helpers
    // -----------------------------------------------------------------------

    private fun toRgb(img: PixelImage): DoubleArray {
        val out = DoubleArray(img.argb.size * 3)
        for (i in img.argb.indices) {
            val c = img.argb[i]
            out[i * 3] = ((c shr 16) and 255) / 255.0
            out[i * 3 + 1] = ((c shr 8) and 255) / 255.0
            out[i * 3 + 2] = (c and 255) / 255.0
        }
        return out
    }

    private fun toImage(rgb: DoubleArray, w: Int, h: Int): PixelImage {
        val px = IntArray(w * h)
        for (i in px.indices) {
            val r = jsRound(clamp01(rgb[i * 3]) * 255).toInt()
            val g = jsRound(clamp01(rgb[i * 3 + 1]) * 255).toInt()
            val b = jsRound(clamp01(rgb[i * 3 + 2]) * 255).toInt()
            px[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        return PixelImage(w, h, px)
    }

    private fun luminance(rgb: DoubleArray, n: Int) =
        DoubleArray(n) { 0.2126 * rgb[it * 3] + 0.7152 * rgb[it * 3 + 1] + 0.0722 * rgb[it * 3 + 2] }

    /** Separable box blur, applied `passes` times, on an interleaved plane. */
    private fun blur(src: DoubleArray, w: Int, h: Int, radius: Double, channels: Int = 3, passes: Int = 2): DoubleArray {
        val r = max(0, jsRound(radius).toInt())
        if (r == 0) return src.copyOf()
        val a = src.copyOf()
        val b = DoubleArray(src.size)
        val norm = 1.0 / (2 * r + 1)
        fun pass(from: DoubleArray, to: DoubleArray, horizontal: Boolean) {
            val len = if (horizontal) w else h
            val lines = if (horizontal) h else w
            for (line in 0 until lines) {
                for (c in 0 until channels) {
                    fun idx(k: Int): Int {
                        val kk = if (k < 0) 0 else if (k >= len) len - 1 else k
                        return (if (horizontal) line * w + kk else kk * w + line) * channels + c
                    }
                    var sum = 0.0
                    for (k in -r..r) sum += from[idx(k)]
                    for (k in 0 until len) {
                        to[idx(k)] = sum * norm
                        sum += from[idx(k + r + 1)] - from[idx(k - r)]
                    }
                }
            }
        }
        repeat(passes) {
            pass(a, b, true)
            pass(b, a, false)
        }
        return a
    }

    private fun sobel(lum: DoubleArray, w: Int, h: Int): DoubleArray {
        val out = DoubleArray(w * h)
        fun at(x: Int, y: Int): Double {
            val yy = if (y < 0) 0 else if (y >= h) h - 1 else y
            val xx = if (x < 0) 0 else if (x >= w) w - 1 else x
            return lum[yy * w + xx]
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val gx = -at(x - 1, y - 1) - 2 * at(x - 1, y) - at(x - 1, y + 1) +
                    at(x + 1, y - 1) + 2 * at(x + 1, y) + at(x + 1, y + 1)
                val gy = -at(x - 1, y - 1) - 2 * at(x, y - 1) - at(x + 1, y - 1) +
                    at(x - 1, y + 1) + 2 * at(x, y + 1) + at(x + 1, y + 1)
                out[y * w + x] = sqrt(gx * gx + gy * gy)
            }
        }
        return out
    }

    /** Grayscale max filter (thickens line masks). */
    private fun dilate(mask: DoubleArray, w: Int, h: Int, radius: Double): DoubleArray {
        val r = jsRound(radius).toInt()
        if (r <= 0) return mask
        val tmp = DoubleArray(w * h)
        val out = DoubleArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0.0
            for (k in -r..r) {
                val xx = min(w - 1, max(0, x + k))
                if (mask[y * w + xx] > m) m = mask[y * w + xx]
            }
            tmp[y * w + x] = m
        }
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0.0
            for (k in -r..r) {
                val yy = min(h - 1, max(0, y + k))
                if (tmp[yy * w + x] > m) m = tmp[yy * w + x]
            }
            out[y * w + x] = m
        }
        return out
    }

    private class Voronoi(
        val index: IntArray,
        val d1: DoubleArray,
        val border: DoubleArray,
        val sx: DoubleArray,
        val sy: DoubleArray,
        val count: Int,
    )

    /** Jittered-grid Voronoi: nearest seed, distance to it, and distance to the cell border. */
    private fun voronoi(w: Int, h: Int, cell: Int, seed: Int): Voronoi {
        val cols = (w + cell - 1) / cell + 1
        val rows = (h + cell - 1) / cell + 1
        val sx = DoubleArray(cols * rows)
        val sy = DoubleArray(cols * rows)
        for (gy in 0 until rows) for (gx in 0 until cols) {
            val i = gy * cols + gx
            sx[i] = (gx + 0.1 + 0.8 * hash2(gx, gy, seed)) * cell
            sy[i] = (gy + 0.1 + 0.8 * hash2(gx, gy, seed + 7)) * cell
        }
        val index = IntArray(w * h)
        val d1 = DoubleArray(w * h)
        val border = DoubleArray(w * h)
        for (y in 0 until h) {
            val gy0 = y / cell
            for (x in 0 until w) {
                val gx0 = x / cell
                var best = Double.POSITIVE_INFINITY
                var second = Double.POSITIVE_INFINITY
                var bi = 0
                for (oy in -1..1) {
                    val gy = gy0 + oy
                    if (gy < 0 || gy >= rows) continue
                    for (ox in -1..1) {
                        val gx = gx0 + ox
                        if (gx < 0 || gx >= cols) continue
                        val i = gy * cols + gx
                        val dx = sx[i] - x
                        val dy = sy[i] - y
                        val d = dx * dx + dy * dy
                        if (d < best) {
                            second = best; best = d; bi = i
                        } else if (d < second) second = d
                    }
                }
                val p = y * w + x
                index[p] = bi
                val a = sqrt(best)
                d1[p] = a
                border[p] = (sqrt(second) - a) * 0.5
            }
        }
        return Voronoi(index, d1, border, sx, sy, cols * rows)
    }

    private fun cellAverages(rgb: DoubleArray, vor: Voronoi, n: Int): DoubleArray {
        val sums = DoubleArray(vor.count * 4)
        for (p in 0 until n) {
            val c = vor.index[p] * 4
            sums[c] += rgb[p * 3]
            sums[c + 1] += rgb[p * 3 + 1]
            sums[c + 2] += rgb[p * 3 + 2]
            sums[c + 3]++
        }
        val avg = DoubleArray(vor.count * 3)
        for (i in 0 until vor.count) {
            val k = if (sums[i * 4 + 3] == 0.0) 1.0 else sums[i * 4 + 3]
            avg[i * 3] = sums[i * 4] / k
            avg[i * 3 + 1] = sums[i * 4 + 1] / k
            avg[i * 3 + 2] = sums[i * 4 + 2] / k
        }
        return avg
    }

    /** Feature size relative to a ~600px reference so filters look the same at any resolution. */
    private fun scaleOf(w: Int, h: Int) = max(w, h) / 600.0

    // -----------------------------------------------------------------------
    // Filters
    // -----------------------------------------------------------------------

    private val UDNIE_STOPS = listOf(
        Stop(0.0, hex("#0a1030")), Stop(0.22, hex("#16347a")), Stop(0.42, hex("#1f63b0")),
        Stop(0.58, hex("#3c8ca0")), Stop(0.72, hex("#d29a2e")), Stop(0.88, hex("#f5c85a")), Stop(1.0, hex("#fff2c8")),
    )

    private fun udnie(src: PixelImage, seed: Int): PixelImage {
        val w = src.width
        val h = src.height
        val s = scaleOf(w, h)
        val rgb = toRgb(src)

        val ang = DoubleArray(w * h)
        val f = 1 / (110 * s)
        for (y in 0 until h) for (x in 0 until w) ang[y * w + x] = fbm(x * f, y * f, seed, 3) * Math.PI * 4

        val amp = 7 * s
        val warped = DoubleArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val a = ang[y * w + x]
            val xx = min(w - 1, max(0, jsRound(x + amp * cos(a)).toInt()))
            val yy = min(h - 1, max(0, jsRound(y + amp * sin(a)).toInt()))
            val q = (yy * w + xx) * 3
            val p = (y * w + x) * 3
            warped[p] = rgb[q]; warped[p + 1] = rgb[q + 1]; warped[p + 2] = rgb[q + 2]
        }
        val soft = blur(warped, w, h, 2 * s)
        val lum = luminance(soft, w * h)

        val noise = DoubleArray(w * h)
        val cellN = max(1, jsRound(1.5 * s).toInt())
        for (y in 0 until h) for (x in 0 until w) noise[y * w + x] = hash2(x / cellN, y / cellN, seed + 3)
        val len = max(4, jsRound(9 * s).toInt())
        val lic = DoubleArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0.0
            var cnt = 0
            for (dir in intArrayOf(1, -1)) {
                var px = x.toDouble()
                var py = y.toDouble()
                for (k in 0 until len) {
                    val ix = px.toInt()
                    val iy = py.toInt()
                    // toInt() truncates toward zero exactly like JS `px | 0`.
                    if (ix < 0 || iy < 0 || ix >= w || iy >= h) break
                    sum += noise[iy * w + ix]; cnt++
                    val a = ang[iy * w + ix]
                    px += dir * cos(a); py += dir * sin(a)
                }
            }
            lic[y * w + x] = sum / (if (cnt == 0) 1 else cnt)
        }

        val edge = sobel(lum, w, h)
        val out = DoubleArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val p = y * w + x
            val l = clamp01((lum[p] - 0.5) * 1.45 + 0.5)
            val pal = gradientAt(UDNIE_STOPS, l)
            val hsv = rgbToHsv(soft[p * 3], soft[p * 3 + 1], soft[p * 3 + 2])
            val own = hsvToRgb(hsv[0], min(1.0, hsv[1] * 1.6), hsv[2])
            val xa = max(0, x - 1); val ya = max(0, y - 1)
            val xb = min(w - 1, x + 1); val yb = min(h - 1, y + 1)
            val ridge = (lic[yb * w + xb] - lic[ya * w + xa]) * 2.2
            val stroke = 0.78 + 0.45 * lic[p] - ridge
            val ink = 1 - smoothstep(0.25, 0.9, edge[p]) * 0.65
            for (c in 0..2) out[p * 3 + c] = mix(pal[c], own[c], 0.28) * stroke * ink
        }
        return toImage(out, w, h)
    }

    private val WAVE_INKS = listOf(hex("#12142a"), hex("#1c3066"), hex("#345c96"), hex("#8cacc4"), hex("#ece2c8"))

    private fun wave(src: PixelImage, seed: Int): PixelImage {
        val w = src.width
        val h = src.height
        val s = scaleOf(w, h)
        val soft = blur(toRgb(src), w, h, 1.5 * s)
        val lum = luminance(soft, w * h)
        val edge = sobel(blur(lum, w, h, 1 * s, 1), w, h)
        val lines = DoubleArray(w * h) { smoothstep(0.18, 0.32, edge[it]) }
        val thick = dilate(lines, w, h, 0.8 * s)
        val vermilion = hex("#b4523a")
        val ochre = hex("#d4aa62")

        val out = DoubleArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val p = y * w + x
            val l = clamp01((lum[p] - 0.5) * 1.3 + 0.5)
            val level = if (l < 0.2) 0 else if (l < 0.4) 1 else if (l < 0.6) 2 else if (l < 0.8) 3 else 4
            var col = WAVE_INKS[level]
            val hsv = rgbToHsv(soft[p * 3], soft[p * 3 + 1], soft[p * 3 + 2])
            if (hsv[1] > 0.35 && hsv[2] > 0.3 && (hsv[0] < 55 || hsv[0] > 330)) {
                col = if (level <= 2) vermilion else ochre
            }
            val grain = 1 + 0.045 * sin(x * 0.09 / s + fbm(x / (40 * s), y / (8 * s), seed, 2) * 14)
            val paper = 0.9 + 0.1 * fbm(x / (2 * s), y / (30 * s), seed + 5, 3)
            val speck = if (hash2(x, y, seed + 9) > 0.995) 0.85 else 1.0
            val k = grain * paper * speck
            val t = thick[p]
            for (c in 0..2) out[p * 3 + c] = mix(col[c] * k, WAVE_INKS[0][c], t)
        }
        return toImage(out, w, h)
    }

    private fun mosaic(src: PixelImage, seed: Int): PixelImage {
        val w = src.width
        val h = src.height
        val s = scaleOf(w, h)
        val n = w * h
        val rgb = toRgb(src)
        val cell = max(5, jsRound(13 * s).toInt())
        val vor = voronoi(w, h, cell, seed)
        val avg = cellAverages(rgb, vor, n)
        val grout = 1.1 * s + 0.6
        val groutCol = hex("#2a2622")

        val out = DoubleArray(n * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val p = y * w + x
            val ci = vor.index[p]
            val hsv = rgbToHsv(avg[ci * 3], avg[ci * 3 + 1], avg[ci * 3 + 2])
            val ss = min(1.0, hsv[1] * 1.45 + 0.05)
            val vv = clamp01((hsv[2] - 0.5) * 1.25 + 0.52)
            val jitter = 0.92 + 0.16 * hash2(ci, 0, seed + 11)
            val tile = hsvToRgb(hsv[0] + (hash2(ci, 1, seed) - 0.5) * 10, ss, vv)
            val dx = (x - vor.sx[ci]) / cell
            val dy = (y - vor.sy[ci]) / cell
            val bevel = 1 - 0.28 * (dx + dy) - 0.15 * smoothstep(0.4, 0.9, vor.d1[p] / cell)
            val grit = 0.95 + 0.1 * hash2(x, y, seed + 13)
            val g = 1 - smoothstep(grout * 0.6, grout + 0.8, vor.border[p])
            for (c in 0..2) out[p * 3 + c] = mix(tile[c] * jitter * bevel * grit, groutCol[c] * grit, g)
        }
        return toImage(out, w, h)
    }

    private fun candy(src: PixelImage, seed: Int): PixelImage {
        val w = src.width
        val h = src.height
        val s = scaleOf(w, h)
        val soft = blur(toRgb(src), w, h, 2 * s)
        val lum = luminance(soft, w * h)
        val edge = sobel(lum, w, h)
        val lines = DoubleArray(w * h) { smoothstep(0.12, 0.24, edge[it]) }
        val thick = dilate(lines, w, h, 1.2 * s)
        val outline = hex("#2a0a3c")
        val dot = 6 * s + 2

        val out = DoubleArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val p = y * w + x
            val hsv = rgbToHsv(soft[p * 3], soft[p * 3 + 1], soft[p * 3 + 2])
            var hh = jsRound(hsv[0] / 30) * 30 + 8 * sin(hsv[0] * 0.05 + seed)
            var ss = min(1.0, jsRound((hsv[1] * 1.9 + 0.25) * 3) / 3)
            val vv = min(1.0, jsRound((hsv[2] * 1.2 + 0.08) * 4) / 4)
            if (ss < 0.3) hh = 300 + 40 * vv
            ss = max(ss, 0.55)
            var col = hsvToRgb(hh, ss, max(vv, 0.25))
            if (vv < 0.62) {
                val u = (x + y) / (dot * 1.414)
                val v = (x - y) / (dot * 1.414)
                val fx = u - floor(u) - 0.5
                val fy = v - floor(v) - 0.5
                val r = sqrt(fx * fx + fy * fy)
                val radius = 0.15 + 0.5 * (0.62 - vv)
                val inDot = 1 - smoothstep(radius - 0.05, radius + 0.05, r)
                col = DoubleArray(3) { mix(col[it], outline[it], inDot * 0.7) }
            }
            val t = thick[p]
            for (c in 0..2) out[p * 3 + c] = mix(col[c], outline[c], t)
        }
        return toImage(out, w, h)
    }

    private val JEWELS = listOf(
        350.0 to hex("#9e0f2a"), 28.0 to hex("#c8650f"), 48.0 to hex("#e0aa1a"), 130.0 to hex("#0f7a3c"),
        178.0 to hex("#0f8a8c"), 218.0 to hex("#123a9e"), 265.0 to hex("#4a1e8c"), 300.0 to hex("#7a1a6e"),
    )

    private fun stainedGlass(src: PixelImage, seed: Int): PixelImage {
        val w = src.width
        val h = src.height
        val s = scaleOf(w, h)
        val n = w * h
        val rgb = toRgb(src)
        val cell = max(10, jsRound(30 * s).toInt())
        val vor = voronoi(w, h, cell, seed)
        // Split every Voronoi cell by tonal band so glass pieces follow the subject's contours.
        val bands = 5
        val tone = luminance(blur(rgb, w, h, 3.5 * s), n)
        val seg = IntArray(n) { vor.index[it] * bands + min(bands - 1, (tone[it] * bands).toInt()) }
        val segCount = vor.count * bands
        // Fold slivers (under ~8% of a cell) into the cell's dominant piece.
        val area = IntArray(segCount)
        for (p in 0 until n) area[seg[p]]++
        val minArea = cell * cell * 0.08
        for (p in 0 until n) {
            if (area[seg[p]] >= minArea) continue
            val base = vor.index[p] * bands
            var best = base
            for (b in 1 until bands) if (area[base + b] > area[best]) best = base + b
            seg[p] = best
        }
        val sums = DoubleArray(segCount * 4)
        for (p in 0 until n) {
            val k = seg[p] * 4
            sums[k] += rgb[p * 3]; sums[k + 1] += rgb[p * 3 + 1]; sums[k + 2] += rgb[p * 3 + 2]; sums[k + 3]++
        }
        // Lead came along piece boundaries, only where tone really changes.
        val toneEdge = sobel(tone, w, h)
        val split = DoubleArray(n)
        for (y in 0 until h) for (x in 0 until w) {
            val p = y * w + x
            val boundary = (x + 1 < w && seg[p + 1] != seg[p]) || (y + 1 < h && seg[p + w] != seg[p])
            if (boundary) split[p] = smoothstep(0.04, 0.1, toneEdge[p])
        }
        val splitLead = blur(dilate(split, w, h, 0.9 * s), w, h, 0.6, 1, 1)
        val lead = 2.2 * s + 1
        val leadCol = hex("#121014")
        val clearPale = hex("#e8dcae")
        val clearSmoky = hex("#3c4a6e")

        val glass = DoubleArray(segCount * 3)
        for (i in 0 until segCount) {
            val k = sums[i * 4 + 3]
            if (k == 0.0) continue
            val hsv = rgbToHsv(sums[i * 4] / k, sums[i * 4 + 1] / k, sums[i * 4 + 2] / k)
            val col = if (hsv[1] < 0.12) {
                if (hsv[2] > 0.55) clearPale else clearSmoky
            } else {
                var best = JEWELS[0]
                for (j in JEWELS) if (hueDist(j.first, hsv[0]) < hueDist(best.first, hsv[0])) best = j
                best.second
            }
            val bright = 0.55 + hsv[2] * 0.9
            for (c in 0..2) glass[i * 3 + c] = col[c] * bright
        }

        val out = DoubleArray(n * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val p = y * w + x
            val si = seg[p]
            val glow = 1.3 - 0.55 * smoothstep(0.0, 0.8, vor.d1[p] / cell)
            val ripple = 0.88 + 0.24 * fbm(x / (6 * s), y / (6 * s), seed + (si % 7), 3)
            val band = sin((x + y * 0.6) / (55 * s) + fbm(x / (200 * s), y / (200 * s), seed, 2) * 4)
            val spec = smoothstep(0.86, 0.99, band) * 0.35
            val leadMask = max(1 - smoothstep(lead * 0.6, lead + 0.8, vor.border[p]), clamp01(splitLead[p] * 1.6))
            val sheen = if (leadMask > 0.5 && leadMask < 0.85) 0.12 else 0.0
            for (c in 0..2) {
                val g = glass[si * 3 + c] * glow * ripple + spec
                out[p * 3 + c] = mix(g, leadCol[c] + sheen, leadMask)
            }
        }
        // Dark vignette like the stone frame around a window.
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x.toDouble() / w - 0.5
            val dy = y.toDouble() / h - 0.5
            val v = 1 - 0.55 * smoothstep(0.3, 0.75, sqrt(dx * dx + dy * dy))
            val p = (y * w + x) * 3
            out[p] *= v; out[p + 1] *= v; out[p + 2] *= v
        }
        return toImage(out, w, h)
    }

    private fun sketch(src: PixelImage, seed: Int): PixelImage {
        val w = src.width
        val h = src.height
        val s = scaleOf(w, h)
        val n = w * h
        val rgb = toRgb(src)
        val lum = luminance(blur(rgb, w, h, 0.8 * s), n)
        val g1 = blur(lum, w, h, 1 * s, 1)
        val g2 = blur(lum, w, h, 3 * s, 1)
        val wash = blur(rgb, w, h, 7 * s)
        val parch = hex("#eedfba")
        val inkCol = hex("#2a1c16")
        val sp = 5.5 * s + 1.5
        fun lineAt(u: Double, wobble: Double): Double {
            val f = (u + wobble) / sp
            val d = abs(f - floor(f) - 0.5)
            return 1 - smoothstep(0.08, 0.2, d)
        }

        val out = DoubleArray(n * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val p = y * w + x
            val l = clamp01((lum[p] - 0.5) * 1.35 + 0.52)
            val wob = (fbm(x / (25 * s), y / (25 * s), seed, 2) - 0.5) * 6 * s
            var hatch = 0.0
            if (l < 0.75) hatch = max(hatch, lineAt((x + y).toDouble(), wob) * smoothstep(0.75, 0.55, l))
            if (l < 0.5) hatch = max(hatch, lineAt((x - y).toDouble(), wob) * smoothstep(0.5, 0.32, l))
            if (l < 0.28) hatch = max(hatch, lineAt(x * 1.414, wob) * smoothstep(0.28, 0.12, l))
            val dog = g1[p] - g2[p]
            val contour = smoothstep(0.012, 0.05, -dog)
            val ink = max(contour, hatch * 0.75)

            val mottle = 0.88 + 0.12 * fbm(x / (70 * s), y / (70 * s), seed + 2, 4)
            val fiber = 0.97 + 0.03 * hash2(x, y, seed + 4)
            val dx = x.toDouble() / w - 0.5
            val dy = y.toDouble() / h - 0.5
            val vig = 1 - 0.3 * smoothstep(0.35, 0.72, sqrt(dx * dx + dy * dy))

            val hsv = rgbToHsv(wash[p * 3], wash[p * 3 + 1], wash[p * 3 + 2])
            val pig = hsvToRgb(hsv[0], min(1.0, hsv[1] * 1.3), jsRound(hsv[2] * 4) / 4 * 0.5 + 0.5)
            val blot = smoothstep(0.35, 0.65, fbm(x / (45 * s), y / (45 * s), seed + 6, 4))
            val alpha = 0.6 * blot * smoothstep(0.08, 0.3, hsv[1])

            for (c in 0..2) {
                val paper = parch[c] * mottle * fiber * vig
                val washed = paper * mix(1.0, pig[c], alpha)
                out[p * 3 + c] = mix(washed, inkCol[c], ink)
            }
        }
        return toImage(out, w, h)
    }
}

/** Procedural demo scene (sunset over mountains and sea, with a boat). Port of src/sample.js. */
object SampleScene {
    fun render(width: Int = 640, height: Int = 480): PixelImage {
        val px = IntArray(width * height)
        val sunX = width * 0.68
        val sunY = height * 0.42
        val sunR = height * 0.11
        val horizon = height * 0.58
        fun ridge(x: Double, f: Double, a: Double, o: Double) =
            o + a * (sin(x * f) * 0.6 + sin(x * f * 2.3 + 1.7) * 0.3 + sin(x * f * 5.1) * 0.1)
        fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
        // Uint8ClampedArray store: clamp, then round half to even.
        fun byte(v: Double) = if (v.isNaN() || v <= 0) 0 else if (v >= 255) 255 else Math.rint(v).toInt()

        for (y in 0 until height) for (x in 0 until width) {
            var r: Double
            var g: Double
            var b: Double
            val t = y / horizon
            r = lerp(70.0, 250.0, t); g = lerp(40.0, 170.0, t * t); b = lerp(140.0, 90.0, t)
            val ds = Math.hypot(x - sunX, y - sunY)
            if (ds < sunR) {
                r = 255.0; g = 230.0; b = 150.0
            } else if (ds < sunR * 2.2) {
                val k = 1 - (ds - sunR) / (sunR * 1.2)
                r = lerp(r, 255.0, k * 0.6); g = lerp(g, 200.0, k * 0.6); b = lerp(b, 120.0, k * 0.4)
            }
            val far = ridge(x.toDouble(), 0.012, height * 0.07, height * 0.45)
            val near = ridge(x + 300.0, 0.008, height * 0.09, height * 0.53)
            if (y > far) { r = 95.0; g = 70.0; b = 120.0 }
            if (y > near) { r = 45.0; g = 50.0; b = 85.0 }
            if (y > horizon) {
                val d = (y - horizon) / (height - horizon)
                r = lerp(40.0, 10.0, d); g = lerp(80.0, 40.0, d); b = lerp(140.0, 90.0, d)
                val wv = sin(x * 0.05 + y * 0.35) * sin(y * 0.9)
                if (wv > 0.75) { r += 60; g += 70; b += 80 }
                if (abs(x - sunX) < sunR * (1 - d * 0.5) && sin(y * 1.3 + x * 0.1) > 0.2) {
                    r = 250.0; g = 190.0; b = 110.0
                }
            }
            val bx = width * 0.3
            val by = horizon + height * 0.12
            val bw = width * 0.12
            if (y > by && y < by + height * 0.03 && abs(x - bx) < bw * (1 - (y - by) / (height * 0.06))) {
                r = 120.0; g = 40.0; b = 30.0
            }
            if (y < by && y > by - height * 0.16 && x > bx - 2 && x < bx + (by - y) * -0.45 + bw * 0.6) {
                r = 240.0; g = 235.0; b = 220.0
            }
            px[y * width + x] = (0xff shl 24) or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
        }
        return PixelImage(width, height, px)
    }
}
