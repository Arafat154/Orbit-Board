package org.futo.inputmethod.announcements

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class AnnouncementManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("orbit_board_announcements", Context.MODE_PRIVATE)
    private val inMemoryDismissed = ConcurrentHashMap.newKeySet<String>()

    var endpointUrl: String = DEFAULT_ANNOUNCEMENT_URL

    private val _availableAnnouncement = MutableStateFlow<Announcement?>(null)
    val availableAnnouncement: StateFlow<Announcement?> = _availableAnnouncement.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    fun checkForAnnouncements(
        scope: CoroutineScope,
        forceShow: Boolean = false,
        onResult: ((Result<Announcement?>) -> Unit)? = null
    ) {
        scope.launch {
            _isChecking.value = true
            val announcement = fetchRemoteAnnouncement()
            _isChecking.value = false

            if (announcement != null) {
                val shouldShow = forceShow || (announcement.autoShow && !isDismissed(announcement.id))
                if (shouldShow) {
                    _availableAnnouncement.value = announcement
                } else if (_availableAnnouncement.value?.id == announcement.id) {
                    _availableAnnouncement.value = null
                }
                onResult?.invoke(Result.success(announcement))
            } else {
                _availableAnnouncement.value = null
                onResult?.invoke(Result.failure(Exception("No announcements available")))
            }
        }
    }

    fun dismissAnnouncement(id: String) {
        inMemoryDismissed.add(id)
        prefs.edit().putBoolean("dismissed_$id", true).apply()
        if (_availableAnnouncement.value?.id == id) {
            _availableAnnouncement.value = null
        }
    }

    fun isDismissed(id: String): Boolean {
        if (inMemoryDismissed.contains(id)) return true
        val dismissedInPrefs = prefs.getBoolean("dismissed_$id", false)
        if (dismissedInPrefs) {
            inMemoryDismissed.add(id)
            return true
        }
        return false
    }

    private suspend fun fetchRemoteAnnouncement(): Announcement? = withContext(Dispatchers.IO) {
        fetchFromUrl(endpointUrl)
    }

    private fun fetchFromUrl(urlString: String): Announcement? {
        // Announcements are switched off in this build.
        if (urlString.isNotEmpty()) return null
        return runCatching {
            val url = URL(urlString)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "OrbitBoard/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", "application/json")
            }

            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                AnnouncementParser.parse(body)
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    companion object {
        const val DEFAULT_ANNOUNCEMENT_URL =
            "https://raw.githubusercontent.com/fahitakrim/my-artifacts/main/apps/orbitboard/announcements.json"

        @Volatile
        private var instance: AnnouncementManager? = null

        fun getInstance(context: Context): AnnouncementManager {
            return instance ?: synchronized(this) {
                instance ?: AnnouncementManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
