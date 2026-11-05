package com.prismaclone.app

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.prismaclone.kit.PixelImage
import com.prismaclone.kit.Prisma
import com.prismaclone.kit.SampleScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val original: Bitmap? = null,
    val styled: Bitmap? = null,
    val thumbnails: Map<String, Bitmap> = emptyMap(),
    val filterId: String = Prisma.filters.first().id,
    val intensity: Float = 1f,
    val seed: Int = 1,
    val busy: Boolean = false,
    val subject: String = "",
    val message: String? = null,
) {
    val prompt: String get() = Prisma.buildPrompt(filterId, subject)
}

class PrismaViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var source: PixelImage? = null
    private var renderJob: Job? = null
    private var thumbJob: Job? = null

    init {
        useSample()
    }

    fun useSample() = setSource(SampleScene.render(800, 600))

    fun load(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val pixels = withContext(Dispatchers.IO) {
                    BitmapIo.toPixels(BitmapIo.decode(getApplication(), uri))
                }
                setSource(pixels)
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = "Couldn't open that image") }
            }
        }
    }

    private fun setSource(pixels: PixelImage) {
        source = pixels
        _state.update { it.copy(original = BitmapIo.toBitmap(pixels), styled = null, thumbnails = emptyMap()) }
        render()
        renderThumbnails()
    }

    fun selectFilter(id: String) {
        if (id == _state.value.filterId) return
        _state.update { it.copy(filterId = id) }
        render()
    }

    fun setIntensity(value: Float) = _state.update { it.copy(intensity = value) }

    /** Called when the slider is released, so we don't re-render on every drag step. */
    fun commitIntensity() = render()

    fun reroll() {
        _state.update { it.copy(seed = it.seed % 9973 + 1) }
        render()
        renderThumbnails()
    }

    fun setSubject(text: String) = _state.update { it.copy(subject = text) }

    fun showMessage(text: String?) = _state.update { it.copy(message = text) }

    // A newer request cancels the stale one: its result is dropped before reaching the UI.
    private fun render() {
        val src = source ?: return
        val s = _state.value
        renderJob?.cancel()
        _state.update { it.copy(busy = true) }
        renderJob = viewModelScope.launch {
            val out = withContext(Dispatchers.Default) {
                BitmapIo.toBitmap(Prisma.apply(s.filterId, src, s.intensity.toDouble(), s.seed))
            }
            _state.update { it.copy(styled = out, busy = false) }
        }
    }

    private fun renderThumbnails() {
        val original = _state.value.original ?: return
        val seed = _state.value.seed
        thumbJob?.cancel()
        thumbJob = viewModelScope.launch {
            val small = withContext(Dispatchers.Default) { BitmapIo.toPixels(BitmapIo.thumbnail(original, 160)) }
            for (f in Prisma.filters) {
                val bmp = withContext(Dispatchers.Default) { BitmapIo.toBitmap(Prisma.apply(f.id, small, seed = seed)) }
                _state.update { it.copy(thumbnails = it.thumbnails + (f.id to bmp)) }
            }
        }
    }
}
