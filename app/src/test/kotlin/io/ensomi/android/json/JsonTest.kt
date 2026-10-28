package io.ensomi.android.json

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonTest {
    @Test
    fun encodesNestedValuesInOrder() {
        val value = linkedMapOf(
            "b" to 1,
            "a" to listOf(true, null, 2L, 1.5),
            "c" to linkedMapOf("empty" to emptyList<Any>()),
        )
        assertEquals("""{"b":1,"a":[true,null,2,1.5],"c":{"empty":[]}}""", Json.encode(value))
    }

    @Test
    fun escapesStrings() {
        assertEquals(
            "\"quote \\\" backslash \\\\ newline \\n tab \\t bell \\u0007 é ×\"",
            Json.encode("quote \" backslash \\ newline \n tab \t bell \u0007 é ×"),
        )
    }

    @Test
    fun writesNonFiniteNumbersAsNull() {
        assertEquals("[null,null,null,60.0]", Json.encode(listOf(Float.NaN, Double.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 60f)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesNonStringKeys() {
        Json.encode(mapOf(1 to "one"))
    }
}
