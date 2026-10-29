package io.ensomi.android

import android.content.Context
import io.ensomi.android.audio.PlayAudioCache
import io.ensomi.android.chart.ChartStore
import io.ensomi.android.chart.RuntimeChartMaker
import io.ensomi.android.library.ChartedSongLibrary
import io.ensomi.android.library.DocumentSongLibrary
import io.ensomi.android.model.DownloadingChartModelStore
import io.ensomi.android.settings.DataStorePlaySettingsStore
import io.ensomi.android.settings.SystemOutputRoute
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The app's stores, one of each for the process: the settings (DataStore), the song library, the chart model, the
 * charts made and the sound of the song last played. They work on [scope], off the main thread, and outlive any
 * activity.
 */
class AppStores private constructor(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val route = SystemOutputRoute(context)
    val settings = DataStorePlaySettingsStore(DataStorePlaySettingsStore.dataStore(context, scope), route.route, scope)
    private val documents = DocumentSongLibrary(context, scope)
    val model = DownloadingChartModelStore(context, scope)
    val chartStore = ChartStore(File(context.filesDir, "charts"))
    val charts = RuntimeChartMaker(context, scope, documents, model, chartStore, RuntimeChartMaker.lowMemory(context))
    val songs = ChartedSongLibrary(documents, chartStore.songIds, scope) { charts.forget(it) }
    val playAudio = PlayAudioCache()

    companion object {
        @Volatile
        private var instance: AppStores? = null

        fun get(context: Context): AppStores =
            instance ?: synchronized(this) { instance ?: AppStores(context.applicationContext).also { instance = it } }
    }
}
