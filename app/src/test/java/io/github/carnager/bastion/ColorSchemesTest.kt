package io.github.carnager.bastion

import io.github.carnager.bastion.data.ColorSchemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorSchemesTest {
    @Test fun schemesAreWellFormed() {
        val ids = ColorSchemes.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(ColorSchemes.DEFAULT_ID, ColorSchemes.all.first().id)
        ColorSchemes.all.forEach { assertEquals(it.id, 16, it.ansi.size) }
    }

    @Test fun lightAndDarkAreDetected() {
        assertTrue(ColorSchemes.byId("solarized-light")!!.isLight)
        assertTrue(ColorSchemes.byId("paper")!!.isLight)
        assertFalse(ColorSchemes.byId("tokyo-night")!!.isLight)
    }

    @Test fun unknownIdFallsBackToDefault() {
        assertEquals(ColorSchemes.DEFAULT_ID, ColorSchemes.orDefault("gone").id)
    }
}
