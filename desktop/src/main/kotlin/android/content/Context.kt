package android.content

import android.content.res.AssetManager
import java.io.File
import java.util.Properties

class ContentResolver

interface SharedPreferences {
    fun getString(key: String, def: String?): String?
    fun edit(): Editor
    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun apply()
    }
}

/** Stores preferences in %APPDATA%\GumdropGala (or ~/.gumdropgala on other systems) as a plain properties file. */
internal class FilePrefs(name: String) : SharedPreferences {
    private val file: File
    private val props = Properties()

    init {
        val appData = System.getenv("APPDATA")
        val override = System.getProperty("gumdrop.save.dir")
        val dir = if (!override.isNullOrBlank()) File(override) else if (!appData.isNullOrBlank()) File(appData, "GumdropGala") else File(System.getProperty("user.home"), ".gumdropgala")
        dir.mkdirs()
        file = File(dir, "$name.properties")
        try { if (file.exists()) file.inputStream().use { props.load(it) } } catch (e: Exception) { /* start fresh */ }
    }

    override fun getString(key: String, def: String?): String? = synchronized(this) { props.getProperty(key) ?: def }

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = HashMap<String, String?>()
        override fun putString(key: String, value: String?): SharedPreferences.Editor { pending[key] = value; return this }
        override fun apply() {
            synchronized(this@FilePrefs) {
                for ((k, v) in pending) if (v == null) props.remove(k) else props.setProperty(k, v)
                try {
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.outputStream().use { props.store(it, "Gumdrop Gala save data") }
                    java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } catch (e: Exception) { /* best effort */ }
            }
        }
    }
}

open class Context {
    val assets = AssetManager()
    val contentResolver = ContentResolver()
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = FilePrefs(name)
    fun getSystemService(name: String): Any? = null

    companion object {
        const val MODE_PRIVATE = 0
        const val VIBRATOR_SERVICE = "vibrator"
        const val VIBRATOR_MANAGER_SERVICE = "vibrator_manager"
    }
}
