package com.prismaclone.app

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.prismaclone.kit.Prisma
import com.prismaclone.kit.SampleScene
import org.junit.Rule
import org.junit.Test

/** Renders the real UI with real filter output on the JVM (no emulator needed). */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenHeight = 3400),
        theme = "android:Theme.Material.NoActionBar",
    )

    private fun state(filterId: String): UiState {
        val src = SampleScene.render(800, 600)
        val original = BitmapIo.toBitmap(src)
        val thumbSrc = BitmapIo.toPixels(BitmapIo.thumbnail(original, 160))
        return UiState(
            original = original,
            styled = BitmapIo.toBitmap(Prisma.apply(filterId, src)),
            thumbnails = Prisma.filters.associate { it.id to BitmapIo.toBitmap(Prisma.apply(it.id, thumbSrc)) },
            filterId = filterId,
            subject = "a fox in a snowy forest",
        )
    }

    @Test
    fun gothic() {
        val s = state("gothic")
        paparazzi.snapshot { PrismaTheme { PrismaContent(s, PrismaActions()) } }
    }

    @Test
    fun udnie() {
        val s = state("udnie")
        paparazzi.snapshot { PrismaTheme { PrismaContent(s, PrismaActions()) } }
    }
}
