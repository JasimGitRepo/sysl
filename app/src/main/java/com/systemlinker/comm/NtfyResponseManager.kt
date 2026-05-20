package com.systemlinker.comm

import com.systemlinker.base.ConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class NtfyResponseManager(private val configStore: ConfigStore) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun sendResponse(message: String, isCritical: Boolean, verboseFlag: Boolean) {
        if (!isCritical && !verboseFlag) return

        withContext(Dispatchers.IO) {
            try {
                val url = if (configStore.ntfyUrl.startsWith("http")) "${configStore.ntfyUrl.trimEnd('/')}/${configStore.ntfyServerTopic}" 
                          else "https://${configStore.ntfyUrl}/${configStore.ntfyServerTopic.trimEnd('/')}"
                
                val request = Request.Builder()
                    .url(url)
                    .post(message.toRequestBody())
                    .build()

                client.newCall(request).execute().close()
            } catch (e: Exception) {
                // Ignore silent failures on non-critical reporting
            }
        }
    }
}