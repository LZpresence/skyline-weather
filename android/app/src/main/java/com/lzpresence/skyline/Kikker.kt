package com.lzpresence.skyline

import android.graphics.Bitmap
import android.graphics.Canvas
import com.caverock.androidsvg.SVG
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** Kikker for the widget. Mirrors kikker.js in the web app; keep the two in step. */
object Kikker {
    private const val SKIN = "#2f625f"
    private const val SHADE = "#1f4644"
    private const val HI = "#4f8a85"
    private const val LINE = "#163533"
    private const val IRIS = "#7a4622"
    private const val PUPIL = "#1b1009"

    private data class Top(
        val c: String, val d: String, val y: Int, val long: Boolean,
        val puff: Boolean = false, val hood: Boolean = false, val zip: Boolean = false,
        val collar: Boolean = false, val buttons: Boolean = false,
    )

    private val TOPS = mapOf(
        "winter" to Top("#c8562f", "#9c3f20", 128, true, puff = true, hood = true),
        "heavy" to Top("#34507c", "#243a5c", 130, true, puff = true),
        "warm" to Top("#a8432f", "#7f2f20", 132, true, zip = true, collar = true),
        "fleece" to Top("#8b9aa5", "#6b7a85", 134, true, zip = true),
        "wind" to Top("#2a8fbf", "#1d6c92", 134, true, zip = true),
        "rain" to Top("#f2c230", "#c99a17", 134, true, buttons = true),
        "sleeves" to Top("#6d4f8f", "#523a6c", 140, true),
        "tee" to Top("#e9823a", "#c0642a", 140, false),
    )

    private fun f(d: Double) = String.format(Locale.US, "%.1f", d)

    fun bitmap(keys: Set<String>, scene: Scene, px: Int): Bitmap {
        val svg = SVG.getFromString(svg(keys, scene))
        svg.documentWidth = px.toFloat()
        svg.documentHeight = px.toFloat()
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(Canvas(bmp))
        return bmp
    }

    fun svg(keys: Set<String>, sc: Scene): String {
        fun has(k: String) = k in keys
        val umbrella = has("umbrella")
        val top = when {
            has("coat-winter") -> "winter"
            has("coat-heavy") -> "heavy"
            has("coat-warm") -> "warm"
            has("jacket-light") -> if (has("wind")) "wind" else "fleece"
            has("wind") -> "wind"
            umbrella -> "rain"
            has("sleeves") -> "sleeves"
            else -> "tee"
        }
        val t = TOPS.getValue(top)
        val pantsColor = "#3d5f8f"
        val arms = listOf("M62 130 C44 148 40 170 46 190", "M178 130 C196 148 200 170 194 190")
        val upper = listOf("M62 130 C54 138 50 146 48 154", "M178 130 C186 138 190 146 192 154")
        val legs = listOf("M94 186 C62 180 40 194 52 210 C58 218 70 220 84 220", "M146 186 C178 180 200 194 188 210 C182 218 170 220 156 220")
        val thighs = listOf("M94 186 C72 182 58 188 54 198", "M146 186 C168 182 182 188 186 198")

        val s = StringBuilder()
        s.append(
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 240 240" width="240" height="240"><defs>
            <radialGradient id="g" cx="40%" cy="28%" r="75%"><stop offset="0" stop-color="$HI"/><stop offset=".55" stop-color="$SKIN"/><stop offset="1" stop-color="$SHADE"/></radialGradient>
            <clipPath id="b"><ellipse cx="120" cy="142" rx="72" ry="62"/></clipPath>
            <clipPath id="c"><ellipse cx="120" cy="143" rx="77" ry="66"/></clipPath></defs>"""
        )

        // scene
        if (sc.sunny && !sc.night) {
            s.append("""<circle cx="34" cy="36" r="15" fill="#ffb347"/><g stroke="#ffb347" stroke-width="3" stroke-linecap="round">""")
            for (a in 0 until 360 step 45) {
                val r = Math.toRadians(a.toDouble())
                s.append("""<line x1="${f(34 + 21 * cos(r))}" y1="${f(36 + 21 * sin(r))}" x2="${f(34 + 28 * cos(r))}" y2="${f(36 + 28 * sin(r))}"/>""")
            }
            s.append("</g>")
        } else if (sc.night && sc.precip == null) {
            s.append("""<path d="M40 18a16 16 0 1 0 12 26a13 13 0 0 1-12-26z" fill="#d9e3ec"/><g fill="#d9e3ec"><circle cx="70" cy="22" r="1.6"/><circle cx="18" cy="62" r="1.4"/><circle cx="214" cy="30" r="1.6"/></g>""")
        }
        if (sc.precip != null || sc.thunder) {
            s.append("""<path d="M14 52h44a12 12 0 0 0 0-24a17 17 0 0 0-32-3a12 12 0 0 0-12 27z" fill="#c9d4de" opacity=".9"/>""")
            if (sc.thunder) s.append("""<path d="M38 52l-7 13h7l-4 12 12-16h-7l4-9z" fill="#ffd23f"/>""")
        }
        if (sc.precip == "rain") {
            s.append("""<g stroke="#7fb8e6" stroke-width="2.4" stroke-linecap="round" opacity=".9">""")
            for ((x, y) in listOf(20 to 70, 36 to 92, 14 to 112, 30 to 134, 212 to 96, 224 to 128, 206 to 150, 222 to 170))
                s.append("""<line x1="$x" y1="$y" x2="${x - 4}" y2="${y + 11}"/>""")
            s.append("</g>")
        } else if (sc.precip == "snow") {
            s.append("""<g fill="#ffffff" opacity=".95">""")
            listOf(22 to 72, 40 to 96, 14 to 120, 32 to 146, 210 to 92, 226 to 120, 204 to 144, 220 to 172, 196 to 64)
                .forEachIndexed { i, (x, y) -> s.append("""<circle cx="$x" cy="$y" r="${2.2 + (i % 2)}"/>""") }
            s.append("</g>")
        }
        if (sc.windy) {
            s.append("""<g fill="none" stroke="#c9d4de" stroke-width="2.4" stroke-linecap="round" opacity=".8"><path d="M196 70h22a6 6 0 1 0-6-6"/><path d="M188 82h34a6 6 0 1 1-6 6"/><path d="M200 94h16"/></g>""")
        }
        s.append("""<ellipse cx="120" cy="228" rx="74" ry="7" fill="#000000" opacity=".22"/>""")

        // umbrella
        if (umbrella) {
            s.append(
                """<line x1="150" y1="40" x2="193" y2="194" stroke="#3a2a1f" stroke-width="3.5" stroke-linecap="round"/>
                <g transform="rotate(-12 150 40)"><path d="M76 42 Q150 -16 224 42 Q212 33 199 42 Q187 33 175 42 Q162 33 150 42 Q138 33 125 42 Q113 33 101 42 Q88 33 76 42Z" fill="#d6453d"/>
                <path d="M150 -1 Q138 14 125 42 M150 -1 Q162 14 175 42" fill="none" stroke="#a8322b" stroke-width="2"/><circle cx="150" cy="-2" r="3" fill="#3a2a1f"/></g>"""
            )
        }

        // legs, pants, feet
        for (d in legs) s.append("""<path d="$d" fill="none" stroke="$SKIN" stroke-width="20" stroke-linecap="round"/>""")
        val pantsPaths = if (has("shorts")) thighs else legs
        val pantsWidth = if (has("shorts")) 25 else 22
        for (d in pantsPaths) s.append("""<path d="$d" fill="none" stroke="$pantsColor" stroke-width="$pantsWidth" stroke-linecap="round"/>""")
        for ((x, flip) in listOf(84 to 1, 156 to -1)) {
            if (has("boots")) {
                val bc = if (sc.precip == "snow") "#6b4a2f" else "#f2c230"
                s.append("""<g transform="translate($x 220) scale($flip 1)"><rect x="-15" y="-15" width="30" height="24" rx="8" fill="$bc"/><rect x="-20" y="4" width="36" height="9" rx="4.5" fill="$bc"/><rect x="-20" y="10" width="36" height="4" rx="2" fill="#000000" opacity=".25"/></g>""")
            } else {
                s.append("""<g transform="translate($x 218) scale($flip 1)" fill="$SKIN"><ellipse cx="2" cy="2" rx="15" ry="8"/><circle cx="-11" cy="8" r="5.5"/><circle cx="2" cy="11" r="5.5"/><circle cx="15" cy="8" r="5.5"/></g>""")
            }
        }

        // body
        s.append("""<ellipse cx="120" cy="142" rx="72" ry="62" fill="url(#g)"/>""")

        // top layer, clipped to the body
        val clip = if (t.puff) "c" else "b"
        s.append("""<g clip-path="url(#$clip)"><rect x="0" y="${t.y}" width="240" height="120" fill="${t.c}"/>""")
        if (!t.zip && !t.puff && !t.buttons) s.append("""<path d="M98 ${t.y - 1} Q120 ${t.y + 11} 142 ${t.y - 1}" fill="none" stroke="${t.d}" stroke-width="3.5" stroke-linecap="round"/>""")
        if (t.puff) {
            s.append("""<g stroke="${t.d}" stroke-width="2.5" fill="none">""")
            for (y in listOf(156, 176, 196)) s.append("""<path d="M40 $y Q120 ${y + 8} 200 $y"/>""")
            s.append("</g>")
        }
        if (t.zip || t.puff) s.append("""<line x1="120" y1="${t.y + 2}" x2="120" y2="212" stroke="${t.d}" stroke-width="2.5"/><rect x="117" y="${t.y + 6}" width="6" height="9" rx="2" fill="#d7d7d7"/>""")
        if (t.buttons) {
            s.append("""<g fill="${t.d}">""")
            for (y in listOf(150, 168, 186)) s.append("""<circle cx="120" cy="$y" r="3"/>""")
            s.append("</g>")
        }
        if (t.hood) s.append("""<path d="M44 ${t.y + 2} Q120 ${t.y + 20} 196 ${t.y + 2}" stroke="#efe6d6" stroke-width="9" fill="none" stroke-linecap="round"/>""")
        if (t.collar) s.append("""<path d="M92 ${t.y} L112 ${t.y + 14} L120 ${t.y + 2} L128 ${t.y + 14} L148 ${t.y}" fill="${t.d}"/>""")
        s.append("</g>")

        // scarf
        if (has("scarf")) {
            s.append(
                """<g clip-path="url(#c)"><path d="M40 122 Q120 146 200 122 L200 140 Q120 164 40 140 Z" fill="#c23b3b"/><path d="M40 131 Q120 155 200 131" stroke="#f3e2c7" stroke-width="3" fill="none"/></g>
                <g transform="rotate(8 150 142)"><rect x="143" y="136" width="15" height="40" rx="3" fill="#c23b3b"/><rect x="143" y="150" width="15" height="3" fill="#f3e2c7"/><path d="M143 176 v6 M148 176 v6 M153 176 v6 M158 176 v6" stroke="#c23b3b" stroke-width="2"/></g>"""
            )
        }

        // arms, sleeves, hands
        for (d in arms) s.append("""<path d="$d" fill="none" stroke="$SKIN" stroke-width="15" stroke-linecap="round"/>""")
        for (i in 0..1) {
            val d = if (t.long) arms[i] else upper[i]
            s.append("""<path d="$d" fill="none" stroke="${t.c}" stroke-width="${if (t.puff) 21 else 18}" stroke-linecap="${if (t.long) "butt" else "round"}"/>""")
            if (t.long) {
                val x = if (i == 0) 46 else 194
                s.append("""<ellipse cx="$x" cy="186" rx="${if (t.puff) 11 else 9.5}" ry="4" fill="${t.d}"/>""")
            }
        }
        for ((x, flip) in listOf(46 to 1, 194 to -1)) {
            if (has("gloves")) {
                s.append("""<g transform="translate($x 196) scale($flip 1)"><ellipse cx="0" cy="2" rx="10" ry="11" fill="#e9b949"/><ellipse cx="-9" cy="-2" rx="4" ry="6" fill="#e9b949"/><rect x="-10" y="-9" width="20" height="5" rx="2.5" fill="#c99a2a"/></g>""")
            } else {
                s.append("""<g transform="translate($x 194)" fill="$SKIN"><circle r="7"/><circle cx="-8" cy="5" r="4"/><circle cx="-3" cy="10" r="4"/><circle cx="3" cy="10" r="4"/><circle cx="8" cy="5" r="4"/></g>""")
            }
        }

        // hat, eyes, face
        if (has("hat")) {
            s.append("""<path d="M72 84 C72 40 168 40 168 84 Z" fill="#7a3fa0"/><g stroke="#5d2e7c" stroke-width="2.5"><line x1="98" y1="78" x2="104" y2="52"/><line x1="120" y1="78" x2="120" y2="48"/><line x1="142" y1="78" x2="136" y2="52"/></g><rect x="68" y="72" width="104" height="15" rx="7.5" fill="#5d2e7c"/><circle cx="120" cy="45" r="10" fill="#f3e2c7"/>""")
        }
        for (x in listOf(82, 158)) {
            s.append(
                """<circle cx="$x" cy="80" r="24" fill="url(#g)"/><circle cx="$x" cy="84" r="16" fill="#fffaf2"/>
                <circle cx="${x + 1}" cy="86" r="13" fill="$IRIS"/><circle cx="${x + 1}" cy="87" r="8" fill="$PUPIL"/>
                <circle cx="${x + 6}" cy="80" r="4.5" fill="#ffffff"/><circle cx="${x - 4}" cy="92" r="2.2" fill="#ffffff" opacity=".9"/>"""
            )
        }
        if (has("sun")) {
            s.append(
                """<path d="M102 82 Q120 76 138 82" stroke="#111111" stroke-width="3.5" fill="none"/>
                <rect x="60" y="72" width="44" height="26" rx="12" fill="#151515"/><rect x="136" y="72" width="44" height="26" rx="12" fill="#151515"/>
                <path d="M68 79 l10 0 M144 79 l10 0" stroke="#ffffff" stroke-width="2.5" stroke-linecap="round" opacity=".55"/>"""
            )
        }
        val cheek = if (sc.mood == "cold") "#a9cdf0" else "#ff8fa3"
        s.append("""<g fill="$cheek" opacity=".6"><ellipse cx="72" cy="112" rx="11" ry="6.5"/><ellipse cx="168" cy="112" rx="11" ry="6.5"/></g>""")
        s.append("""<circle cx="114" cy="104" r="1.5" fill="$LINE"/><circle cx="126" cy="104" r="1.5" fill="$LINE"/>""")
        if (sc.mood == "happy") {
            s.append("""<path d="M104 113 Q120 132 136 113 Z" fill="#6e2a36" stroke="$LINE" stroke-width="2.5" stroke-linejoin="round"/><path d="M112 122 Q120 117 128 122 Q120 129 112 122Z" fill="#e8798a"/>""")
        } else {
            val mouth = if (sc.mood == "cold") "M107 118 q4.5 -3 9 0 t9 0 t9 0" else "M107 115 Q120 125 133 115"
            s.append("""<path d="$mouth" fill="none" stroke="$LINE" stroke-width="3" stroke-linecap="round"/>""")
        }
        if (has("mask")) {
            s.append("""<path d="M98 112 L72 104 M142 112 L168 104" stroke="#cfd9e0" stroke-width="2"/><rect x="96" y="100" width="48" height="28" rx="9" fill="#e6eef3"/><path d="M102 110 h36 M102 118 h36" stroke="#c5d1d9" stroke-width="1.5"/>""")
        }
        if (has("umbrella-maybe") && !umbrella) {
            s.append("""<g transform="rotate(-28 200 170)"><rect x="196" y="140" width="9" height="50" rx="4" fill="#d6453d"/><path d="M200 190 v8 a4 4 0 0 1 -8 0" stroke="#3a2a1f" stroke-width="3" fill="none"/></g>""")
        }
        s.append("</svg>")
        return s.toString()
    }
}
