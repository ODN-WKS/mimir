package io.github.odnwks.mimir.ui

/*
 * The mode eyes' shapes, exactly as docs/designs/mimir/icon/in-app/make.py draws them (on its
 * -30 -60 640 640 view box). Copied from the SVGs it makes; don't redraw or round them.
 */

/** One filled shape; [evenOdd] cuts the inner ring out (the iris, the globe). */
data class EyePath(val d: String, val evenOdd: Boolean = false)

object EyeArt {
    /** The view box: left, top, size. */
    const val LEFT = -30f
    const val TOP = -60f
    const val SIZE = 640f

    /** The soft halo behind every eye: radius 420, centred 30 above the open eye's pupil (284, 232) so the eye sits a little low in it. */
    const val HALO_X = 284f
    const val HALO_Y = 202f
    const val HALO_R = 420f

    /** The closed eye's halo centre: 30 above the middle of its lids, scar and lashes (284, 259). */
    const val HALO_CLOSED_X = 284f
    const val HALO_CLOSED_Y = 229f

    /** Where the halo is centred for an eye. */
    fun haloCenter(open: Boolean): Pair<Float, Float> = if (open) HALO_X to HALO_Y else HALO_CLOSED_X to HALO_CLOSED_Y

    /** The halo's falloff at r (0 at the centre, 1 at the edge): peak * exp(-4.2 r^2) * (1 - r^2)^2, exactly 0 at the edge. */
    fun haloAlpha(r: Float, peak: Float = 0.24f): Float {
        val q = r * r
        return peak * kotlin.math.exp(-4.2f * q) * (1f - q) * (1f - q)
    }

    /** The halo's gradient stops: (offset, opacity), 33 of them, from the centre to the edge. */
    fun haloStops(peak: Float = 0.24f, n: Int = 33): List<Pair<Float, Float>> =
        List(n) { i -> val r = i / (n - 1).toFloat(); r to haloAlpha(r, peak) }

    /** The glow's blur (the SVG's stdDeviation), in view-box units. */
    const val GLOW_SIGMA = 9f

    /** Closed eye: the lower lid, the scar, three lashes. */
    val Closed = listOf(
        EyePath("M35.0 226.0 L47.5 237.9 L60.0 249.2 L72.5 259.9 L85.0 270.0 L97.5 279.5 L110.0 288.4 L122.4 296.7 L134.9 304.4 L147.4 311.5 L159.9 318.0 L172.3 323.9 L184.8 329.2 L197.3 333.9 L209.8 338.0 L222.2 341.5 L234.7 344.4 L247.1 346.7 L259.6 348.4 L272.0 349.5 L284.5 350.0 L296.9 349.9 L309.4 349.2 L321.8 347.9 L334.3 346.0 L346.7 343.5 L359.2 340.4 L371.6 336.7 L384.0 332.4 L396.4 327.5 L408.9 322.0 L421.3 315.9 L433.7 309.2 L446.1 301.9 L458.6 294.0 L471.0 285.5 L483.4 276.4 L495.8 266.7 L508.2 256.4 L520.6 245.5 L533.0 234.0 L520.6 242.8 L508.2 251.1 L495.8 258.9 L483.4 266.3 L471.0 273.2 L458.6 279.7 L446.1 285.7 L433.7 291.3 L421.3 296.4 L408.9 301.0 L396.4 305.2 L384.0 308.9 L371.6 312.1 L359.2 314.9 L346.7 317.2 L334.3 319.1 L321.8 320.5 L309.4 321.5 L296.9 322.0 L284.5 322.0 L272.0 321.6 L259.6 320.7 L247.1 319.3 L234.7 317.5 L222.2 315.2 L209.8 312.5 L197.3 309.3 L184.8 305.7 L172.3 301.6 L159.9 297.0 L147.4 292.0 L134.9 286.5 L122.4 280.5 L110.0 274.1 L97.5 267.2 L85.0 259.9 L72.5 252.1 L60.0 243.9 L47.5 235.2Z", evenOdd = false),
        EyePath("M326.0 28.0 L222.3 155.7 L307.5 208.2 L244.0 296.0 L244.0 296.0 L336.5 203.8 L249.7 150.3 L326.0 28.0Z", evenOdd = false),
        EyePath("M181.6 376.6 Q174.7 405.9 170.0 462.0 Q188.7 408.9 194.4 379.4 Q188.0 373.4 181.6 376.6Z", evenOdd = false),
        EyePath("M281.5 387.9 Q280.5 423.6 287.0 490.0 Q294.8 423.8 294.5 388.1 Q288.0 383.4 281.5 387.9Z", evenOdd = false),
        EyePath("M375.7 379.6 Q382.8 409.2 404.0 462.0 Q396.6 405.6 388.3 376.4 Q382.0 373.4 375.7 379.6Z", evenOdd = false),
    )

    /** Open eye: both lids, three lashes turned up, the iris ring and the pupil. */
    val Open = listOf(
        EyePath("M35.0 230.0 L47.5 218.3 L60.0 207.2 L72.5 196.7 L85.0 186.8 L97.5 177.5 L110.0 168.8 L122.4 160.7 L134.9 153.2 L147.4 146.3 L159.9 140.0 L172.3 134.3 L184.8 129.2 L197.3 124.7 L209.8 120.8 L222.2 117.5 L234.7 114.8 L247.1 112.7 L259.6 111.2 L272.0 110.3 L284.5 110.0 L296.9 110.3 L309.4 111.2 L321.8 112.7 L334.3 114.8 L346.7 117.5 L359.2 120.8 L371.6 124.7 L384.0 129.2 L396.4 134.3 L408.9 140.0 L421.3 146.3 L433.7 153.2 L446.1 160.7 L458.6 168.8 L471.0 177.5 L483.4 186.8 L495.8 196.7 L508.2 207.2 L520.6 218.3 L533.0 230.0 L520.6 221.0 L508.2 212.5 L495.8 204.5 L483.4 196.9 L471.0 189.8 L458.6 183.1 L446.1 176.9 L433.7 171.1 L421.3 165.8 L408.9 161.0 L396.4 156.6 L384.0 152.7 L371.6 149.3 L359.2 146.3 L346.7 143.8 L334.3 141.7 L321.8 140.1 L309.4 138.9 L296.9 138.2 L284.5 138.0 L272.0 138.2 L259.6 138.9 L247.1 140.1 L234.7 141.7 L222.2 143.8 L209.8 146.3 L197.3 149.3 L184.8 152.7 L172.3 156.6 L159.9 161.0 L147.4 165.8 L134.9 171.1 L122.4 176.9 L110.0 183.1 L97.5 189.8 L85.0 196.9 L72.5 204.5 L60.0 212.5 L47.5 221.0Z", evenOdd = false),
        EyePath("M35.0 230.0 L47.5 240.2 L60.0 249.9 L72.5 259.1 L85.0 267.8 L97.5 275.9 L110.0 283.6 L122.4 290.6 L134.9 297.2 L147.4 303.2 L159.9 308.8 L172.3 313.7 L184.8 318.2 L197.3 322.1 L209.8 325.6 L222.2 328.4 L234.7 330.8 L247.1 332.6 L259.6 333.9 L272.0 334.7 L284.5 335.0 L296.9 334.7 L309.4 334.0 L321.8 332.6 L334.3 330.8 L346.7 328.4 L359.2 325.6 L371.6 322.1 L384.0 318.2 L396.4 313.7 L408.9 308.8 L421.3 303.2 L433.7 297.2 L446.1 290.6 L458.6 283.5 L471.0 275.9 L483.4 267.8 L495.8 259.1 L508.2 250.0 L520.6 240.2 L533.0 230.0 L520.6 238.7 L508.2 246.9 L495.8 254.7 L483.4 262.0 L471.0 268.9 L458.6 275.4 L446.1 281.4 L433.7 287.0 L421.3 292.1 L408.9 296.8 L396.4 301.0 L384.0 304.8 L371.6 308.1 L359.2 311.0 L346.7 313.4 L334.3 315.4 L321.8 317.0 L309.4 318.1 L296.9 318.8 L284.5 319.0 L272.0 318.8 L259.6 318.1 L247.1 317.0 L234.7 315.4 L222.2 313.4 L209.8 311.0 L197.3 308.1 L184.8 304.8 L172.3 301.0 L159.9 296.8 L147.4 292.1 L134.9 287.0 L122.4 281.4 L110.0 275.4 L97.5 268.9 L85.0 262.0 L72.5 254.7 L60.0 246.9 L47.5 238.7Z", evenOdd = false),
        EyePath("M194.3 94.3 Q187.9 68.2 168.0 22.0 Q174.1 72.0 181.7 97.7 Q188.0 91.5 194.3 94.3Z", evenOdd = false),
        EyePath("M290.5 84.0 Q291.1 49.7 284.0 -14.0 Q276.9 49.7 277.5 84.0 Q284.0 79.5 290.5 84.0Z", evenOdd = false),
        EyePath("M386.3 97.7 Q393.9 72.0 400.0 22.0 Q380.1 68.2 373.7 94.3 Q380.0 91.5 386.3 97.7Z", evenOdd = false),
        EyePath("M284 160a72 72 0 1 0 0.1 0Z M284 182a50 50 0 1 0 0.1 0Z", evenOdd = true),
        EyePath("M261 232a23 23 0 1 0 46 0a23 23 0 1 0 -46 0Z", evenOdd = false),
    )

    /** The small globe added at the closed eye's lower right for a Whisper with internet. */
    val Globe = listOf(
        EyePath("M492 388a50 50 0 1 0 0.1 0Z M492 395a43 43 0 1 0 0.1 0Z", evenOdd = true),
        EyePath("M445 435h94v6h-94Z", evenOdd = false),
        EyePath("M492 388c36.0 20.0 36.0 80.0 0 100c-36.0 -20.0 -36.0 -80.0 0 -100Z M492 395c28.000000000000004 20.0 28.000000000000004 66.0 0 86c-28.000000000000004 -20.0 -28.000000000000004 -66.0 0 -86Z", evenOdd = true),
    )
}
