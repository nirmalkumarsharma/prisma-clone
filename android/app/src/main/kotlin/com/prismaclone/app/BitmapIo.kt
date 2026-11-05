package com.prismaclone.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.prismaclone.kit.PixelImage
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Longest side we filter at; keeps each filter to a few seconds on a mid-range phone. */
const val MAX_SIDE = 1200

object BitmapIo {
    /** Decode any supported image (HEIC, JPEG, PNG, WebP…), honoring EXIF rotation, scaled down to [maxSide]. */
    fun decode(context: Context, uri: Uri, maxSide: Int = MAX_SIDE): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val k = minOf(1.0, maxSide.toDouble() / max(w, h))
            decoder.setTargetSize(max(1, (w * k).roundToInt()), max(1, (h * k).roundToInt()))
            // Software bitmaps so we can read pixels; the engine assumes opaque sRGB.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
    }

    fun toPixels(bitmap: Bitmap): PixelImage {
        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return PixelImage(bitmap.width, bitmap.height, argb)
    }

    fun toBitmap(image: PixelImage): Bitmap =
        Bitmap.createBitmap(image.argb, image.width, image.height, Bitmap.Config.ARGB_8888)

    /** Square center-crop thumbnail for the filter strip. */
    fun thumbnail(bitmap: Bitmap, side: Int): Bitmap {
        val s = minOf(bitmap.width, bitmap.height)
        val crop = Bitmap.createBitmap(bitmap, (bitmap.width - s) / 2, (bitmap.height - s) / 2, s, s)
        return Bitmap.createScaledBitmap(crop, side, side, true)
    }

    /** Saves to Pictures/Prisma Clone via MediaStore (no storage permission needed on Android 10+). */
    fun saveToGallery(context: Context, bitmap: Bitmap, name: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Prisma Clone")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create a gallery entry")
        try {
            resolver.openOutputStream(uri).use { out ->
                check(out != null && bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "Could not write image" }
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** Writes a PNG to the cache and returns a share-sheet intent for it. */
    fun shareIntent(context: Context, bitmap: Bitmap, name: String): Intent {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null)
    }
}
