package io.ensomi.android.probe

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** One component of the installed NetEase client the probe lists. */
data class InstalledComponent(
    val name: String,
    val exported: Boolean,
)

/** Everything the probe could read about the installed NetEase client. */
data class NetEaseRecord(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val targetSdk: Int,
    val sourceDir: String,
    val lastUpdateTimeMs: Long,
    val activityCount: Int,
    val exportedActivities: List<InstalledComponent>,
    val recognitionComponents: List<InstalledComponent>,
)

/** The device and the probe target, frozen together so every later result can be tied to them. */
data class DeviceRecord(
    val manufacturer: String,
    val model: String,
    val device: String,
    val release: String,
    val sdk: Int,
    val fingerprint: String,
    val buildIncremental: String,
    val netEase: NetEaseRecord?,
    val netEaseError: String?,
)

/**
 * Reads the facts the report's first phase asks to lock down: the Android build, the HyperOS build and
 * the NetEase versionCode, so an experiment result means something specific.
 */
object DeviceProbe {
    const val NETEASE_PACKAGE = "com.netease.cloudmusic"

    fun read(context: Context): DeviceRecord {
        val manager = context.packageManager
        val netEase = runCatching { readNetEase(manager) }
        return DeviceRecord(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            release = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            fingerprint = Build.FINGERPRINT,
            buildIncremental = Build.VERSION.INCREMENTAL,
            netEase = netEase.getOrNull(),
            netEaseError = netEase.exceptionOrNull()?.let { it::class.java.simpleName + ": " + it.message },
        )
    }

    private fun readNetEase(manager: PackageManager): NetEaseRecord {
        val flags = PackageManager.PackageInfoFlags.of(
            PackageManager.GET_ACTIVITIES.toLong() or PackageManager.GET_SERVICES.toLong(),
        )
        val info = manager.getPackageInfo(NETEASE_PACKAGE, flags)
        val activities = info.activities.orEmpty()
        val components = activities.map { InstalledComponent(it.name, it.exported) }

        // The recognition family is what the report's P0 asks about: an entry a third party may start
        // would remove the UI automation step entirely.
        val recognition = components.filter {
            it.name.contains(".recognition.") || it.name.contains("Identify", ignoreCase = true)
        }

        return NetEaseRecord(
            packageName = info.packageName,
            versionName = info.versionName.orEmpty(),
            versionCode = PackageInfoCompat.versionCode(info),
            targetSdk = info.applicationInfo?.targetSdkVersion ?: 0,
            sourceDir = info.applicationInfo?.sourceDir.orEmpty(),
            lastUpdateTimeMs = info.lastUpdateTime,
            activityCount = activities.size,
            exportedActivities = components.filter { it.exported },
            recognitionComponents = recognition,
        )
    }
}

/** The two version-code accessors, so the probe reads one value on every API level. */
private object PackageInfoCompat {
    fun versionCode(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
}
