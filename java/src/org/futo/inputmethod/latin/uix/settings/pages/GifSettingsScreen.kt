package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import org.futo.inputmethod.latin.uix.actions.GifSettings
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.ScrollableList
import org.futo.inputmethod.latin.uix.settings.Tip

@Composable
fun GifSettingsScreen(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    var key by remember { mutableStateOf(GifSettings.getKey(context)) }
    var saved by remember { mutableStateOf(false) }

    ScrollableList {
        ScreenTitle("GIF search", showBack = true, navController)

        Tip(
            "GIF search uses the Giphy service with your own free key. When you open the GIF panel " +
                    "and search, your search words are sent to Giphy. Nothing else is sent. Only " +
                    "family-friendly (G rated) GIFs are requested, and searches that contain a " +
                    "blocked word are not sent at all."
        )

        Text(
            "How to get a key: open developers.giphy.com in your browser, create a free account, " +
                    "choose Create an App, select API, and copy the key it shows you. Paste it below. " +
                    "Then add the GIF button to your toolbar from the keyboard's toolbar editor.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = key,
            onValueChange = {
                key = it
                saved = false
            },
            label = { Text("Giphy API key") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                GifSettings.setKey(context, key)
                saved = true
            },
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Text("Save key")
        }
        if (saved) {
            Text(
                if (key.isBlank()) "Key removed. GIF search is off." else "Saved.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
