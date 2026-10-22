package org.droref

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** App settings, shared by the main screen and the floating window. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.applicationContext.getSharedPreferences("droref", Context.MODE_PRIVATE)
    }

    private fun int(k: String, d: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getInt(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) = sp.edit().putInt(k, value).apply()
    }
    private fun bool(k: String, d: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getBoolean(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) = sp.edit().putBoolean(k, value).apply()
    }
    private fun float(k: String, d: Float) = object : ReadWriteProperty<Any?, Float> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getFloat(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) = sp.edit().putFloat(k, value).apply()
    }
    private fun str(k: String, d: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getString(k, d) ?: d
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) = sp.edit().putString(k, value).apply()
    }

    var scene by str("scene", "")
    var prevScene by str("prevScene", "")         // Ctrl+Shift+L goes back to it
    var folders by str("folders", "")             // scene browser folders, one per line (also kept when empty)
    var grid by int("grid", 2)                    // 0 none, 1 lines, 2 dots (default, like VizRef / RefCanvas boards)
    var snap by bool("snap", false)
    var canvasGray by bool("gray", false)
    var canvasLocked by bool("locked", false)
    var normalizeFirst by bool("normalizeFirst", false)

    // settings screen (defaults follow PureRef's)
    var theme by int("theme", 0)                  // 0 dark, 1 light, 2 glass
    var language by str("language", "")           // "" = system
    var undoLimit by int("undoLimit", 200)
    var packNew by bool("packNew", true)
    var packBy by int("packBy", 0)                // 0 optimal, 1 name, 2 addition
    var bringToFront by bool("bringToFront", true)
    var doubleTapFocus by bool("doubleTapFocus", true)
    var borders by int("borders", 0)              // 0 all, 1 selected only, 2 off
    var padding by int("padding", 10)
    var nearest by bool("nearest", false)         // default sampling for new images
    var slideSeconds by int("slideSeconds", 10)
    var slideOrder by int("slideOrder", 0)        // 0 ordered, 1 shuffle, 2 random
    var dragIntoGroups by bool("dragIntoGroups", true)
    var autoParentNotes by bool("autoParentNotes", false)
    var quality by int("quality", 2048)           // longest side kept in memory per image

    // drawing
    var penTool by int("penTool", T_PEN)
    var penColor by int("penColor", 0xFFFF4040.toInt())
    var penWidth by int("penWidth", 4)            // dp
    var penStyle by int("penStyle", S_SOLID)

    // floating window
    var ovX by int("ovX", 40)
    var ovY by int("ovY", 200)
    var ovW by int("ovW", 720)
    var ovH by int("ovH", 900)
    var ovAlpha by float("ovAlpha", 1f)
    var ovBg by int("ovBg", 0)                    // 0 solid, 1 glass, 2 none (overlay mode)
    var ovTarget by str("ovTarget", "")           // "always on top of" this app's package; "" = everywhere

    /** All settings as JSON, for moving them to another device (PureRef 2's settings export). */
    fun exportJson(): String = JSONObject().apply {
        sp.all.filterKeys { it != "scene" && it != "prevScene" && it != "folders" }.forEach { (k, v) ->
            put(k, JSONArray().put(when (v) { is Int -> "i"; is Boolean -> "b"; is Float -> "f"; is Long -> "l"; else -> "s" }).put(v))
        }
    }.toString(2)

    fun importJson(json: String) {
        val o = JSONObject(json)
        val e = sp.edit()
        for (k in o.keys()) {
            if (k == "scene") continue
            val a = o.getJSONArray(k)
            when (a.getString(0)) {
                "i" -> e.putInt(k, a.getInt(1))
                "b" -> e.putBoolean(k, a.getBoolean(1))
                "f" -> e.putFloat(k, a.getDouble(1).toFloat())
                "l" -> e.putLong(k, a.getLong(1))
                else -> e.putString(k, a.getString(1))
            }
        }
        e.apply()
    }

    fun canvasColor() = if (theme == 1) 0xFFE4E4E4.toInt() else 0xFF2B2B2B.toInt()
}

/** This context in the language picked in settings. */
fun Context.localized(): Context {
    Prefs.init(this)
    val tag = Prefs.language
    if (tag.isEmpty()) return this
    val c = Configuration(resources.configuration)
    c.setLocale(Locale.forLanguageTag(tag))
    return createConfigurationContext(c)
}
