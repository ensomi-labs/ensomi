package io.ensomi.android.model

/** Merge hook for the durable diagnostic journal. Details contain no private paths, names or URLs. */
fun interface InstallEvents {
    fun record(event: String, phase: String, detail: Map<String, String>)

    companion object {
        val NONE = InstallEvents { _, _, _ -> }
    }
}

enum class InstallPhase { STAGING, VERIFYING, COMMITTING }
