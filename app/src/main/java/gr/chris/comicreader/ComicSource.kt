   fun close()
}

suspend fun openComic(ctx: Context, c: Comic): ComicSource =
    if (c.isPdf) PdfSource.open(ctx, c.uri) else CbzSource.open(ctx, c.uri)

/** "2.jpg" < "10.jpg" : numbers inside names are compared as numbers. */
fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var si = i
            while (si < a.length && a[si].isDigit()) si++
            var sj = j
            while (sj < b.length && b[sj].isDigit()) sj++
            val na = a.substring(i, si).trimStart('0')
            val nb = b.substring(j, sj).trimStart('0')
            if (na.length != nb.length) return na.length - nb.length
            val c = na.compareTo(nb)
            if (c != 0) return c
            i = si
            j = sj
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}

private fun decodeSampled(open: () -> InputStream, targetWidth: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open().use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return open().use { BitmapFactory.decodeStream(it, null, opts) }
}

private val IMG_EXT = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp")

/**
 * Lightweight thumbnail of the first page (cover).
 * Does NOT copy the whole CBZ to cache — streams the first image only.
 * Size is small (~120–200 px) so the library list stays smooth.
 */
suspend fun loadThumbnail(ctx: Context, comic: Comic, targetWidth: Int = 160): Bitmap? =
    withContext(Dispatchers.IO) {
        try {
            if (comic.isPdf) loadPdfThumbnail(ctx, comic.uri, targetWidth)
            else loadCbzThumbnail(ctx, comic.uri, targetWidth)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

private fun loadPdfThumbnail(ctx: Context, uri: Uri, targetWidth: Int): Bitmap? {
    val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
    return try {
        val renderer = PdfRenderer(pfd)
        try {
            if (renderer.pageCount <= 0) return null
            val page = renderer.openPage(0)
            try {
                val w = targetWidth.coerceIn(80, 400)
                val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            } finally {
                page.close()
            }
        } finally {
            renderer.close()
        }
    } finally {
        try { pfd.close() } catch (_: Exception) { }
    }
}

/** Stream the first image entry from a CBZ/ZIP without copying the whole archive. */
private fun loadCbzThumbnail(ctx: Context, uri: Uri, targetWidth: Int): Bitmap? {
    val input = ctx.contentResolver.openInputStream(uri) ?: return null
    return input.use { stream ->
        ZipInputStream(stream).use { zis ->
            var entry = zis.nextEntry
            val candidates = mutableListOf<Pair<String, ByteArray>>()
            // Collect a few early image entries (we can't seek, so we pick the
            // lexicographically first name after natural sort among the first ones we see).
            // For most CBZs the first entry is already the cover.
            while (entry != null && candidates.size < 40) {
                val name = entry.name
                val l = name.lowercase()
                if (!entry.isDirectory &&
                    !l.startsWith("__macosx") &&
                    !name.substringAfterLast('/').startsWith(".") &&
                    IMG_EXT.any { l.endsWith(it) }
                ) {
                    // Read only a reasonable amount for a thumbnail (max ~4 MB)
                    val bytes = zis.readBytes().takeIf { it.size in 1..(4 * 1024 * 1024) }
                    if (bytes != null) candidates.add(name to bytes)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
            if (candidates.isEmpty()) return null
            // Prefer the naturally first name (same order as the reader)
            val best = candidates.minWithOrNull { a, b -> naturalCompare(a.first, b.first) } ?: return null
            decodeSampled({ best.second.inputStream() }, targetWidth)
        }
    }
}

class CbzSource private constructor(
    private val zip: ZipFile,
    private val names: List<String>
) : ComicSource {
    override val pageCount: Int get() = names.size

    override suspend fun loadPage(index: Int, targetWidth: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (index !in names.indices) return@withContext null
        val entry = zip.getEntry(names[index]) ?: return@withContext null
        decodeSampled({ zip.getInputStream(entry) }, targetWidth)
    }

    override fun close() {
        try { zip.close() } catch (_: Exception) { }
    }

    companion object {
        suspend fun open(ctx: Context, uri: Uri): CbzSource = withContext(Dispatchers.IO) {
            val f = File(ctx.cacheDir, "current.cbz")
            val input = ctx.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Cannot open the file")
            input.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            val zip = try {
                ZipFile(f)
            } catch (e: Exception) {
                throw IllegalStateException("This file is not a valid CBZ/ZIP (CBR/RAR is not supported)")
            }
            val names = zip.entries().asSequence()
                .filter { !it.isDirectory }
                .map { it.name }
                .filter { n ->
                    val l = n.lowercase()
                    !l.startsWith("__macosx") &&
                        !n.substringAfterLast('/').startsWith(".") &&
                        IMG_EXT.any { l.endsWith(it) }
                }
                .sortedWith { a, b -> naturalCompare(a, b) }
                .toList()
            if (names.isEmpty()) {
                zip.close()
                throw IllegalStateException("No images found inside this file")
            }
            CbzSource(zip, names)
        }
    }
}

class PdfSource private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer
) : ComicSource {
    private val lock = Mutex()
    override val pageCount: Int get() = renderer.pageCount

    override suspend fun loadPage(index: Int, targetWidth: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (index !in 0 until renderer.pageCount) return@withContext null
        lock.withLock {
            val page = renderer.openPage(index)
            try {
                val w = targetWidth.coerceIn(400, 2200)
                val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            } finally {
                page.close()
            }
        }
    }

    override fun close() {
        try { renderer.close() } catch (_: Exception) { }
        try { pfd.close() } catch (_: Exception) { }
    }

    companion object {
        suspend fun open(ctx: Context, uri: Uri): PdfSource = withContext(Dispatchers.IO) {
            try {
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("Cannot open the file")
                try {
                    PdfSource(pfd, PdfRenderer(pfd))
                } catch (e: Exception) {
                    pfd.close()
                    throw e
                }
            } catch (e: Exception) {
                // fallback: copy to the app cache (needed when the provider is not seekable)
                val f = File(ctx.cacheDir, "current.pdf")
                val input = ctx.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Cannot open the file")
                input.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                try {
                    PdfSource(pfd, PdfRenderer(pfd))
                } catch (e2: Exception) {
                    pfd.close()
                    throw IllegalStateException("Cannot open this PDF (password-protected or damaged)")
                }
            }
        }
    }
}

/** Keeps decoded pages in memory (about 1/5 of the app's heap). */
class PageCache(private val src: ComicSource, private val width: Int) {
    private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 5).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    val pageCount: Int get() = src.pageCount

    fun peek(i: Int): Bitmap? = cache.get(i)

    suspend fun get(i: Int): Bitmap? {
        if (i < 0 || i >= src.pageCount) return null
        cache.get(i)?.let { return it }
        return try {
            val b = src.loadPage(i, width)
            if (b != null) cache.put(i, b)
            b
        } catch (e: OutOfMemoryError) {
            cache.evictAll()
            null
        } catch (e: Exception) {
            null
        }
    }
}

/** In-memory cache for library thumbnails (shared across recompositions). */
object ThumbnailCache {
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 16).toInt().coerceAtLeast(4 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bmp: Bitmap) {
        cache.put(key, bmp)
    }
}
