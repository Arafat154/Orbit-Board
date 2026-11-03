package org.futo.inputmethod.latin.uix.settings.pages

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.uix.THEME_KEY
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.ScrollableList
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.uix.theme.custom.CustomThemeStore
import android.graphics.Color as AColor

private const val DEFAULT_BACKGROUND = 0xFF101418.toInt()
private const val DEFAULT_TEXT = 0xFFEAEAEA.toInt()
private const val DEFAULT_ACCENT = 0xFF4F9DFF.toInt()

/** Create or edit one custom theme: three colours and an optional photo behind the keys. */
@Composable
fun CustomThemeScreen(navController: NavHostController = rememberNavController(), themeId: String = "new") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    CustomThemeStore.init(context)

    val existing = remember(themeId) {
        CustomThemeStore.themes.value.firstOrNull { it.id == themeId }
    }
    val (currentThemeKey, setThemeKey) = useDataStore(THEME_KEY.key, THEME_KEY.default)

    var name by remember { mutableStateOf(existing?.name ?: "My theme") }
    var background by remember { mutableStateOf(existing?.background ?: DEFAULT_BACKGROUND) }
    var textColor by remember { mutableStateOf(existing?.text ?: DEFAULT_TEXT) }
    var accent by remember { mutableStateOf(existing?.accent ?: DEFAULT_ACCENT) }
    var dim by remember { mutableStateOf(existing?.dim ?: 0.4f) }

    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var photoIsNew by remember { mutableStateOf(false) }
    var photoRemoved by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Show the photo already saved with this theme
    LaunchedEffect(existing?.id) {
        if (existing != null && existing.hasPhoto) {
            photo = withContext(Dispatchers.IO) { CustomThemeStore.loadPhoto(context, existing) }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) { CustomThemeStore.decodePhoto(context, uri) }
                if (bitmap != null) {
                    photo = bitmap
                    photoIsNew = true
                    photoRemoved = false
                } else {
                    Toast.makeText(context, "Could not read that picture.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${existing.name}\"?") },
            text = { Text("This theme and its photo will be removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (CustomThemeStore.find(context, currentThemeKey)?.id == existing.id) {
                        setThemeKey(THEME_KEY.default)
                    }
                    CustomThemeStore.delete(context, existing.id)
                    navController.popBackStack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    ScrollableList {
        ScreenTitle(if (existing == null) "New theme" else "Edit theme", showBack = true, navController)

        Tip("Pick your colours and, if you like, a photo from your gallery. The photo stays on your phone.")

        ThemeSample(background, textColor, accent, photo, dim)

        val contrast = ColorUtils.calculateContrast(textColor, ColorUtils.compositeColors(background, DEFAULT_BACKGROUND))
        if (photo == null && contrast < 4.5) {
            Text(
                "These colours are hard to tell apart. Try making the text lighter or darker than the background.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(30) },
            label = { Text("Theme name") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        ColorEditor("Background", background) { background = it }
        ColorEditor("Letters and icons", textColor) { textColor = it }
        ColorEditor("Accent (enter key, highlights)", accent) { accent = it }

        Text(
            "Photo",
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 4.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { picker.launch("image/*") }) {
                Text(if (photo == null) "Choose photo" else "Change photo")
            }
            if (photo != null) {
                TextButton(onClick = {
                    photo = null
                    photoIsNew = false
                    photoRemoved = true
                }) { Text("Remove photo") }
            }
        }
        if (photo != null) {
            Text(
                "Photo visibility",
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                // Slider shows how much photo is visible, which is the opposite of "dim".
                value = 1f - dim,
                onValueChange = { dim = (1f - it).coerceIn(0f, 0.85f) },
                valueRange = 0.15f..1f,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                "Lower it if the letters are hard to read on top of the picture.",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(16.dp))
        Button(
            enabled = !saving,
            onClick = {
                saving = true
                scope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        CustomThemeStore.save(
                            context = context,
                            existing = existing,
                            name = name,
                            background = background,
                            text = textColor,
                            accent = accent,
                            dim = dim,
                            newPhoto = if (photoIsNew) photo else null,
                            keepPhoto = !photoRemoved && photo != null,
                        )
                    }
                    // Switching to it also refreshes the keyboard when editing the active theme
                    setThemeKey(saved.key)
                    navController.popBackStack()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(if (existing == null) "Save and use this theme" else "Save changes")
        }

        if (existing != null) {
            TextButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text("Delete this theme", color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** A tiny keyboard so the person sees the result while choosing colours. */
@Composable
private fun ThemeSample(background: Int, text: Int, accent: Int, photo: Bitmap?, dim: Float) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .height(150.dp)
            .clip(shape)
            .background(Color(background)),
    ) {
        if (photo != null) {
            Image(
                bitmap = photo.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(background).copy(alpha = dim))
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val keyColor = Color(text).copy(alpha = 0.14f)
            repeat(2) { row ->
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val letters = if (row == 0) "Q W E R T" else "A S D F G"
                    letters.split(" ").forEach { letter ->
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp))
                                .background(keyColor),
                            contentAlignment = Alignment.Center,
                        ) { Text(letter, color = Color(text)) }
                    }
                }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .weight(3f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(keyColor),
                    contentAlignment = Alignment.Center,
                ) { Text("space", color = Color(text).copy(alpha = 0.6f)) }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(accent)),
                    contentAlignment = Alignment.Center,
                ) {
                    val onAccent = if (ColorUtils.calculateLuminance(accent) > 0.5) Color.Black else Color.White
                    Text("↵", color = onAccent)
                }
            }
        }
    }
}

private fun toHex(color: Int): String = String.format("%06X", color and 0xFFFFFF)

/** Hue / saturation / brightness sliders plus a hex box for one colour. */
@Composable
private fun ColorEditor(label: String, color: Int, onColorChange: (Int) -> Unit) {
    var hsv by remember { mutableStateOf(FloatArray(3).also { AColor.colorToHSV(color, it) }) }
    var hex by remember { mutableStateOf(toHex(color)) }

    fun applyHsv(next: FloatArray) {
        hsv = next
        val c = AColor.HSVToColor(next)
        hex = toHex(c)
        onColorChange(c)
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(color or 0xFF000000.toInt()))
            )
            Text(
                label,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                style = MaterialTheme.typography.titleSmall,
            )
            OutlinedTextField(
                value = hex,
                onValueChange = { input ->
                    val cleaned = input.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                    hex = cleaned
                    if (cleaned.length == 6) {
                        val parsed = (0xFF000000.toInt() or cleaned.toInt(16))
                        val next = FloatArray(3).also { AColor.colorToHSV(parsed, it) }
                        hsv = next
                        onColorChange(parsed)
                    }
                },
                prefix = { Text("#") },
                singleLine = true,
                modifier = Modifier.width(132.dp),
            )
        }
        Text("Shade", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = hsv[0],
            onValueChange = { applyHsv(hsv.copyOf().also { a -> a[0] = it }) },
            valueRange = 0f..360f,
        )
        Text("Colourfulness", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = hsv[1],
            onValueChange = { applyHsv(hsv.copyOf().also { a -> a[1] = it }) },
            valueRange = 0f..1f,
        )
        Text("Brightness", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = hsv[2],
            onValueChange = { applyHsv(hsv.copyOf().also { a -> a[2] = it }) },
            valueRange = 0f..1f,
        )
    }
}
