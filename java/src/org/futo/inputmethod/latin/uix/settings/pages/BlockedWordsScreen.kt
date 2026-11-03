package org.futo.inputmethod.latin.uix.settings.pages

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
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

    fun askToRemove(entry: BlockedEntry, remove: () -> Unit) {
        pendingRemoval = PendingRemoval(
            title = "Remove \"${entry.word}\"?",
            message = "This word will no longer be blocked. You can confirm after the wait " +
                    "finishes. Take a moment to be sure.",
            confirmLabel = "Remove",
            action = remove,
        )
    }

    val query = search.trim()
    fun matchesSearch(entry: BlockedEntry) =
        query.isEmpty() || entry.word.contains(query, ignoreCase = true)

    val activeBuiltIn = state.builtIn.filter { it.word !in state.removedBuiltIn && matchesSearch(it) }
    val myWords = state.userWords.filter { matchesSearch(it) }

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

        SectionLabel("My words (${state.userWords.size})")
        if (state.userWords.isEmpty()) {
            Tip("You have not added any words yet. Words you add above appear here.")
        } else if (myWords.isEmpty()) {
            Tip("No words match your search.")
        }
        myWords.forEach { entry ->
            WordRow(entry) { askToRemove(entry) { BlockedWordsStore.removeUserWord(entry.word) } }
        }

        SectionLabel("Starter library")
        if (activeBuiltIn.isEmpty()) {
            Tip(if (query.isEmpty()) "No starter words are active." else "No words match your search.")
        }
        activeBuiltIn.forEach { entry ->
            WordRow(entry) { askToRemove(entry) { BlockedWordsStore.removeBuiltInWord(entry.word) } }
        }

        if (state.removedBuiltIn.isNotEmpty()) {
            TextButton(
                onClick = { BlockedWordsStore.restoreBuiltInWords() },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text("Restore ${state.removedBuiltIn.size} removed starter word(s)")
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
private fun WordRow(entry: BlockedEntry, onRemove: () -> Unit) {
    SettingItem(
        title = entry.word,
        subtitle = if (entry.matchInside) "Also blocks longer words starting or ending with it" else null,
    ) {
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Clear, contentDescription = "Remove ${entry.word}")
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
