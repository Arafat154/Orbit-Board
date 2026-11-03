package org.futo.inputmethod.latin.uix.actions

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.blocklist.BlockedWordsStore
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionTextEditor
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.LocalManager
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Where the user's own GIF service key is kept (on the phone only). */
object GifSettings {
    private const val PREFS = "orbit_gif"
    private const val KEY = "api_key"

    fun getKey(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "")?.trim() ?: ""

    fun setKey(context: Context, value: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value.trim()).apply()
    }
}

data class GifItem(
    val id: String,
    val previewUrl: String,
    val gifUrl: String,
    val pageUrl: String,
    val width: Int,
    val height: Int,
)

/**
 * GIF search using the Giphy API with the person's own key. Only family-friendly ("g") results
 * are requested. Nothing is sent until the person opens this panel and searches.
 */
object GifSearch {
    private const val BASE = "https://api.giphy.com/v1/gifs"

    suspend fun search(apiKey: String, query: String): Result<List<GifItem>> =
        withContext(Dispatchers.IO) {
            val url = if (query.isBlank()) {
                "$BASE/trending?api_key=${enc(apiKey)}&limit=30&rating=g"
            } else {
                "$BASE/search?api_key=${enc(apiKey)}&q=${enc(query.trim())}&limit=30&rating=g&lang=en"
            }
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    val message = when (connection.responseCode) {
                        401, 403 -> "The GIF key was not accepted. Check it in Settings."
                        429 -> "Too many searches. Try again in a minute."
                        else -> "GIF service error (${connection.responseCode})."
                    }
                    return@withContext Result.failure(Exception(message))
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val data = JSONObject(body).getJSONArray("data")
                val items = ArrayList<GifItem>()
                for (i in 0 until data.length()) {
                    val o = data.getJSONObject(i)
                    val images = o.optJSONObject("images") ?: continue
                    val still = images.optJSONObject("fixed_width_still") ?: continue
                    val moving = images.optJSONObject("fixed_width") ?: continue
                    items.add(
                        GifItem(
                            id = o.getString("id"),
                            previewUrl = still.getString("url"),
                            gifUrl = moving.getString("url"),
                            pageUrl = o.optString("url"),
                            width = moving.optString("width").toIntOrNull() ?: 200,
                            height = moving.optString("height").toIntOrNull() ?: 200,
                        )
                    )
                }
                Result.success(items)
            } catch (e: Exception) {
                Result.failure(Exception("Could not reach the GIF service. Check your connection."))
            }
        }

    suspend fun loadPreview(url: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
    }

    /** Saves the animated GIF in the cache so it can be handed to the app being typed in. */
    suspend fun download(context: Context, item: GifItem): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "gifs").apply { mkdirs() }
            // Keep the cache small
            dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(15)?.forEach { it.delete() }
            val file = File(dir, "${item.id}.gif")
            if (!file.exists()) {
                val connection = URL(item.gifUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 15000
                connection.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    fun uriFor(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.gifs", file)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

@Composable
private fun GifThumbnail(item: GifItem, onClick: () -> Unit) {
    var bitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.id) { bitmap = GifSearch.loadPreview(item.previewUrl) }

    Box(
        modifier = Modifier
            .padding(3.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.height(24.dp))
        }
    }
}

val GifSearchAction = Action(
    icon = R.drawable.ic_gif,
    name = R.string.gif_action_title,
    simplePressImpl = null,
    persistentState = null,
    canShowKeyboard = true,
    windowImpl = { _, _ ->
        object : ActionWindow {
            @Composable
            override fun windowName(): String = stringResource(R.string.gif_action_title)

            @Composable
            override fun WindowContents(keyboardShown: Boolean) {
                val context = LocalContext.current
                val manager = LocalManager.current
                val scope = rememberCoroutineScope()
                val apiKey = remember { GifSettings.getKey(context) }

                val query = remember { mutableStateOf("") }
                var loading by remember { mutableStateOf(false) }
                var message by remember { mutableStateOf<String?>(null) }
                var results by remember { mutableStateOf<List<GifItem>>(emptyList()) }

                if (apiKey.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "GIF search needs your own free key. Open Orbit Board settings, then " +
                                    "\"GIF search\", and paste it there.",
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    return
                }

                LaunchedEffect(query.value) {
                    delay(600)
                    val words = query.value.split(Regex("\\s+")).filter { it.isNotEmpty() }
                    if (words.any { BlockedWordsStore.isBlocked(it) }) {
                        results = emptyList()
                        message = "That search is blocked."
                        return@LaunchedEffect
                    }
                    loading = true
                    message = null
                    GifSearch.search(apiKey, query.value)
                        .onSuccess {
                            results = it
                            if (it.isEmpty()) message = "No GIFs found."
                        }
                        .onFailure {
                            results = emptyList()
                            message = it.message
                        }
                    loading = false
                }

                Column(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        ActionTextEditor(query)
                    }
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(8.dp)
                                .height(24.dp)
                                .align(Alignment.CenterHorizontally)
                        )
                    }
                    message?.let {
                        Text(
                            it,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Top,
                    ) {
                        items(results, key = { it.id }) { gif ->
                            GifThumbnail(gif) {
                                scope.launch {
                                    val file = GifSearch.download(context, gif)
                                    val sent = file != null && manager.typeUri(
                                        GifSearch.uriFor(context, file), listOf("image/gif")
                                    )
                                    // Apps that can't take a picture get the link instead
                                    if (!sent && gif.pageUrl.isNotEmpty()) manager.typeText(gif.pageUrl)
                                }
                            }
                        }
                    }
                }
            }

            override fun close() {}
        }
    }
)
