package io.ensomi.android.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareParserTest {
    private fun parsed(text: String): SharedSong = assertNotNull(text, ShareParser.parse(text)).let { ShareParser.parse(text)!! }

    @Test
    fun readsTheHostsShareText() {
        val song = parsed("分享周杰伦的单曲《晴天》: https://music.163.com/song?id=186016&userid=99 (来自@网易云音乐)")
        assertEquals(186016L, song.songId)
        assertEquals("晴天", song.title)
        assertEquals("周杰伦", song.artist)
        assertNull(song.shortLink)
        assertTrue(song.namesSong)
    }

    @Test
    fun readsEveryFormOfASongPage() {
        val pages = listOf(
            "https://music.163.com/song?id=186016",
            "http://music.163.com/song?id=186016&userid=1234567",
            "https://music.163.com/song?userid=1234567&id=186016",
            "https://music.163.com/#/song?id=186016",
            "https://music.163.com/#/m/song?id=186016",
            "https://y.music.163.com/m/song?id=186016&dlt=0846",
            "https://music.163.com/m/song?id=186016",
            "http://music.163.com/song/186016/?userid=1234567",
            "https://music.163.com/m/song/186016",
            "https://music.163.com/song/media/outer/url?id=186016.mp3",
            "music.163.com/song?id=186016",
            "y.music.163.com/m/song?id=186016",
            "orpheus://song/186016",
            "orpheus://song/186016?autoplay=true",
        )
        for (page in pages) assertEquals(page, 186016L, parsed("听这首 $page 好听").songId)
    }

    @Test
    fun aLinkDoesNotTakeThePunctuationAroundIt() {
        assertEquals(186016L, parsed("https://music.163.com/song?id=186016，来自网易云").songId)
        assertEquals(186016L, parsed("（https://music.163.com/song?id=186016）").songId)
        assertEquals(186016L, parsed("https://music.163.com/song?id=186016。").songId)
        assertEquals("https://163cn.tv/zoIxm3", parsed("分享A的单曲《B》https://163cn.tv/zoIxm3(来自@网易云音乐)").shortLink)
    }

    @Test
    fun aUserIdIsNotASongId() {
        val song = ShareParser.parse("https://music.163.com/song?userid=1234567")
        assertNull(song?.songId)
    }

    @Test
    fun aShortLinkIsKeptForTheLookUpAndTheWordsStillName() {
        val song = parsed("分享Taylor Swift/Ed Sheeran的单曲《Everything Has Changed》: https://163cn.tv/zoIxm3 (来自@网易云音乐)")
        assertNull(song.songId)
        assertEquals("https://163cn.tv/zoIxm3", song.shortLink)
        assertEquals("Everything Has Changed", song.title)
        assertEquals("Taylor Swift/Ed Sheeran", song.artist)
        assertEquals("https://163.tn/ab1", parsed("https://163.tn/ab1").shortLink)
        assertEquals("163cn.tv/zoIxm3", parsed("听听 163cn.tv/zoIxm3").shortLink)
    }

    @Test
    fun aSongPageMakesAShortLinkUnnecessary() {
        val song = parsed("https://music.163.com/song?id=7 https://163cn.tv/zoIxm3")
        assertEquals(7L, song.songId)
        assertNull(song.shortLink)
    }

    @Test
    fun titlesKeepWhatTheyAre() {
        assertEquals("七里香 (Live)", parsed("分享周杰伦的单曲《七里香 (Live)》: https://163cn.tv/a").title)
        assertEquals("《雨》之歌", parsed("分享某人的单曲《《雨》之歌》: https://163cn.tv/a").title)
        assertEquals("A：B", parsed("分享X的单曲《A：B》：https://163cn.tv/a").title)
        assertEquals("X的单曲", parsed("分享Y的单曲《X的单曲》https://163cn.tv/a").title)
    }

    @Test
    fun otherWordingsOfASongAreRead() {
        val noArtist = parsed("分享单曲《光年之外》: https://music.163.com/song?id=449818741")
        assertEquals("光年之外", noArtist.title)
        assertNull(noArtist.artist)
        val before = parsed("今天听到这首太好了 分享邓紫棋的歌曲《光年之外》 https://music.163.com/song?id=449818741")
        assertEquals("邓紫棋", before.artist)
        val noLink = parsed("分享邓紫棋的单曲《光年之外》")
        assertEquals("光年之外", noLink.title)
        assertNull(noLink.songId)
        assertTrue(noLink.namesSong)
    }

    @Test
    fun aMultiLineShareIsRead() {
        val song = parsed("分享五月天的单曲《倔强》:\nhttps://y.music.163.com/m/song?id=386538\n(来自@网易云音乐)")
        assertEquals(386538L, song.songId)
        assertEquals("倔强", song.title)
    }

    @Test
    fun whatIsNotASongSaysWhatItIs() {
        val playlist = parsed("分享某人的歌单《夜跑》: https://music.163.com/playlist?id=2829883282 (来自@网易云音乐)")
        assertEquals("playlist", playlist.otherKind)
        assertNull(playlist.songId)
        assertFalse(playlist.namesSong)
        assertEquals("album", parsed("https://music.163.com/#/album?id=34720827").otherKind)
        assertEquals("artist", parsed("https://music.163.com/artist?id=6452").otherKind)
        assertEquals("album", parsed("分享周杰伦的专辑《叶惠美》").otherKind)
    }

    @Test
    fun textWithoutASongIsNothing() {
        assertNull(ShareParser.parse("hello"))
        assertNull(ShareParser.parse(""))
        assertNull(ShareParser.parse("https://example.com/song?id=5"))
        assertNull(ShareParser.parse("分享一个好消息"))
    }

    @Test
    fun anOtherHostIsNotASongPage() {
        assertNull(ShareParser.parse("https://notmusic.163.com/song?id=5")?.songId)
        assertNull(ShareParser.parse("https://example.com/?u=music.163.com/song?id=5")?.songId)
    }
}
