package com.prismaclone.kit

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Checks the Kotlin port against reference images produced by the JS engine. */
class PrismaTest {
    private val dir = File(System.getProperty("fixtures.dir"))
    private val meta = File(dir, "fixtures.json").readText()
    private val w = Regex("\"width\": (\\d+)").find(meta)!!.groupValues[1].toInt()
    private val h = Regex("\"height\": (\\d+)").find(meta)!!.groupValues[1].toInt()
    private val seed = Regex("\"seed\": (\\d+)").find(meta)!!.groupValues[1].toInt()

    private fun fixture(name: String) = File(dir, "$name.rgba").readBytes()

    /** Tiny float differences can flip a smoothstep edge, so allow a few stray pixels. */
    private fun assertClose(expected: ByteArray, actual: ByteArray, label: String) {
        assertEquals(expected.size, actual.size)
        var sum = 0L
        var bad = 0
        for (i in expected.indices) {
            if (i % 4 == 3) continue
            val d = abs((expected[i].toInt() and 255) - (actual[i].toInt() and 255))
            sum += d
            if (d > 8) bad++
        }
        val mean = sum.toDouble() / (expected.size / 4 * 3)
        val badShare = bad.toDouble() / (expected.size / 4 * 3)
        assertTrue(mean < 0.5 && badShare < 0.005, "$label differs from JS: mean Δ=$mean, Δ>8 on ${badShare * 100}%")
    }

    @Test
    fun sampleSceneMatchesJs() {
        assertContentEquals(fixture("sample"), SampleScene.render(w, h).toRgba())
    }

    @Test
    fun everyFilterMatchesJs() {
        val src = SampleScene.render(w, h)
        for (f in Prisma.filters) assertClose(fixture(f.id), Prisma.apply(f.id, src, seed = seed).toRgba(), f.id)
    }

    @Test
    fun intensityBlendMatchesJs() {
        val src = SampleScene.render(w, h)
        assertClose(fixture("candy-60"), Prisma.apply("candy", src, intensity = 0.6, seed = seed).toRgba(), "candy@60%")
    }

    @Test
    fun intensityZeroIsOriginal() {
        val src = SampleScene.render(w, h)
        assertContentEquals(src.argb, Prisma.apply("wave", src, intensity = 0.0).argb)
    }

    @Test
    fun promptsMatchJs() {
        for (f in Prisma.filters) {
            val expected = Regex("\"${f.id}\": \"((?:[^\"\\\\]|\\\\.)*)\"").find(meta)!!.groupValues[1]
            assertEquals(expected, Prisma.buildPrompt(f.id, "  a lighthouse at dusk.  "))
        }
        val empty = Regex("\"empty\": \"([^\"]*)\"").find(meta)!!.groupValues[1]
        assertEquals(empty, Prisma.buildPrompt("wave", "   "))
    }

    @Test
    fun unknownFilterThrows() {
        assertFailsWith<IllegalArgumentException> { Prisma.filter("nope") }
    }

    @Test
    fun deterministicAndSizePreserving() {
        val src = SampleScene.render(50, 37)
        for (f in Prisma.filters) {
            val a = Prisma.apply(f.id, src, seed = 9)
            assertEquals(50, a.width); assertEquals(37, a.height)
            assertContentEquals(a.argb, Prisma.apply(f.id, src, seed = 9).argb)
        }
    }
}
