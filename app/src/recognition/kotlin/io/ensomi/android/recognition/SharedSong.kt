package io.ensomi.android.recognition

/** What a share's text says about a song. */
data class SharedSong(
    val songId: Long? = null,
    val title: String? = null,
    val artist: String? = null,
    /** The first link in the text, whatever it is. */
    val url: String? = null,
    /** A public short link, when the text itself names no song id. */
    val shortLink: String? = null,
    /** The link is to something else: "playlist", "album" and so on. */
    val otherKind: String? = null,
) {
    val identity: SongIdentity get() = SongIdentity(songId, title, artist)

    /** Whether this says which song it is, or how to find out. */
    val namesSong: Boolean get() = otherKind == null && (songId != null || title != null || shortLink != null)
}

/**
 * Reads the text of a share. The host's share text looks like `分享xxx的单曲《xxx》: https://… (来自@网易云音乐)`; the link
 * may be a page of `music.163.com` or `y.music.163.com` in any of its forms, an `orpheus://song/` link, or a public short
 * link. A short link says nothing until it is looked up ([ShortLinks]), and the title and artist in the text are enough
 * to match files without it.
 */
object ShareParser {
    private val LINK = Regex(
        """(?:(?:https?|orpheus)://|(?<![\w.])(?:y\.music\.163\.com|music\.163\.com|163cn\.tv|163\.tn)/)[^\s，。；、（）()》」'"<>\u3000]+""",
        RegexOption.IGNORE_CASE,
    )
    private val ID_PARAMETER = Regex("""(?:^|[?&])id=(\d+)""")
    private val ORPHEUS_SONG = Regex("""^orpheus://song/(\d+)""", RegexOption.IGNORE_CASE)
    private val PAGE_HOSTS = setOf("music.163.com", "y.music.163.com", "www.music.163.com")
    private val SHORT_HOSTS = setOf("163cn.tv", "163.tn")

    /** What a page's first path segment means, for a link that is not to a song. */
    private val OTHER_PAGES = setOf("playlist", "album", "artist", "user", "dj", "djradio", "program", "mv", "video", "event")

    /** The same, in the text's own words. */
    private val OTHER_WORDS = listOf("的专辑《" to "album", "的歌单《" to "playlist", "歌单《" to "playlist", "的声音《" to "program", "的播客《" to "program")

    /** How the host calls a song in a share, and what stands between the artist and the title. */
    private val SONG_WORDS = listOf("的单曲《", "的歌曲《", "单曲《", "歌曲《")

    /** Null when the text has nothing to do with a song. */
    fun parse(text: String): SharedSong? {
        val links = LINK.findAll(text).map { it.value.trimEnd('.', ',', ':', ';') }.toList()
        val pages = links.mapNotNull(::page)
        val song = pages.firstOrNull { it.kind == "song" && it.id != null }
        val short = links.firstOrNull { host(it) in SHORT_HOSTS }
        val inWords = SONG_WORDS.firstNotNullOfOrNull { titleAndArtist(text, it, links.firstOrNull()) }
        val otherPage = pages.firstOrNull { it.kind in OTHER_PAGES }?.kind
        val otherWord = OTHER_WORDS.firstOrNull { (word, _) -> word in text }?.second
        val other = if (song == null && inWords == null) otherPage ?: otherWord else null
        val shared = SharedSong(
            songId = song?.id,
            title = inWords?.first,
            artist = inWords?.second,
            url = links.firstOrNull(),
            shortLink = short.takeIf { song == null },
            otherKind = other,
        )
        return shared.takeIf { it.namesSong || it.otherKind != null }
    }

    private class Page(val kind: String, val id: Long?)

    private fun page(url: String): Page? {
        ORPHEUS_SONG.find(url)?.let { return Page("song", it.groupValues[1].toLongOrNull()) }
        val host = host(url)
        if (host !in PAGE_HOSTS) return null
        val rest = url.substringAfter("://", url).substringAfter('/', "")
        // `#/song?id=1` keeps its query in the fragment; nothing here needs them told apart.
        val path = rest.removePrefix("#").removePrefix("/").removePrefix("m/")
        val segments = path.substringBefore('?').substringBefore('#').split('/').filter { it.isNotEmpty() }
        val kind = segments.firstOrNull()?.lowercase() ?: return null
        val query = path.substringAfter('?', "")
        val id = ID_PARAMETER.find(query)?.groupValues?.get(1)?.toLongOrNull()
            ?: segments.getOrNull(1)?.toLongOrNull()
        return Page(kind, id)
    }

    private fun host(url: String): String =
        url.substringAfter("://", url).substringBefore('/').substringBefore('?').substringBefore('#').lowercase()

    /** The title and artist around [word]: the title runs to the last `》` before the link, the artist back to `分享`. */
    private fun titleAndArtist(text: String, word: String, link: String?): Pair<String, String?>? {
        val at = text.indexOf(word)
        if (at < 0) return null
        val start = at + word.length
        val limit = link?.let { text.indexOf(it).takeIf { index -> index > start } } ?: text.length
        val end = text.lastIndexOf('》', limit - 1)
        if (end <= start) return null
        val title = text.substring(start, end).trim()
        if (title.isEmpty()) return null
        val share = text.lastIndexOf("分享", at)
        val artist = if (share >= 0) text.substring(share + 2, at).trim().takeIf { it.isNotEmpty() } else null
        return title to artist
    }
}
