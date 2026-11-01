package io.ensomi.android.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Identity and file matching must agree about the same concrete title pairs. */
class SongTitleTest {
    private fun assertTitlesMatch(a: String, b: String, matches: Boolean = true) {
        for ((left, right) in listOf(a to b, b to a)) {
            val song = SongIdentity(title = left, artist = "Artist")
            assertEquals("$left / $right", matches, song.sameSongAs(SongIdentity(title = right)))
            val expected = if (matches) MatchStrength.TITLE_ARTIST else MatchStrength.NONE
            val tagged = FileFacts("/music/track.mp3", "track.mp3", 1, 1, title = right, artist = "Artist")
            assertEquals("tags: $left / $right", expected, SongMatch.strength(song, tagged, null))
            val named = FileFacts("/music/file.mp3", "Artist - $right.mp3", 1, 1)
            assertEquals("name: $left / $right", expected, SongMatch.strength(song, named, null))
        }
    }

    @Test
    fun theObservedGameEndingAndTvEpisodeCreditsDoNotChangeTheSong() {
        assertTitlesMatch("lettRE", "lettRE (ゲーム「トリノライン」EDテーマ)")
        assertTitlesMatch("春日影", "春日影 (TV动画「BanG Dream! It's MyGO!!!!!」#3 插曲)")
    }

    @Test
    fun widthCaseAndSpacesAreFoldedBeforeReadingCredits() {
        assertTitlesMatch("lettRE", " ＬＥＴＴＲＥ ")
        assertTitlesMatch("lettRE", "ＬＥＴＴＲＥ （ゲーム「トリノライン」ｅｄテーマ）")
        assertTitlesMatch("春日影", "春日影 （ＴＶ动画「Example」＃３ 插曲）")
    }

    @Test
    fun gameAndAnimeOpeningEndingThemeAndInsertCreditsHaveExplicitForms() {
        for (credit in listOf(
            "ゲーム「Example」OPテーマ", "ゲーム『Example』エンディングテーマ", "ゲーム「Example」主題歌",
            "アニメ「Example」オープニングテーマ", "TVアニメ「Example」ED", "アニメ「Example」挿入歌",
            "游戏《Example》片头曲", "游戏《Example》片尾曲", "游戏《Example》主题曲", "游戏《Example》插曲",
            "TV动画《Example》片头曲", "动画《Example》片尾曲", "动画《Example》主题歌", "动画《Example》第3话 插曲",
            "遊戲《Example》片頭曲", "遊戲《Example》片尾曲", "遊戲《Example》主題曲", "遊戲《Example》插曲",
            "TV動畫「Example」#3 插曲", "動畫《Example》第3話 片頭曲",
            "Game \"Example\" opening theme", "Game \"Example\" ending song", "Game \"Example\" theme song",
            "Anime \"Example\" insert song", "TV anime \"Example\" opening theme song",
        )) assertTitlesMatch("lettRE", "lettRE ($credit)")
    }

    @Test
    fun takeVersionAndCoverLabelsStayDistinctEvenBesideACredit() {
        for (label in listOf("Live", "Remix", "Instrumental", "Off Vocal", "Acoustic", "TV Size", "cover by Artist", "翻唱：Artist")) {
            assertTitlesMatch("lettRE", "lettRE ($label)", matches = false)
            assertTitlesMatch("lettRE", "lettRE ($label) (ゲーム「Example」EDテーマ)", matches = false)
            assertTitlesMatch("lettRE", "lettRE (ゲーム「Example」EDテーマ $label)", matches = false)
            assertTitlesMatch("lettRE", "lettRE (ゲーム「Example」EDテーマ) ($label)", matches = false)
        }
    }

    @Test
    fun unknownOrIncompleteParenthesizedTextIsNotDecoration() {
        for (suffix in listOf(
            "(Unknown)", "(EDテーマ)", "(ゲーム Example EDテーマ)", "(ゲーム「Example」Unknown)",
            "(ゲーム「Example」EDテーマ extra)", "(ゲーム「Example」EDテーマ", "(ゲーム「Example』EDテーマ)",
            "(映画「Example」主題歌)", "(ゲーム「Example」EDテーマ) extra",
        )) assertTitlesMatch("lettRE", "lettRE $suffix", matches = false)
        assertTitlesMatch("lettRE", "(ゲーム「Example」EDテーマ)", matches = false)
        assertTitlesMatch("", "(ゲーム「Example」EDテーマ)", matches = false)
    }

    @Test
    fun twoSongsSharingOneCreditAreStillDifferent() {
        assertTitlesMatch("lettRE (ゲーム「Example」EDテーマ)", "春日影 (ゲーム「Example」EDテーマ)", matches = false)
    }

    @Test
    fun knownSongIdsStillTakePrecedenceOverTitles() {
        val song = SongIdentity(songId = 1, title = "lettRE")
        assertTrue(song.sameSongAs(SongIdentity(songId = 1, title = "春日影")))
        assertFalse(song.sameSongAs(SongIdentity(songId = 2, title = "lettRE (ゲーム「トリノライン」EDテーマ)")))
    }
}
