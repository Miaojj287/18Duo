/*
   Portions copyright 2025 Kyant (AndroidLiquidGlass / backdrop, https://github.com/Kyant0/AndroidLiquidGlass)

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

   Modified for 18Duo: corner lookup uses centred coordinates (per-corner radii work), the 7-tap
   spectral dispersion became a bounded 3-tap RGB split (same corner weighting), and the specular rim shader is new.
 */

package com.jake.duolauncher

import org.intellij.lang.annotations.Language

/** Rounded-rect signed distance and its gradient; continuous corners are approximated by it. */
@Language("AGSL")
private const val ROUNDED_RECT_SDF = """
float radiusAt(float2 centered, float4 radii) {
    if (centered.x >= 0.0) {
        return centered.y <= 0.0 ? radii.y : radii.z;
    }
    return centered.y <= 0.0 ? radii.x : radii.w;
}

float sdRoundedRect(float2 p, float2 halfSize, float radius) {
    float2 q = abs(p) - (halfSize - float2(radius));
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

float2 gradSdRoundedRect(float2 p, float2 halfSize, float radius) {
    float2 q = abs(p) - (halfSize - float2(radius));
    if (q.x >= 0.0 || q.y >= 0.0) {
        return sign(p) * normalize(max(q, 0.0) + float2(1e-4));
    }
    float gx = step(q.y, q.x);
    return sign(p) * float2(gx, 1.0 - gx);
}
"""

/**
 * Lens refraction near the rim: inside a band of [refractionHeight] the backdrop is sampled
 * displaced along the edge normal following a circular profile, so content bends at the edge
 * like a thick glass slab. Interior pixels take the early exit (one sample). The optional RGB
 * split costs at most three samples, only inside the band.
 */
@Language("AGSL")
internal const val LIQUID_LENS_SHADER = """
uniform shader content;
uniform float2 size;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float dispersion;
uniform float edgeLight;
$ROUNDED_RECT_SDF

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 p = coord - halfSize;
    float radius = radiusAt(p, cornerRadii);
    float sd = sdRoundedRect(p, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);
    float d = circleMap(1.0 - (-sd / refractionHeight)) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(p, halfSize, gradRadius) + depthEffect * normalize(p + float2(1e-4)));
    float2 offset = d * grad;
    // The bevel gathers light: what shows through it brightens in its own colour (iOS 26 folders glow
    // orange over a fire wallpaper), strongest at the rim.
    float t = 1.0 - (-sd / refractionHeight);
    half4 base = content.eval(coord + offset);
    // Hue-preserving: a light colour reaches its brightest shade, never clips to a white halo.
    half peak = max(max(base.r, base.g), max(base.b, 0.02));
    // Never below 1: vibrancy can push a channel past 1 and the band must not come out darker than the
    // interior (a visible inner rectangle on the emulator, round 16).
    half gain = max(min(half(1.0 + edgeLight * t * t * t), 1.0 / peak), 1.0);
    base.rgb *= gain;
    // Kyant's weighting: the split grows toward the corners (x·y) and vanishes mid-edge, so a capsule
    // never gets a red/green outline along its long sides, only a faint prism at its ends.
    float spread = dispersion * clamp(abs(p.x * p.y) / (halfSize.x * halfSize.y), 0.0, 1.0);
    if (spread <= 0.002) {
        return base;
    }
    half4 red = content.eval(coord + offset * (1.0 + spread));
    half4 blue = content.eval(coord + offset * (1.0 - spread));
    return half4(red.r * gain, base.g, blue.b * gain, base.a);
}
"""

/**
 * Specular rim: the light falls from [lightAngle] (top-left on iOS), so the rim facing it is
 * brightest, the opposite rim catches a softer reflection and the sides keep [ambient].
 */
@Language("AGSL")
internal const val LIQUID_RIM_SHADER = """
uniform float2 size;
uniform float4 cornerRadii;
uniform float2 lightDirection;
layout(color) uniform half4 color;
uniform float ambient;
uniform float falloff;
$ROUNDED_RECT_SDF

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 p = coord - halfSize;
    float radius = radiusAt(p, cornerRadii);
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 n = gradSdRoundedRect(p, halfSize, gradRadius);
    float facing = dot(n, lightDirection);
    // Liquid Glass catches the light on the rim facing it and, almost as brightly, on the opposite
    // rim (the light passing through the slab): two lit corners, dim sides (Kyant's Highlight.Default).
    float lit = pow(max(facing, 0.0), falloff) + 0.8 * pow(max(-facing, 0.0), falloff);
    float intensity = ambient + (1.0 - ambient) * min(lit, 1.0);
    return color * intensity;
}
"""

/**
 * Light caught inside the slab along its bevel (iOS 26 folder and icon glass): a soft band of added
 * light [width] deep, strongest on the rims facing [lightDirection] and its opposite, fading inward.
 */
@Language("AGSL")
internal const val LIQUID_EDGE_GLOW_SHADER = """
uniform float2 size;
uniform float4 cornerRadii;
uniform float2 lightDirection;
layout(color) uniform half4 color;
uniform float ambient;
uniform float width;
$ROUNDED_RECT_SDF

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 p = coord - halfSize;
    float radius = radiusAt(p, cornerRadii);
    float inside = -sdRoundedRect(p, halfSize, radius);
    if (inside <= 0.0 || inside >= width) {
        return half4(0.0);
    }
    float band = 1.0 - inside / width;
    band = band * band;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 n = gradSdRoundedRect(p, halfSize, gradRadius);
    float facing = dot(n, lightDirection);
    float lit = pow(max(facing, 0.0), 1.2) + 0.75 * pow(max(-facing, 0.0), 1.2);
    float intensity = ambient + (1.0 - ambient) * min(lit, 1.0);
    return color * (intensity * band);
}
"""

/**
 * iPhone Duo's Today View while it scrolls (r21, owner's recording ScreenRecording_10-25-2026 16-16-59 and 7.webp):
 * the widgets are seen through one clear glass sheet [rect]. Inside, what comes within [refractionHeight] of the rim
 * is magnified toward it like the lens above (sampled up to [refractionAmount] further in, so a widget's edge sliding
 * under the rim stretches out to meet it), with a faint RGB split ([dispersion]) where the bend is strongest.
 * Outside the sheet the content fades by [progress]; every effect scales with it.
 */
@Language("AGSL")
internal const val TODAY_SHEET_SHADER = """
uniform shader content;
uniform float4 rect;
uniform float radius;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float dispersion;
uniform float progress;
$ROUNDED_RECT_SDF

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = (rect.zw - rect.xy) * 0.5;
    float2 p = coord - (rect.xy + rect.zw) * 0.5;
    float sd = sdRoundedRect(p, halfSize, radius);
    if (sd > -0.5) {
        // Outside (one pixel of anti-aliasing at the rim): hidden, whether or not the sheet shows (r23).
        float k = clamp(sd + 0.5, 0.0, 1.0);
        return content.eval(coord) * half(1.0 - k);
    }
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    float t = 1.0 - (-sd / refractionHeight);
    float d = circleMap(t) * refractionAmount * progress;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    // Negative distance along the outward normal: sample further in, so what is near the rim is drawn out to it.
    float2 offset = -d * normalize(gradSdRoundedRect(p, halfSize, gradRadius));
    half4 base = content.eval(coord + offset);
    float spread = dispersion * t * t;
    if (spread <= 0.002) {
        return base;
    }
    half4 red = content.eval(coord + offset * (1.0 + spread));
    half4 blue = content.eval(coord + offset * (1.0 - spread));
    return half4(red.r, base.g, blue.b, base.a);
}
"""
