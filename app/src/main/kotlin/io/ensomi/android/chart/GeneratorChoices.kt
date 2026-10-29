package io.ensomi.android.chart

/** Choices the app makes about the runtime's generator, kept apart from it so the JVM tests can check them. */
object GeneratorChoices {
    /**
     * Below this much memory the generator runs with `lowMemory` on. On the 4 GB emulator the default's audio stages
     * of a 105-second song peaked at 1.8 GB of resident memory, and Android's low-memory killer ended seven background
     * apps to make room; `lowMemory` peaked at 1.1 GB with no kill, for 1.9 s more (6.4 s against 4.5 s), in a stage
     * that runs in the background as the song opens. A phone sold with 8 GB reports a little over 7 GiB, so it keeps
     * the faster default until the phone's own measurement says otherwise (plan step 5).
     */
    const val LOW_MEMORY_BELOW_BYTES = 7L shl 30

    fun lowMemory(totalMemoryBytes: Long, lowRamDevice: Boolean): Boolean =
        lowRamDevice || totalMemoryBytes < LOW_MEMORY_BELOW_BYTES

    /** The runtime's refusal as the status card shows it: its code, then its message, without the code twice. */
    fun refusal(code: String, message: String): String =
        if (message.startsWith(code)) message else "$code: $message"
}
