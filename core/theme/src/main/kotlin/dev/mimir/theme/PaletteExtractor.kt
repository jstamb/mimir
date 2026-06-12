package dev.mimir.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

/** Extracts an AmbientPalette from an art uri (content:// or https://), memoized. */
class PaletteExtractor(private val context: Context) {
    private val cache = LruCache<String, AmbientPalette>(64)

    suspend fun extract(artUri: String?): AmbientPalette {
        if (artUri == null) return AmbientPalette.from(AmbientPalette.FALLBACK_ARGB)
        cache.get(artUri)?.let { return it }
        val palette = withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = loadScaledBitmap(artUri) ?: return@runCatching null
                val p = Palette.from(bitmap).maximumColorCount(16).generate()
                bitmap.recycle()
                AmbientPalette.from(
                    AmbientPalette.choosePrimary(
                        vibrant = p.vibrantSwatch?.rgb,
                        muted = p.mutedSwatch?.rgb,
                        dominant = p.dominantSwatch?.rgb,
                    )
                )
            }.getOrNull()
        } ?: AmbientPalette.from(AmbientPalette.FALLBACK_ARGB)
        cache.put(artUri, palette)
        return palette
    }

    private fun loadScaledBitmap(uri: String): Bitmap? {
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 } // palette doesn't need full res
        return if (uri.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, opts) }
        } else {
            URL(uri).openStream().use { BitmapFactory.decodeStream(it, null, opts) }
        }
    }
}
