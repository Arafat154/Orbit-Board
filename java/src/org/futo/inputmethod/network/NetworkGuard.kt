package org.futo.inputmethod.network

import okhttp3.OkHttpClient
import java.io.IOException

/**
 * This build only uses the internet for two things the user starts themselves: the Translate panel
 * and GIF search. Everything else that used to talk to a server (update checks, announcements,
 * downloads) goes through here or is switched off, so it can never connect.
 */
object NetworkGuard {
    /** An HTTP client that refuses every request. */
    fun blockedClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { throw IOException("Network access is turned off for this feature in this build") }
        .build()
}
