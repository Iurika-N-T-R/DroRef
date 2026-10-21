package org.droref

import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import org.json.JSONArray

/** Rich-text note formatting, stored as a JSON list of [type, start, end, value] next to the plain text. */
object Rich {
    const val UNCHECKED = '☐'
    const val CHECKED = '☑'
    const val INDENT = 32

    fun build(text: String, json: String): SpannableStringBuilder {
        val s = SpannableStringBuilder(text)
        if (json.isEmpty()) return s
        val a = runCatching { JSONArray(json) }.getOrNull() ?: return s
        for (i in 0 until a.length()) {
            val o = a.optJSONArray(i) ?: continue
            val st = o.optInt(1).coerceIn(0, text.length)
            val en = o.optInt(2).coerceIn(st, text.length)
            val span = runCatching { span(o.optString(0), o.optString(3)) }.getOrNull() ?: continue
            s.setSpan(span, st, en, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
        }
        return s
    }

    fun span(type: String, v: String): Any? = when (type) {
        "b" -> StyleSpan(Typeface.BOLD)
        "i" -> StyleSpan(Typeface.ITALIC)
        "u" -> UnderlineSpan()
        "s" -> StrikethroughSpan()
        "size" -> RelativeSizeSpan(v.toFloat())
        "fg" -> ForegroundColorSpan(v.toInt())
        "hl" -> BackgroundColorSpan(v.toInt())
        "url" -> URLSpan(v)
        "align" -> AlignmentSpan.Standard(Layout.Alignment.values()[v.toInt()])
        "bullet" -> BulletSpan(16)
        "indent" -> LeadingMarginSpan.Standard(v.toInt() * INDENT)
        "font" -> TypefaceSpan(v)
        else -> null
    }

    /** Our type names for a span ("b", "url", ...); empty for spans we don't store (spell check, composing, ...). */
    fun types(sp: Any): List<String> = when (sp) {
        is StyleSpan -> listOfNotNull("b".takeIf { sp.style and Typeface.BOLD != 0 }, "i".takeIf { sp.style and Typeface.ITALIC != 0 })
        is UnderlineSpan -> if (sp.javaClass == UnderlineSpan::class.java) listOf("u") else emptyList() // not the keyboard's composing underline
        is StrikethroughSpan -> listOf("s")
        is RelativeSizeSpan -> listOf("size")
        is ForegroundColorSpan -> listOf("fg")
        is BackgroundColorSpan -> listOf("hl")
        is URLSpan -> listOf("url")
        is AlignmentSpan -> listOf("align")
        is BulletSpan -> listOf("bullet")
        is LeadingMarginSpan.Standard -> listOf("indent")
        is TypefaceSpan -> if (sp.family != null) listOf("font") else emptyList()
        else -> emptyList()
    }

    private fun value(sp: Any): String = when (sp) {
        is RelativeSizeSpan -> sp.sizeChange.toString()
        is ForegroundColorSpan -> sp.foregroundColor.toString()
        is BackgroundColorSpan -> sp.backgroundColor.toString()
        is URLSpan -> sp.url
        is AlignmentSpan -> sp.alignment.ordinal.toString()
        is TypefaceSpan -> sp.family.orEmpty()
        is LeadingMarginSpan.Standard -> (sp.getLeadingMargin(true) / INDENT).toString()
        else -> ""
    }

    fun toJson(s: Spanned): String {
        val a = JSONArray()
        for (sp in s.getSpans(0, s.length, Any::class.java)) {
            val st = s.getSpanStart(sp)
            val en = s.getSpanEnd(sp)
            if (st == en) continue
            for (t in types(sp)) a.put(JSONArray().put(t).put(st).put(en).put(value(sp)))
        }
        return if (a.length() == 0) "" else a.toString()
    }
}
