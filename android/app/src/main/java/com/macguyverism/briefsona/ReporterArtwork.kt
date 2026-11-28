package com.macguyverism.briefsona

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.util.LruCache
import android.util.Xml
import com.caverock.androidsvg.SVG
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringWriter

internal data class Appearance(val id: String, val colors: Map<String, String>, val style: String, val glasses: Boolean) {
    fun json(): JSONObject = JSONObject(colors).put("avatar_id", id).put("clothing_style", style).put("glasses", glasses)
}

internal data class AvatarBase(val id: String, val file: String, val category: String, val styles: List<String>, val defaults: Appearance)

private fun JSONArray.strings() = List(length()) { getString(it) }

internal class ReporterArtwork(context: Context) {
    private val assets = context.applicationContext.assets
    private val manifest = assets.open("reporter_art/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
    val palettes = manifest.getJSONObject("palettes").let { values ->
        values.keys().asSequence().associateWith { values.getJSONArray(it).strings() }
    }
    val bases = manifest.getJSONArray("avatars").let { entries ->
        List(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            val defaults = entry.getJSONObject("defaults")
            val id = entry.getString("id")
            AvatarBase(id, entry.getString("file"), entry.getString("category"), entry.getJSONArray("styles").strings(),
                Appearance(id, palettes.keys.associateWith { defaults.getString(it) }, defaults.getString("clothing_style"), defaults.getBoolean("glasses")))
        }
    }

    fun restore(value: JSONObject, fallback: AvatarBase): Appearance {
        val base = bases.find { it.id == value.optString("avatar_id") } ?: fallback
        return Appearance(base.id, palettes.mapValues { (key, allowed) ->
            value.optString(key).takeIf { it in allowed } ?: base.defaults.colors.getValue(key)
        }, value.optString("clothing_style").takeIf { it in base.styles } ?: base.defaults.style,
            value.optBoolean("glasses", base.defaults.glasses))
    }

    private data class RenderKey(val appearance: Appearance, val pixels: Int)
    private val cache = object : LruCache<RenderKey, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: RenderKey, value: Bitmap) = value.allocationByteCount
    }

    fun bitmap(appearance: Appearance, pixels: Int): Bitmap {
        require(pixels == 128 || pixels == 256)
        val key = RenderKey(appearance, pixels)
        cache.get(key)?.let { return it }
        val base = bases.first { it.id == appearance.id }
        val output = StringWriter()
        val writer = Xml.newSerializer().apply { setOutput(output) }
        assets.open("reporter_art/" + base.file).use { stream ->
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                setInput(stream, null)
            }
            while (parser.nextToken() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.DOCDECL, XmlPullParser.ENTITY_REF -> error("Unexpected entity in avatar")
                    XmlPullParser.START_TAG -> {
                        writer.startTag(null, parser.name)
                        val id = parser.getAttributeValue(null, "id")
                        for (i in 0 until parser.attributeCount) {
                            val name = parser.getAttributeName(i)
                            if (name == "display" && (id in base.styles || id == "glasses")) continue
                            val color = when {
                                name == "stroke" && id == "eyebrows" -> "hair_color"
                                name != "fill" -> null
                                id == "hair" || id == "hair_back" -> "hair_color"
                                id in listOf("background", "skin", "clothing") -> id + "_color"
                                else -> null
                            }
                            writer.attribute(null, name, appearance.colors[color] ?: parser.getAttributeValue(i))
                        }
                        if ((id in base.styles && id != appearance.style) || (id == "glasses" && !appearance.glasses))
                            writer.attribute(null, "display", "none")
                    }
                    XmlPullParser.END_TAG -> writer.endTag(null, parser.name)
                }
            }
        }
        writer.flush()
        val bitmap = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888)
        SVG.getFromString(output.toString()).renderToCanvas(Canvas(bitmap), RectF(0f, 0f, pixels.toFloat(), pixels.toFloat()))
        cache.put(key, bitmap)
        return bitmap
    }
}
