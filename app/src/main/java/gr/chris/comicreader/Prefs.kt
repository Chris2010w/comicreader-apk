package gr.chris.comicreader

import android.content.Context
import android.content.SharedPreferences

class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("comicreader", Context.MODE_PRIVATE)

    var name: String
        get() = sp.getString("name", "") ?: ""
        set(v) { sp.edit().putString("name", v).apply() }

    var folder: String
        get() = sp.getString("folder", "") ?: ""
        set(v) { sp.edit().putString("folder", v).apply() }

    /** true = page-flip animation, false = instant page change */
    var flip: Boolean
        get() = sp.getBoolean("flip", true)
        set(v) { sp.edit().putBoolean("flip", v).apply() }

    var lock: Boolean
        get() = sp.getBoolean("lock", true)
        set(v) { sp.edit().putBoolean("lock", v).apply() }

    val setupDone: Boolean
        get() = name.isNotBlank() && folder.isNotBlank()

    fun getPos(key: String): Int = sp.getInt("pos:$key", 0)

    fun setPos(key: String, page: Int) {
        sp.edit().putInt("pos:$key", page).apply()
    }
}
