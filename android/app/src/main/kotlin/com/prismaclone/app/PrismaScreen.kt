package com.prismaclone.app

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prismaclone.kit.Prisma
import com.prismaclone.kit.StyleFilter

private val Bg = Color(0xFF0E0D12)
private val Panel = Color(0xFF1A1820)
private val Line = Color(0xFF2C2934)
private val Muted = Color(0xFF9A95A6)
private val Accent = Color(0xFFF5C85A)

private val Colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1A1408),
    background = Bg,
    surface = Panel,
    onBackground = Color(0xFFF1EEE8),
    onSurface = Color(0xFFF1EEE8),
    outline = Line,
)

@Composable
fun PrismaTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Colors, content = content)

@Composable
fun PrismaApp(vm: PrismaViewModel) {
    PrismaTheme { PrismaScreen(vm) }
}

/** Everything the UI can ask for; keeps [PrismaContent] stateless and previewable. */
class PrismaActions(
    val pickPhoto: () -> Unit = {},
    val useSample: () -> Unit = {},
    val selectFilter: (String) -> Unit = {},
    val setIntensity: (Float) -> Unit = {},
    val commitIntensity: () -> Unit = {},
    val reroll: () -> Unit = {},
    val share: () -> Unit = {},
    val save: () -> Unit = {},
    val setSubject: (String) -> Unit = {},
    val copyPrompt: () -> Unit = {},
)

@Composable
private fun PrismaScreen(vm: PrismaViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.load(uri)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.showMessage(null)
        }
    }

    val actions = PrismaActions(
        pickPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        useSample = vm::useSample,
        selectFilter = vm::selectFilter,
        setIntensity = vm::setIntensity,
        commitIntensity = vm::commitIntensity,
        reroll = vm::reroll,
        share = {
            state.styled?.let { context.startActivity(BitmapIo.shareIntent(context, it, "prisma-${state.filterId}")) }
        },
        save = {
            state.styled?.let { bmp ->
                val msg = runCatching { BitmapIo.saveToGallery(context, bmp, "prisma-${state.filterId}-${System.currentTimeMillis()}") }
                    .fold({ "Saved to Pictures/Prisma Clone" }, { "Couldn't save: ${it.message}" })
                vm.showMessage(msg)
            }
        },
        setSubject = vm::setSubject,
        copyPrompt = {
            clipboard.setText(AnnotatedString(state.prompt))
            vm.showMessage("Prompt copied")
        },
    )
    PrismaContent(state, actions, snackbar)
}

@Composable
fun PrismaContent(state: UiState, actions: PrismaActions, snackbar: SnackbarHostState = remember { SnackbarHostState() }) {
    Scaffold(
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Prisma", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(" clone", fontSize = 22.sp, color = Accent)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = actions.useSample) { Text("Sample") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = actions.pickPhoto) { Text("Photo") }
            }

            Stage(original = state.original, styled = state.styled, busy = state.busy)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Intensity ${(state.intensity * 100).toInt()}%", Modifier.width(120.dp))
                Slider(
                    value = state.intensity,
                    onValueChange = actions.setIntensity,
                    onValueChangeFinished = actions.commitIntensity,
                    valueRange = 0.4f..1f,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.reroll, modifier = Modifier.weight(1f)) { Text("Re-roll") }
                OutlinedButton(onClick = actions.share, enabled = state.styled != null, modifier = Modifier.weight(1f)) { Text("Share") }
                Button(onClick = actions.save, enabled = state.styled != null, modifier = Modifier.weight(1f)) { Text("Save") }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(Prisma.filters, key = { it.id }) { f ->
                    FilterCard(f, state.thumbnails[f.id], selected = f.id == state.filterId) { actions.selectFilter(f.id) }
                }
            }

            Surface(color = Panel, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Line)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Prompt builder", fontWeight = FontWeight.SemiBold)
                    Text("Describe a subject to get a text-to-image prompt in the selected style.", color = Muted, fontSize = 13.sp)
                    OutlinedTextField(
                        value = state.subject,
                        onValueChange = actions.setSubject,
                        placeholder = { Text("e.g. a lighthouse on a cliff during a storm") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Text(state.prompt, fontSize = 14.sp, lineHeight = 20.sp)
                    OutlinedButton(onClick = actions.copyPrompt) { Text("Copy prompt") }
                }
            }
        }
    }
}

/** The main image. Press and hold to see the original. */
@Composable
private fun Stage(original: Bitmap?, styled: Bitmap?, busy: Boolean) {
    var comparing by remember { mutableStateOf(false) }
    val shown = if (comparing || styled == null) original else styled
    val ratio = original?.let { it.width.toFloat() / it.height } ?: (4f / 3f)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .aspectRatio(ratio, matchHeightConstraintsFirst = false)
            .clip(RoundedCornerShape(14.dp))
            .background(Panel)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    comparing = true
                    tryAwaitRelease()
                    comparing = false
                })
            }
            .semantics { contentDescription = "Styled image. Press and hold to compare with the original." },
        contentAlignment = Alignment.Center,
    ) {
        shown?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        if (busy) {
            Box(Modifier.fillMaxSize().background(Bg.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Accent)
                    Text("Painting…", Modifier.padding(top = 8.dp), fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (comparing) {
            Text(
                "Original",
                Modifier.align(Alignment.TopStart).padding(10.dp)
                    .background(Bg.copy(alpha = 0.7f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun FilterCard(filter: StyleFilter, thumb: Bitmap?, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .width(124.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .border(2.dp, if (selected) Accent else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "${filter.name} filter${if (selected) ", selected" else ""}" },
    ) {
        Box(Modifier.size(124.dp).background(Line), contentAlignment = Alignment.Center) {
            if (thumb != null) {
                Image(thumb.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Muted)
            }
        }
        Text(filter.name, Modifier.padding(start = 8.dp, top = 6.dp, end = 8.dp), fontWeight = FontWeight.SemiBold)
        Text(filter.tagline, Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), color = Muted, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2)
    }
}
