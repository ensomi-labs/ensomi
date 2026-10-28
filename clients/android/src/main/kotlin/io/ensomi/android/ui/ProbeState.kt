package io.ensomi.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.ensomi.android.probe.CaptureResult
import io.ensomi.android.probe.DeviceRecord
import java.io.File

/**
 * Everything the probe screen shows. The activity owns one instance and writes it from the main
 * thread, which is what makes it safe to read from composition.
 */
class ProbeState(val traceFile: File) {
    var device by mutableStateOf<DeviceRecord?>(null)
    var microphoneGranted by mutableStateOf(false)
    var allFilesGranted by mutableStateOf(false)
    var netEaseLines by mutableStateOf<List<String>>(emptyList())
    var watchDirectory by mutableStateOf(DEFAULT_WATCH_DIRECTORY)
    var watching by mutableStateOf(false)
    var watchLines by mutableStateOf<List<String>>(emptyList())
    var capturing by mutableStateOf(false)
    var captures by mutableStateOf<List<CaptureResult>>(emptyList())
    var traceLineCount by mutableStateOf(0)
    var message by mutableStateOf<String?>(null)

    companion object {
        /**
         * Where the K80's NetEase client writes downloads, as found on the device: the client keeps
         * its library under `Download/netease/cloudmusic`, not under a top-level `netease` folder.
         * The probe lets the operator change it, because the path is a client setting.
         */
        const val DEFAULT_WATCH_DIRECTORY = "/storage/emulated/0/Download/netease/cloudmusic/Music"
    }
}

/** What the screen can ask the activity to do. */
interface ProbeActions {
    fun freezeDevice()
    fun requestMicrophone()
    fun requestAllFiles()
    fun probeDeepLinks()
    fun openSong()
    fun openRecognition()
    fun toggleWatching()
    fun capture(seconds: Int)
    fun note(text: String)
}
