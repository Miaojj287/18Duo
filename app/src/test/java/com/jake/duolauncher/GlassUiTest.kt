package com.jake.duolauncher

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassUiTest {
    @Test fun settings_rows_have_uniform_light_fill_and_no_dark_drop_shadow() {
        for (dark in listOf(false, true)) for (pressed in listOf(false, true)) {
            val spec = duoGlassSpec(DuoGlassRole.SettingsRow, dark, pressed)
            assertEquals(0f, spec.elevation.value, 0f)
            assertEquals(0f, spec.highlightColor.alpha, 0f)
            assertEquals(1f, spec.fallbackColor.red, 0f)
            assertEquals(1f, spec.fallbackColor.green, 0f)
            assertEquals(1f, spec.fallbackColor.blue, 0f)
            assertTrue(spec.fallbackColor.alpha < .26f)
            assertTrue(spec.borderLightColor.alpha <= .22f)
            assertEquals(1f, spec.pressedScale, 0f)
        }
    }
    @Test fun soft_light_backdrop_stays_enabled_during_motion() {
        assertTrue(duoGlassBackdropEnabled())
    }

    @Test fun roles_have_a_small_deliberate_material_scale() {
        val specs = DuoGlassRole.entries.associateWith { duoGlassSpec(it, dark = false) }

        assertTrue(specs.getValue(DuoGlassRole.Dock).blurRadius > specs.getValue(DuoGlassRole.Control).blurRadius)
        assertTrue(specs.getValue(DuoGlassRole.Elevated).blurRadius >= specs.getValue(DuoGlassRole.Card).blurRadius)
        assertTrue(specs.getValue(DuoGlassRole.Screen).fallbackColor.alpha > specs.getValue(DuoGlassRole.Dock).fallbackColor.alpha)
        assertEquals(1f, specs.getValue(DuoGlassRole.WidgetFrame).pressedScale, 0f)
    }

    @Test fun pressed_material_brightens_without_changing_geometry() {
        DuoGlassRole.entries.forEach { role ->
            val resting = duoGlassSpec(role, dark = false)
            val pressed = duoGlassSpec(role, dark = false, pressed = true)

            assertTrue(pressed.tintColor.alpha >= resting.tintColor.alpha)
            assertTrue(pressed.borderLightColor.alpha >= resting.borderLightColor.alpha)
            // iOS 26 glass buttons swell a little when touched instead of sinking.
            assertTrue(pressed.pressedScale in 1f..1.1f)
            assertEquals(resting.pressedScale, pressed.pressedScale)
            assertEquals(resting.blurRadius, pressed.blurRadius)
            assertEquals(resting.elevation, pressed.elevation)
            assertEquals(resting.material.refractionHeight, pressed.material.refractionHeight)
        }
    }

    @Test fun dark_material_has_stronger_fallback_and_light_content() {
        val light = duoGlassSpec(DuoGlassRole.Card, dark = false)
        val dark = duoGlassSpec(DuoGlassRole.Card, dark = true)

        assertTrue(dark.fallbackColor.alpha > light.fallbackColor.alpha)
        assertTrue(dark.contentColor.red > light.contentColor.red)
        assertTrue(dark.contentColor.green > light.contentColor.green)
        assertTrue(dark.contentColor.blue > light.contentColor.blue)
    }

    @Test fun light_glass_layers_are_white_and_never_darken_the_backdrop() {
        DuoGlassRole.entries.forEach { role ->
            val spec = duoGlassSpec(role, dark = false)
            listOf(spec.tintColor, spec.borderLightColor, spec.highlightColor, spec.material.rim)
                .filter { it.alpha > 0f }.forEach { color ->
                    assertEquals(1f, color.red, .001f)
                    assertEquals(1f, color.green, .001f)
                    assertEquals(1f, color.blue, .001f)
                }
            assertTrue(spec.tintColor.alpha <= .25f)
            assertTrue(spec.material.brightness >= 0f)
            assertEquals(0f, spec.noiseFactor, 0f)
        }
    }

    @Test fun liquid_glass_refracts_only_where_it_samples_a_backdrop() {
        DuoGlassRole.entries.forEach { role ->
            val material = duoGlassSpec(role, dark = true).material
            assertTrue(material.refractionHeight.value <= 24f)
            // The bend exceeds the band (Kyant's iOS tuning) but stays within twice it.
            assertTrue(material.refractionAmount.value <= material.refractionHeight.value * 2f)
            assertTrue(material.dispersion in 0f..1f)
            if (material.blur.value == 0f) assertEquals(0f, material.refractionHeight.value, 0f)
            else assertTrue(material.saturation > 1f)
        }
        // Home Screen glass is the clearest material; menus and panels frost more for legibility.
        val dock = duoGlassSpec(DuoGlassRole.Dock, dark = false).material
        assertTrue(dock.blur < duoGlassSpec(DuoGlassRole.Floating, dark = false).material.blur)
        assertTrue(dock.blur < duoGlassSpec(DuoGlassRole.Elevated, dark = false).material.blur)
    }

    @Test fun only_small_controls_swell_on_press() {
        assertTrue(duoGlassSpec(DuoGlassRole.Control, dark = false).pressedScale > 1f)
        assertTrue(duoGlassSpec(DuoGlassRole.Floating, dark = false).pressedScale > 1f)
        listOf(DuoGlassRole.Dock, DuoGlassRole.Card, DuoGlassRole.Elevated, DuoGlassRole.Screen, DuoGlassRole.WidgetFrame)
            .forEach { assertEquals(1f, duoGlassSpec(it, dark = false).pressedScale, 0f) }
    }

    @Test fun press_scale_grows_a_few_points_and_is_capped() {
        assertEquals(1f, glassPressScale(0f, 120f, 8f, 1.08f), 0f)
        // A 44 dp button at 2.75 px/dp grows by 3 dp ≈ 6.8 %.
        assertEquals(1f + 8.25f / 121f, glassPressScale(1f, 121f, 8.25f, 1.08f), 1e-5f)
        // Tiny controls never exceed the cap; a wide pill grows by the same few pixels only.
        assertEquals(1.08f, glassPressScale(1f, 20f, 8.25f, 1.08f), 1e-5f)
        assertTrue(glassPressScale(1f, 600f, 8.25f, 1.08f) < 1.015f)
        assertEquals(1f, glassPressScale(1f, 0f, 8.25f, 1.08f), 0f)
    }

    @Test fun colour_matrix_is_identity_free_and_keeps_greys_grey() {
        assertEquals(null, liquidColorMatrix(1f, 0f, 1f))
        val m = liquidColorMatrix(saturation = 1.6f, brightness = 0f, contrast = 1f)!!
        // A neutral grey input (r = g = b) must stay neutral after a saturation boost.
        val grey = 100f
        val r = m[0] * grey + m[1] * grey + m[2] * grey + m[4]
        val g = m[5] * grey + m[6] * grey + m[7] * grey + m[9]
        val b = m[10] * grey + m[11] * grey + m[12] * grey + m[14]
        assertEquals(grey, r, .01f); assertEquals(grey, g, .01f); assertEquals(grey, b, .01f)
        val brighter = liquidColorMatrix(1f, .04f, 1f)!!
        assertEquals(.04f * 255f, brighter[4], .001f)
        assertEquals(1f, brighter[18], 0f)
    }
}
