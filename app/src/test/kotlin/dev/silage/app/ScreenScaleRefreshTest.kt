package dev.silage.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenScaleRefreshTest {
    // Stand-ins named like skiko's layer: one directly, one by inheritance.
    private open class SkiaLayer : javax.swing.JPanel()
    private class SoftwareLayer : SkiaLayer()

    @Test fun findsSkiaLayersAnywhereInTheWindow() {
        val direct = SkiaLayer()
        val inherited = SoftwareLayer()
        val root = javax.swing.JPanel().apply {
            add(javax.swing.JPanel().apply { add(direct) })
            add(inherited)
            add(javax.swing.JLabel("other"))
        }
        assertEquals(listOf<java.awt.Component>(direct, inherited), ScreenScaleRefresh.skiaLayers(root))
    }

    @Test fun findsNothingWithoutALayer() {
        assertEquals(emptyList(), ScreenScaleRefresh.skiaLayers(javax.swing.JPanel().apply { add(javax.swing.JLabel("x")) }))
    }
}
