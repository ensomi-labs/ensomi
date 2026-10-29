package io.ensomi.android.audio

import android.net.Uri

/**
 * The sound of the song last played, decoded for play, so that Play again and Restart start at once. It holds one song
 * (a three-minute song at 48 kHz in stereo is about 70 MB) and lets it go when the song's screen closes.
 */
class PlayAudioCache {
    private var songId: String? = null
    private var uri: Uri? = null
    private var audio: DecodedSong? = null

    @Synchronized
    fun get(songId: String, uri: Uri): DecodedSong? = audio.takeIf { this.songId == songId && this.uri == uri }

    @Synchronized
    fun put(songId: String, uri: Uri, audio: DecodedSong) {
        this.songId = songId
        this.uri = uri
        this.audio = audio
    }

    @Synchronized
    fun drop(songId: String) {
        if (this.songId != songId) return
        this.songId = null
        uri = null
        audio = null
    }
}
