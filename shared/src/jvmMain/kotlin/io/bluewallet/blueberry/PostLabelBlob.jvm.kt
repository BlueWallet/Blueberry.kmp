package io.bluewallet.blueberry

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

internal actual suspend fun postLabelBlob(
    namespace: String,
    body: String,
): String =
    withContext(Dispatchers.IO) {
        val url = URL("$LABEL_STORE_ORIGIN/namespace/$namespace/$LABEL_STORE_KEY")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Content-Type", "text/plain")
        try {
            conn.outputStream.use { it.write(body.encodeToByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) error("label upload failed: $code")
            conn.inputStream
                .bufferedReader()
                .use { it.readText() }
                .trim()
        } finally {
            conn.disconnect()
        }
    }

internal actual suspend fun getLabelStore(path: String): String =
    withContext(Dispatchers.IO) {
        val url = URL("$LABEL_STORE_ORIGIN/$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("label download failed: $code")
            conn.inputStream
                .bufferedReader()
                .use { it.readText() }
                .trim()
        } finally {
            conn.disconnect()
        }
    }
