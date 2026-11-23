package android.content.res

import java.io.FileNotFoundException
import java.io.InputStream

/** Reads game assets from the classpath (the Android assets folder is packaged as resources on desktop). */
class AssetManager {
    fun open(name: String): InputStream {
        val cl = AssetManager::class.java.classLoader
        // .webp backdrops are shipped as .jpg on desktop (Java has no built-in WebP decoder)
        val candidates = if (name.endsWith(".webp")) listOf(name.removeSuffix(".webp") + ".jpg", name) else listOf(name)
        for (c in candidates) cl.getResourceAsStream(c)?.let { return it }
        throw FileNotFoundException(name)
    }
}
