package org.futo.inputmethod.updates

import android.content.Context
import org.futo.inputmethod.latin.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdate(
    val versionName: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val isUpdateAvailable: Boolean
)

object UpdateChecker {
    const val RAW_UPDATE_URL = "https://raw.githubusercontent.com/fahitakrim/my-artifacts/main/apps/orbitboard/update.json"
    const val GITHUB_API_URL = "https://api.github.com/repos/fahitakrim/my-artifacts/releases?per_page=15"

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val _latestUpdate = MutableStateFlow<AppUpdate?>(null)
    val latestUpdate: StateFlow<AppUpdate?> = _latestUpdate.asStateFlow()

    suspend fun checkForUpdate(currentVersion: String = BuildConfig.VERSION_NAME): Result<AppUpdate> = withContext(Dispatchers.IO) {
        // Update checks are switched off in this build (it only uses the internet for Translate and GIF search).
        return@withContext Result.failure<AppUpdate>(IllegalStateException("Update checks are turned off"))
        _isChecking.value = true
        try {
            // 1. Try dedicated raw JSON endpoint
            val rawResult = runCatching { fetchFromRawJson(RAW_UPDATE_URL, currentVersion) }
            val rawUpdate = rawResult.getOrNull()
            if (rawUpdate != null) {
                _latestUpdate.value = rawUpdate
                return@withContext Result.success(rawUpdate)
            }

            // 2. Fallback to GitHub Releases API with strict filtering for Orbit Board
            val releasesResult = runCatching { fetchFromReleasesApi(GITHUB_API_URL, currentVersion) }
            val releasesUpdate = releasesResult.getOrNull()
            if (releasesUpdate != null) {
                _latestUpdate.value = releasesUpdate
                return@withContext Result.success(releasesUpdate)
            }

            // If neither returned an update, report that app is on current version (up to date)
            val upToDate = AppUpdate(
                versionName = currentVersion,
                releaseTitle = "Orbit Board",
                releaseNotes = "Already on latest version.",
                downloadUrl = "",
                isUpdateAvailable = false
            )
            _latestUpdate.value = upToDate
            Result.success(upToDate)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _isChecking.value = false
        }
    }

    private fun fetchFromRawJson(urlString: String, currentVersion: String): AppUpdate? {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("User-Agent", "OrbitBoard/$currentVersion")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            if (body.isBlank() || !body.startsWith("{")) return null
            val json = JSONObject(body)
            val latestVer = json.optString("latestVersion", "").ifBlank { json.optString("versionName", "") }
            if (latestVer.isBlank()) return null
            val downloadUrl = json.optString("downloadUrl", "")
            val changelogArr = json.optJSONArray("changelog")
            val notes = if (changelogArr != null && changelogArr.length() > 0) {
                (0 until changelogArr.length()).joinToString("\n• ", prefix = "• ") { changelogArr.optString(it) }
            } else {
                json.optString("releaseNotes", "Bug fixes and performance improvements.")
            }
            val title = json.optString("title", "Orbit Board v$latestVer (Stable)")
            val isAvailable = isNewerVersion(remote = latestVer, current = currentVersion)
            AppUpdate(
                versionName = latestVer,
                releaseTitle = title,
                releaseNotes = notes,
                downloadUrl = downloadUrl,
                isUpdateAvailable = isAvailable
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchFromReleasesApi(urlString: String, currentVersion: String): AppUpdate? {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("User-Agent", "OrbitBoard/$currentVersion")
            setRequestProperty("Accept", "application/vnd.github.v3+json")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val responseBody = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            if (responseBody.isBlank()) return null

            val targetRelease: JSONObject = if (responseBody.startsWith("[")) {
                val array = JSONArray(responseBody)
                var matchedObj: JSONObject? = null
                for (i in 0 until array.length()) {
                    val rel = array.optJSONObject(i) ?: continue
                    val tag = rel.optString("tag_name", "").lowercase()
                    val relTitle = rel.optString("name", "").lowercase()
                    val assets = rel.optJSONArray("assets")
                    var hasKbdApk = false
                    if (assets != null) {
                        for (j in 0 until assets.length()) {
                            val assetName = assets.optJSONObject(j)?.optString("name", "")?.lowercase() ?: ""
                            if ((assetName.contains("orbit-board") || assetName.contains("orbitboard") ||
                                assetName.contains("orbit_board") || assetName.contains("nth-kbd")) && assetName.endsWith(".apk")) {
                                hasKbdApk = true
                                break
                            }
                        }
                    }
                    if (hasKbdApk || relTitle.contains("orbit-board") || relTitle.contains("orbit board") ||
                        relTitle.contains("orbitboard") || tag.contains("orbit-board") || tag.contains("orbitboard")) {
                        matchedObj = rel
                        break
                    }
                }
                // Never fall back to index 0 (which may be Orbit Music)
                matchedObj ?: return null
            } else {
                val singleObj = JSONObject(responseBody)
                val relTitle = singleObj.optString("name", "").lowercase()
                val tag = singleObj.optString("tag_name", "").lowercase()
                if (relTitle.contains("orbit-board") || relTitle.contains("orbit board") ||
                    relTitle.contains("orbitboard") || tag.contains("orbit-board") || tag.contains("orbitboard")) {
                    singleObj
                } else {
                    return null
                }
            }

            val tagName = targetRelease.optString("tag_name", "").trim()
            val releaseName = targetRelease.optString("name", tagName).ifBlank { tagName }
            val rawBody = targetRelease.optString("body", "Bug fixes and performance improvements.").ifBlank {
                "Bug fixes and performance improvements."
            }
            val body = cleanReleaseNotes(rawBody)
            val htmlUrl = targetRelease.optString("html_url", "https://github.com/fahitakrim/my-artifacts/releases")

            var downloadUrl = htmlUrl
            val assets = targetRelease.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i)
                    val assetName = asset?.optString("name", "") ?: ""
                    if ((assetName.contains("orbit-board", ignoreCase = true) ||
                         assetName.contains("orbitboard", ignoreCase = true) ||
                         assetName.contains("nth-kbd", ignoreCase = true)) &&
                        assetName.endsWith(".apk", ignoreCase = true)) {
                        val apkUrl = asset.optString("browser_download_url", "")
                        if (apkUrl.isNotBlank()) {
                            downloadUrl = apkUrl
                            break
                        }
                    }
                }
            }

            val parsedVersion = extractVersion(tagName).ifBlank { extractVersion(releaseName) }
            val isAvailable = isNewerVersion(remote = parsedVersion, current = currentVersion)

            AppUpdate(
                versionName = if (parsedVersion.isNotBlank()) parsedVersion else tagName,
                releaseTitle = releaseName,
                releaseNotes = body,
                downloadUrl = downloadUrl,
                isUpdateAvailable = isAvailable
            )
        } finally {
            conn.disconnect()
        }
    }

    fun cleanReleaseNotes(raw: String): String {
        return raw
            .replace(Regex("""^#+\s*""", RegexOption.MULTILINE), "")
            .replace("**", "")
            .replace("__", "")
            .trim()
    }

    private val VERSION_REGEX = Regex("""(\d+(?:\.\d+)+)""")

    fun extractVersion(raw: String): String {
        return VERSION_REGEX.find(raw)?.value ?: raw.filter { it.isDigit() || it == '.' }.trim('.')
    }

    fun isNewerVersion(remote: String, current: String): Boolean {
        val cleanRemote = extractVersion(remote)
        val cleanCurrent = extractVersion(current)
        if (cleanRemote.isBlank() || cleanCurrent.isBlank()) return false

        val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }
        if (remoteParts.isEmpty() || currentParts.isEmpty()) return false

        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }
}
