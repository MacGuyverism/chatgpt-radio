package com.macguyverism.briefsona

import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReporterArtworkTest {
    private val art = ReporterArtwork(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun bundledPortraitsRenderAtBothSizesAndPreserveTransparency() {
        assertEquals(20, art.bases.size)
        for (base in art.bases) {
            val thumbnail = art.bitmap(base.defaults, 128)
            assertEquals(0, Color.alpha(thumbnail.getPixel(0, 0)))
            assertEquals(255, Color.alpha(thumbnail.getPixel(64, 64)))
            val styles = base.styles.map { style ->
                val appearance = base.defaults.copy(style = style, glasses = false)
                val plain = art.bitmap(appearance, 256)
                assertEquals(0, Color.alpha(plain.getPixel(16, 240)))
                assertEquals(Color.parseColor(appearance.colors.getValue("background_color")), plain.getPixel(40, 120))
                assertFalse(plain.sameAs(art.bitmap(appearance.copy(glasses = true), 256)))
                plain
            }
            for (i in styles.indices) for (j in 0 until i) assertFalse(styles[i].sameAs(styles[j]))
        }
    }

    @Test fun paletteEditsRecolorThePortraitAndOldPreviewsAreEvicted() {
        val base = art.bases.first { it.category == "female" }.defaults
        val first = art.bitmap(base, 256)
        assertSame(first, art.bitmap(base, 256))
        for ((field, palette) in art.palettes) {
            for (hex in palette.filter { it != base.colors[field] }) {
                val edited = art.bitmap(base.copy(colors = base.colors + (field to hex)), 256)
                assertFalse("$field $hex", first.sameAs(edited))
            }
        }
        assertNotSame(first, art.bitmap(base, 256))
    }

    @Test fun invalidAndLegacyFieldsUseBaseDefaults() {
        val base = art.bases.first()
        assertEquals(base.defaults, art.restore(JSONObject(), base))
        val invalid = JSONObject().put("avatar_id", base.id).put("hair_color", "bad").put("clothing_style", "blouse")
        assertEquals(base.defaults, art.restore(invalid, base))
        art.bases.forEach { item ->
            val edited = item.defaults.copy(glasses = true, style = item.styles.last(),
                colors = art.palettes.mapValues { it.value.last() })
            assertEquals(edited, art.restore(edited.json(), base))
        }
    }
}
