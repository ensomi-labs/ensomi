package io.ensomi.android.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import android.view.Display
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Reads the device facts and runs the runtime check. Every read is best effort. */
class DiagnosticsCollector(context: Context) {
    private val context = context.applicationContext

    suspend fun collect(): DiagnosticsReport = coroutineScope {
        val runtime = async { RuntimeProbe.check() }
        withContext(Dispatchers.IO) {
            DiagnosticsReport(
                collectedAt = Instant.now().toString(),
                app = app(),
                device = device(),
                cpu = CpuFacts(CpuCores.read()),
                memory = memory(),
                display = display(),
                audio = audio(),
                thermal = thermal(),
                runtime = runtime.await(),
            )
        }
    }

    private fun app(): AppFacts {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        return AppFacts(context.packageName, info.versionName, info.longVersionCode)
    }

    private fun device(): DeviceFacts {
        val properties = VendorOs.readProperties()
        return DeviceFacts(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            socManufacturer = Build.SOC_MANUFACTURER,
            socModel = Build.SOC_MODEL,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            vendorOs = VendorOs.label(properties),
            vendorOsProperties = properties,
        )
    }

    private fun memory(): MemoryFacts {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        val advertised = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) info.advertisedMem else null
        return MemoryFacts(
            pageSizeBytes = Os.sysconf(OsConstants._SC_PAGESIZE),
            totalRamBytes = info.totalMem,
            advertisedRamBytes = advertised?.takeIf { it > 0 },
        )
    }

    private fun display(): DisplayFacts {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ?: return DisplayFacts(currentMode = null, supportedModes = emptyList())
        val modes = display.supportedModes
            .map { it.toDisplayMode() }
            .sortedWith(compareByDescending<DisplayMode> { it.width * it.height }.thenByDescending { it.refreshRateHz })
        return DisplayFacts(display.mode.toDisplayMode(), modes)
    }

    private fun Display.Mode.toDisplayMode() = DisplayMode(modeId, physicalWidth, physicalHeight, refreshRate)

    private fun audio(): AudioFacts {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val packageManager = context.packageManager
        return AudioFacts(
            lowLatency = packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY),
            pro = packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO),
            outputSampleRateHz = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull(),
            outputFramesPerBuffer = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
                ?.toIntOrNull(),
            // "true" when supported, null otherwise.
            unprocessedCapture =
                audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true",
        )
    }

    private fun thermal(): ThermalFacts {
        val powerManager = context.getSystemService(PowerManager::class.java)
        // NaN when the device does not support it.
        val headroom = powerManager.getThermalHeadroom(10).takeIf { it.isFinite() }
        return ThermalFacts(thermalStatusName(powerManager.currentThermalStatus), headroom)
    }

    companion object {
        fun thermalStatusName(status: Int): String = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "status $status"
        }
    }
}
