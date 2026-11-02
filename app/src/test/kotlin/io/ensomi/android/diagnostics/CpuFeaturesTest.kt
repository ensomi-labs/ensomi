package io.ensomi.android.diagnostics

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class CpuFeaturesTest {
    @Test fun readsUnsignedArm64AuxiliaryVectorAndIgnoresOtherKeysAndTornTail() {
        val bytes = ByteBuffer.allocate(65).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(6).putLong(16384).putLong(16).putLong(Long.MIN_VALUE).putLong(26).putLong(0x1234)
            .putLong(0).putLong(0).array()
        assertEquals(mapOf(16L to "0x8000000000000000", 26L to "0x1234"), CpuFeatures.parseAuxv(bytes))
    }
}
