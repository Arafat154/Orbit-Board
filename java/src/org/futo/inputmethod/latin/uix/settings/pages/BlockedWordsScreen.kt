package org.futo.inputmethod.latin.uix.settings.pages

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import org.futo.inputmethod.latin.blocklist.BlockedEntry
import org.futo.inputmethod.latin.blocklist.BlockedWordsStore
import org.futo.inputmethod.latin.blocklist.BlockedWordsStore.AddResult
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.ScrollableList
import org.futo.inputmethod.latin.uix.settings.SettingItem
import org.futo.inputmethod.latin.uix.settings.SettingToggleRaw
import org.futo.inputmethod.latin.uix.settings.Tip

/** How long the user has to wait before a word can be removed or the filter switched off. */
private const val REMOVAL_WAIT_SECONDS = 30

/** Longest word list file accepted (about 3 MB, hundreds of thousands of words). */
private const val MAX_IMPORT_BYTES = 3_000_000

/** Long lists are shown in part so the screen stays fast; search finds the rest. */
private const val DISPLAY_LIMIT = 100

/** Hides the middle of a word, so a list of explicit words is not readable by accident. */
private fun maskForDisplay(word: String): String {
    val points = word.codePoints().toArray()
    if (points.size <= 2) return "\u2022".repeat(points.size)
    val sb = StringBuilder()
    sb.appendCodePoint(points.first())
    repeat(points.size - 2) { sb.append('\u2022') }
    sb.appendCodePoint(points.last())
    return sb.toString()
}

/** Reads a small text file. Returns null if it is too big or does not look like text. */
private fun readTextFile(context: android.content.Context, uri: Uri): String? {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_IMPORT_BYTES) return null
            out.write(buffer, 0, read)
        }
        val bytes = out.toByteArray()
        if (bytes.any { it == 0.toByte() }) return null
        return String(bytes, Charsets.UTF_8)
    }
    return null
}

private class PendingRemoval(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val action: () -> Unit,
)

@Composable
fun BlockedWordsScreen(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    // Safe to call repeatedly; makes sure the list is loaded even if the keyboard was not started.
    BlockedWordsStore.init(context)

    val state by BlockedWordsStore.state.collectAsState()

    var pendingRemoval by remember { mutableStateOf<PendingRemoval?>(null) }
    var newWord by remember { mutableStateOf("") }
    var matchLonger by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var showWords by remember { mutableStateOf(false) }
    var libraryOpen by remember { mutableStateOf(false) }
    var duplicateWord by remember { mutableStateOf<String?>(null) }
    fun display(word: String) = if (showWords) word else maskForDisplay(word)
    val scope = rememberCoroutineScope()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    try { readTextFile(context, uri) } catch (e: Exception) { null }
                }
                importMessage = if (text == null) {
                    "Could not read that file. Use a plain text (.txt) or .csv file under 3 MB."
                } else {
                    val words = BlockedWordsStore.parseWordList(text)
                    if (words.isEmpty()) {
                        "That file has no words in it."
                    } else {
                        val result = withContext(Dispatchers.Default) {
                            BlockedWordsStore.addWords(words, matchLonger)
                        }
                        buildString {
                            append("Added ${result.added} word(s).")
                            if (result.alreadyThere > 0) append(" ${result.alreadyThere} were already in the list.")
                            if (result.skipped > 0) append(" ${result.skipped} were skipped (too short or over the limit).")
                        }
                    }
                }
            }
        }
    }

    pendingRemoval?.let { pending ->
        WaitThenConfirmDialog(
            title = pending.title,
            message = pending.message,
            confirmLabel = pending.confirmLabel,
            waitSeconds = REMOVAL_WAIT_SECONDS,
            onConfirm = {
                pending.action()
                pendingRemoval = null
            },
            onDismiss = { pendingRemoval = null },
        )
    }

    duplicateWord?.let { word ->
        AlertDialog(
            onDismissRequest = { duplicateWord = null },
            title = { Text("Already in your list") },
            text = { Text("\"${if (showWords) word else maskForDisplay(word)}\" is already blocked, so it was not added again.") },
            confirmButton = { TextButton(onClick = { duplicateWord = null }) { Text("OK") } },
        )
    }

    fun askToRemove(entry: BlockedEntry, remove: () -> Unit) {
        pendingRemoval = PendingRemoval(
            title = "Remove \"${display(entry.word)}\"?",
            message = "This word will no longer be blocked. You can confirm after the wait " +
                    "finishes. Take a moment to be sure.",
            confirmLabel = "Remove",
            action = remove,
        )
    }

    val query = search.trim()
    fun matchesSearch(entry: BlockedEntry) =
        query.isEmpty() || entry.word.contains(query, ignoreCase = true)

    val allWords = (state.builtIn.filter { it.word !in state.removedBuiltIn } + state.userWords)
    val shownWords = allWords.filter { matchesSearch(it) }

    ScrollableList {
        ScreenTitle("Blocked words", showBack = true, navController)

        Tip(
            "Blocked words are replaced with *** whenever you type, paste or dictate with this " +
                    "keyboard. They are also never suggested. Everything stays on your phone."
        )

        SettingToggleRaw(
            title = "Block these words",
            subtitle = if (state.enabled) {
                "On. Turning this off needs a $REMOVAL_WAIT_SECONDS-second wait."
            } else {
                "Off. Blocked words are typed normally."
            },
            enabled = state.enabled,
            setValue = { wantsOn ->
                if (wantsOn) {
                    BlockedWordsStore.setEnabled(true)
                } else {
                    pendingRemoval = PendingRemoval(
                        title = "Turn off word blocking?",
                        message = "Blocked words will be typed normally again. You can confirm " +
                                "after the wait finishes. Take a moment to be sure.",
                        confirmLabel = "Turn off",
                        action = { BlockedWordsStore.setEnabled(false) },
                    )
                }
            },
        )

        SectionLabel("Add a word")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newWord,
                onValueChange = {
                    newWord = it
                    addError = null
                },
                modifier = Modifier.weight(1f),
                label = { Text("Word to block") },
                singleLine = true,
                isError = addError != null,
            )
            IconButton(onClick = {
                val result = BlockedWordsStore.addWord(newWord, matchLonger)
                if (result == AddResult.ADDED) {
                    newWord = ""
                    addError = null
                } else if (result == AddResult.DUPLICATE) {
                    duplicateWord = newWord.trim()
                    addError = null
                } else {
                    addError = when (result) {
                        AddResult.EMPTY -> "Type a word first."
                        AddResult.HAS_SPACES -> "Add one word at a time (no spaces)."
                        AddResult.TOO_SHORT -> "That word is too short."
                        AddResult.TOO_SHORT_FOR_INSIDE ->
                            "Use at least 3 letters when blocking longer words too."
                        AddResult.DUPLICATE -> "That word is already in the list."
                        AddResult.ADDED -> null
                    }
                }
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add word")
            }
        }
        addError?.let {
            Text(
                it,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = matchLonger, onCheckedChange = { matchLonger = it })
            Text(
                "Also block longer words that start or end with it (for example \"porn\" also " +
                        "blocks \"pornhub\")",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        OutlinedButton(
            onClick = { filePicker.launch("*/*") },
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Text("Import words from a file")
        }
        Text(
            "Choose a .txt or .csv file with one word per line (commas also work). The box " +
                    "above decides whether imported words also block longer words. Spreadsheets " +
                    "(.xlsx) must be saved as .csv first.",
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        importMessage?.let {
            Text(
                it,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            label = { Text("Search the library") },
            singleLine = true,
        )

        SectionLabel("Library (${allWords.size} word${if (allWords.size == 1) "" else "s"})")
        val open = libraryOpen || query.isNotEmpty()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { libraryOpen = !libraryOpen }) {
                Text(if (open) "Hide library" else "Open library")
            }
            Spacer(Modifier.weight(1f))
            Text("Show words", style = MaterialTheme.typography.bodySmall)
            Checkbox(checked = showWords, onCheckedChange = { showWords = it })
        }
        if (allWords.isEmpty()) {
            Tip("Your list is empty. Add a word above or import a file. Nothing is blocked until you do.")
        } else if (!open) {
            Tip("The library is closed. Words are hidden here so nobody sees them by accident.")
        } else {
            if (shownWords.isEmpty()) Tip("No words match your search.")
            shownWords.take(DISPLAY_LIMIT).forEach { entry ->
                val isUser = entry in state.userWords
                WordRow(entry, display(entry.word)) {
                    askToRemove(entry) {
                        if (isUser) BlockedWordsStore.removeUserWord(entry.word)
                        else BlockedWordsStore.removeBuiltInWord(entry.word)
                    }
                }
            }
            if (shownWords.size > DISPLAY_LIMIT) {
                Tip("Showing $DISPLAY_LIMIT of ${shownWords.size} words. Use the search box to find others.")
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun WordRow(entry: BlockedEntry, label: String, onRemove: () -> Unit) {
    SettingItem(
        title = label,
        subtitle = if (entry.matchInside) "Also blocks longer words starting or ending with it" else null,
    ) {
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Clear, contentDescription = "Remove word")
        }
    }
}

/**
 * A confirmation that cannot be tapped until the wait has finished. Leaving the app or closing
 * the dialog cancels it, so the wait starts over next time.
 */
@Composable
private fun WaitThenConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    waitSeconds: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var secondsLeft by remember { mutableStateOf(waitSeconds) }
    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft -= 1
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                if (secondsLeft > 0) {
                    Text(
                        "Please wait $secondsLeft second(s)...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = secondsLeft == 0) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep it blocked") }
        },
    )
}
