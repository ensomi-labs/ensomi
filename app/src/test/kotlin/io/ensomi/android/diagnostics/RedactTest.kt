package io.ensomi.android.diagnostics

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RedactTest {
    @Before fun roots() { Redact.configure(mapOf("/data/user/0/invented.app/files" to "<files>", "/data/user/0/invented.app/no_backup" to "<nobackup>", "/data/user/0/invented.app/cache" to "<cache>")) }
    @Test fun goldenPathsUrisAndTokens() {
        val cases = mapOf(
            "/data/user/0/invented.app/files/private.onnx" to "<files>",
            "/data/user/0/invented.app/no_backup/private.json" to "<nobackup>",
            "/data/user/0/invented.app/cache/private.txt" to "<cache>",
            "/storage/emulated/0/Music/Aster.mp3" to "<ext>",
            "/sdcard/Aster.mp3" to "<ext>",
            "content://invented.provider/document/Aster.mp3?token=secret" to "content://invented.provider",
            "https://huggingface.co/invented/model?token=secret" to "https://huggingface.co",
            "https://cdn-lfs.hf.co/private?token=secret" to "https://cdn-lfs.hf.co",
            "https://cas-bridge.xethub.hf.co/private?token=secret" to "https://cas-bridge.xethub.hf.co",
            "https://invented.invalid/private?token=secret" to "<url>",
            "https://hf.co.invented.invalid/file?token=secret" to "<url>",
            "invented.person@example.invalid" to "<email>",
            "Aster Song.mp3" to "<file>",
            "\"Aster Song\"" to "<redacted>",
            "a".repeat(64) to "<token>",
            "Ab9+".repeat(20) to "<token>",
        )
        cases.forEach { (input, expected) -> assertEquals(input, expected, Redact.text(input)) }
    }
    @Test fun sensitiveContextKeysAreAlwaysSuppressedAndFieldsCapped() {
        for (key in listOf("title", "filename", "song", "document", "message")) assertEquals("<redacted>", Redact.detail(key, "Aster Song"))
        assertEquals(20, Redact.text("word ".repeat(100), 20).length)
        assertEquals("java.lang.IllegalStateException", Redact.text("java.lang.IllegalStateException"))
    }
    @Test fun dottedClassesAndLongKernelSymbolsSurvive() {
        for (value in listOf("io.ensomi.android.json.Json.encode:12", "java.util.zip.ZipFile"))
            assertEquals(value, Redact.text(value))
        val symbol = "kai_run_matmul_clamp_f32_bf16p8x4_bf16p12x4b_8x12_neon_mmla"
        assertEquals(symbol, Redact.symbol(symbol))
    }

}
