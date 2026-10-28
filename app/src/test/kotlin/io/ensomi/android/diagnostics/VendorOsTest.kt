package io.ensomi.android.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class VendorOsTest {
    @Test
    fun parsesGetpropOutput() {
        val output = """
            [ro.mi.os.version.incremental]: [OS3.0.307.0.WNCCNXM]
            [ro.product.model]: [24031PN0DC]
            [empty.value]: []
            not a property line
        """.trimIndent()
        assertEquals(
            mapOf(
                "ro.mi.os.version.incremental" to "OS3.0.307.0.WNCCNXM",
                "ro.product.model" to "24031PN0DC",
                "empty.value" to "",
            ),
            VendorOs.parseGetprop(output),
        )
    }

    @Test
    fun prefersHyperOsOverMiui() {
        val properties = mapOf(
            "ro.mi.os.version.name" to "OS3.0",
            "ro.mi.os.version.incremental" to "OS3.0.307.0.WNCCNXM",
            "ro.miui.ui.version.name" to "V816",
        )
        assertEquals("HyperOS 3.0.307.0.WNCCNXM", VendorOs.label(properties))
        assertEquals("HyperOS 3.0", VendorOs.label(mapOf("ro.mi.os.version.name" to "OS3.0")))
    }

    @Test
    fun fallsBackToMiuiThenUnknown() {
        assertEquals("MIUI V140", VendorOs.label(mapOf("ro.miui.ui.version.name" to "V140")))
        assertEquals(VendorOs.UNKNOWN, VendorOs.label(mapOf("ro.mi.os.version.name" to " ")))
        assertEquals(VendorOs.UNKNOWN, VendorOs.label(emptyMap()))
    }
}
