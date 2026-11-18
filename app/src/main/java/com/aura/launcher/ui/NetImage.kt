package com.aura.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.http.HttpResponseCache
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Small image loader for the news feed: downloads once (HTTP disk cache), decodes at roughly
 * the size it's shown (so a 2,000px photo doesn't cost 16 MB of memory), and keeps recent
 * pictures in memory.
 */
object NetImages {
    private const val MEMORY_BYTES = 24 * 1024 * 1024

    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** Installs a 40 MB HTTP cache for images and feeds, once per process. */
    fun installDiskCache(context: Context) {
        if (HttpResponseCache.getInstalled() == null) {
            runCatching { HttpResponseCache.install(File(context.cacheDir, "http"), 40L * 1024 * 1024) }
        }
    }

    fun cached(url: String): ImageBitmap? = memory.get(url)

    /** For tests and previews: place a ready-made picture in the cache. */
    fun put(url: String, bitmap: Bitmap) { memory.put(url, bitmap.asImageBitmap()) }

    suspend fun load(url: String, targetWidthPx: Int): ImageBitmap? = memory.get(url) ?: withContext(Dispatchers.IO) {
        runCatching {
            val bytes = download(url) ?: return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetWidthPx) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
        }.getOrNull()?.also { memory.put(url, it) }
    }

    private fun download(url: String): ByteArray? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12_000
            useCaches = true
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AURA-Launcher")
        }
        return try {
            if (conn.responseCode !in 200..299) null else conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}

/** A remote picture that fades in when ready; shows nothing (the card background) until then. */
@Composable
fun NetImage(
    url: String,
    targetWidthPx: Int,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    fallbackUrl: String? = null,
) {
    val bitmap by produceState(NetImages.cached(url) ?: fallbackUrl?.let(NetImages::cached), url, fallbackUrl) {
        if (value == null) value = NetImages.load(url, targetWidthPx) ?: fallbackUrl?.let { NetImages.load(it, targetWidthPx) }
    }
    val alpha by animateFloatAsState(if (bitmap != null) 1f else 0f, tween(260), label = "imageFade")
    bitmap?.let {
        Image(
            it, contentDescription = contentDescription, contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxSize().alpha(alpha),
        )
    }
}
