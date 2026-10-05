package io.bluewallet.blueberry

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun postLabelBlob(
    namespace: String,
    body: String,
): String {
    return suspendCancellableCoroutine { continuation ->
        val url = NSURL.URLWithString("$LABEL_STORE_ORIGIN/namespace/$namespace/$LABEL_STORE_KEY")
        if (url == null) {
            continuation.resumeWithException(IllegalArgumentException("bad label store url"))
            return@suspendCancellableCoroutine
        }
        val request = NSMutableURLRequest(uRL = url)
        request.setHTTPMethod("POST")
        request.setValue("text/plain", forHTTPHeaderField = "Content-Type")
        val bytes = body.encodeToByteArray()
        request.setHTTPBody(
            bytes.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert())
            },
        )
        val task =
            NSURLSession.sharedSession.dataTaskWithRequest(request) { data, response, error ->
                val code = (response as? NSHTTPURLResponse)?.statusCode
                val seq =
                    (data as? NSData)?.let { bytes ->
                        NSString.create(data = bytes, encoding = NSUTF8StringEncoding)?.toString()?.trim()
                    }
                when {
                    error != null ->
                        continuation.resumeWithException(IllegalStateException(error.localizedDescription))
                    code == null || code !in 200L..299L ->
                        continuation.resumeWithException(IllegalStateException("label upload failed: $code"))
                    seq.isNullOrEmpty() ->
                        continuation.resumeWithException(IllegalStateException("label upload bad sequence"))
                    else -> continuation.resume(seq)
                }
            }
        continuation.invokeOnCancellation { task.cancel() }
        task.resume()
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun getLabelStore(path: String): String =
    suspendCancellableCoroutine { continuation ->
        val url = NSURL.URLWithString("$LABEL_STORE_ORIGIN/$path")
        if (url == null) {
            continuation.resumeWithException(IllegalArgumentException("bad label store url"))
            return@suspendCancellableCoroutine
        }
        val request = NSMutableURLRequest(uRL = url)
        request.setHTTPMethod("GET")
        val task =
            NSURLSession.sharedSession.dataTaskWithRequest(request) { data, response, error ->
                val code = (response as? NSHTTPURLResponse)?.statusCode
                val text =
                    (data as? NSData)?.let { bytes ->
                        NSString.create(data = bytes, encoding = NSUTF8StringEncoding)?.toString()?.trim()
                    }
                when {
                    error != null ->
                        continuation.resumeWithException(IllegalStateException(error.localizedDescription))
                    code == null || code !in 200L..299L ->
                        continuation.resumeWithException(IllegalStateException("label download failed: $code"))
                    text == null ->
                        continuation.resumeWithException(IllegalStateException("label download empty"))
                    else -> continuation.resume(text)
                }
            }
        continuation.invokeOnCancellation { task.cancel() }
        task.resume()
    }
