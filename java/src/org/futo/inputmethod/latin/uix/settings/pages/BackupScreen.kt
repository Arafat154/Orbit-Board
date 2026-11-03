package org.futo.inputmethod.latin.uix.settings.pages

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.uix.backup.BackupManager
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.ScrollableList
import org.futo.inputmethod.latin.uix.settings.Tip

private const val MAX_BACKUP_BYTES = 20_000_000

@Composable
fun BackupScreen(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                message = try {
                    withContext(Dispatchers.IO) {
                        val json = BackupManager.createBackup(context)
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(json.toByteArray(Charsets.UTF_8))
                        } ?: throw IllegalStateException("Could not open the file")
                    }
                    "Backup saved."
                } catch (e: Exception) {
                    "Could not save the backup."
                }
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                message = try {
                    val result = withContext(Dispatchers.IO) {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw IllegalStateException("Could not open the file")
                        require(bytes.size <= MAX_BACKUP_BYTES) { "That file is too big to be a backup." }
                        BackupManager.restore(context, String(bytes, Charsets.UTF_8))
                    }
                    "Restored: ${result.wordsAdded} new word(s) and ${result.themesAdded} new theme(s)."
                } catch (e: IllegalArgumentException) {
                    e.message ?: "That file is not a backup."
                } catch (e: Exception) {
                    "Could not read that backup file."
                }
            }
        }
    }

    ScrollableList {
        ScreenTitle("Backup and restore", showBack = true, navController)

        Tip(
            "A backup holds your blocked words and your custom themes, including their photos, in " +
                    "one file. Keep it somewhere safe: the words inside the file are readable. " +
                    "Restoring only adds to what you have. It never removes words."
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = { saveLauncher.launch("orbitboard-backup.json") }) {
                Text("Save backup")
            }
            OutlinedButton(onClick = { restoreLauncher.launch("*/*") }) {
                Text("Restore backup")
            }
        }

        message?.let {
            Text(
                it,
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
