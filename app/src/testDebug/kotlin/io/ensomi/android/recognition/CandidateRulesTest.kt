package io.ensomi.android.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateRulesTest {
    private val sessionStart = 1_000_000L
    private val now = sessionStart + 60_000
    private val song = SongIdentity(title = "晴天", artist = "周杰伦")

    private fun file(name: String = "周杰伦 - 晴天.mp3", modified: Long = now - 5_000, size: Long = 8_000_000) =
        FileFacts("/music/$name", name, size, modified)

    private fun judge(
        file: FileFacts = file(),
        evidence: Evidence = Evidence.CLOSE_WRITE,
        identity: SongIdentity? = song,
        match: MatchStrength = MatchStrength.TITLE_ARTIST,
        lastChange: Long = file.modifiedWallMs,
    ) = CandidateRules.judge(file, evidence, identity, match, sessionStart, lastChange, now)

    @Test
    fun aFinishedQuietNewFileGoesToTheDecoder() {
        assertEquals(Verdict.PROBE, judge())
    }

    @Test
    fun temporaryNamesAreRefusedByName() {
        for (name in listOf("a.mp3.tmp", "a.mp3.part", "a.mp3.download", "a.mp3.crdownload", ".a.mp3", "~a.mp3", "a.MP3.TEMP")) {
            assertEquals(name, Verdict.TEMPORARY, judge(file(name)))
        }
    }

    @Test
    fun whatIsNotAudioIsRefused() {
        assertEquals(Verdict.NOT_AUDIO, judge(file("cover.jpg")))
        assertEquals(Verdict.NOT_AUDIO, judge(file("周杰伦 - 晴天.lrc")))
    }

    @Test
    fun aFileStillBeingWrittenWaits() {
        assertEquals(Verdict.UNFINISHED, judge(evidence = Evidence.WRITING))
        assertEquals(Verdict.UNFINISHED, judge(file().copy(pending = true)))
        assertEquals(Verdict.UNFINISHED, judge(file(size = 0)))
    }

    @Test
    fun anythingOfTheFinishedKindCounts() {
        for (evidence in listOf(Evidence.CLOSE_WRITE, Evidence.MOVED_TO, Evidence.MEDIA_STORE)) {
            assertEquals(evidence.name, Verdict.PROBE, judge(evidence = evidence))
        }
    }

    @Test
    fun aFileMustHaveBeenQuietForASecond() {
        assertEquals(Verdict.NOT_QUIET, judge(file(modified = now - 999)))
        assertEquals(Verdict.PROBE, judge(file(modified = now - 1_000)))
        // A size change noticed just now counts, whatever the file's own time says.
        assertEquals(Verdict.NOT_QUIET, judge(file(modified = now - 5_000), lastChange = now - 100))
    }

    @Test
    fun aFileOnlyThePollHasSeenMustBeQuietForLonger() {
        assertEquals(Verdict.NOT_QUIET, judge(file(modified = now - 7_999), evidence = Evidence.POLL))
        assertEquals(Verdict.PROBE, judge(file(modified = now - 8_000), evidence = Evidence.POLL))
    }

    @Test
    fun aNewFileFoundByListingHasNotShownItsWriterIsDone() {
        assertEquals(Verdict.NOT_QUIET, judge(file(modified = now - 5_000), evidence = Evidence.INDEX))
        assertEquals(Verdict.PROBE, judge(file(modified = now - 8_000), evidence = Evidence.INDEX))
    }

    @Test
    fun anOlderFileIsAcceptedOnlyByIdentity() {
        val old = file(modified = sessionStart - 86_400_000)
        assertEquals(Verdict.PROBE, judge(old, evidence = Evidence.INDEX, match = MatchStrength.TITLE_ARTIST))
        assertEquals(Verdict.PROBE, judge(old, evidence = Evidence.INDEX, match = MatchStrength.SONG_ID))
        assertEquals(Verdict.BEFORE_SESSION, judge(old, evidence = Evidence.INDEX, match = MatchStrength.NONE))
        assertEquals(Verdict.BEFORE_SESSION, judge(old, evidence = Evidence.INDEX, match = MatchStrength.TITLE_ONLY))
    }

    @Test
    fun anOlderFileByTitleAloneNeedsItsLengthToAgree() {
        val old = file(modified = sessionStart - 86_400_000).copy(durationMs = 269_000)
        val timed = song.copy(durationMs = 270_000)
        assertEquals(Verdict.PROBE, judge(old, evidence = Evidence.INDEX, identity = timed, match = MatchStrength.TITLE_ONLY))
        val far = song.copy(durationMs = 300_000)
        assertEquals(Verdict.BEFORE_SESSION, judge(old, evidence = Evidence.INDEX, identity = far, match = MatchStrength.TITLE_ONLY))
    }

    @Test
    fun aNewFileNeedsOnlyTheTitle() {
        assertEquals(Verdict.PROBE, judge(match = MatchStrength.TITLE_ONLY))
    }

    @Test
    fun withoutASongAFileWaitsAndAnOldOneIsLeftAlone() {
        assertEquals(Verdict.NO_SONG_YET, judge(identity = null, match = MatchStrength.NONE))
        val old = file(modified = sessionStart - 1)
        assertEquals(Verdict.BEFORE_SESSION, judge(old, identity = null, match = MatchStrength.NONE))
    }

    @Test
    fun aNewFileThatIsNotTheSongIsLeftToThePerson() {
        assertEquals(Verdict.NO_MATCH, judge(match = MatchStrength.NONE))
        assertEquals(Verdict.PROBE, judge(identity = null, match = MatchStrength.PERSON))
    }

    @Test
    fun aNcmIsProtectedWhenItIsTheSongAndNeverProbed() {
        val ncm = file("周杰伦 - 晴天.ncm")
        assertEquals(Verdict.PROTECTED, judge(ncm))
        assertEquals(Verdict.PROTECTED, judge(ncm, evidence = Evidence.WRITING))
        assertEquals(Verdict.PROTECTED, judge(ncm, match = MatchStrength.PERSON))
        assertEquals(Verdict.NO_MATCH, judge(ncm, match = MatchStrength.NONE))
        assertEquals(Verdict.NO_SONG_YET, judge(ncm, identity = null, match = MatchStrength.NONE))
        assertEquals(Verdict.TEMPORARY, judge(file("周杰伦 - 晴天.ncm.tmp")))
    }

    private fun decoded(
        file: FileFacts,
        decodedMs: Double = 200_000.0,
        declaredMs: Long? = 200_000,
        ok: Boolean = true,
    ) = ProbeFacts(ok, decodedMs, declaredMs, 44_100, sizeBytes = file.sizeBytes, modifiedWallMs = file.modifiedWallMs)

    @Test
    fun aWholeFileOfTheRightLengthIsReady() {
        val file = file()
        assertEquals(Verdict.READY, CandidateRules.judgeDecoded(file, decoded(file), 200_000))
        assertEquals(Verdict.READY, CandidateRules.judgeDecoded(file, decoded(file), expectedDurationMs = null))
        assertEquals(Verdict.READY, CandidateRules.judgeDecoded(file, decoded(file, declaredMs = null), null))
    }

    @Test
    fun aFileThatDoesNotDecodeIsRefused() {
        val file = file()
        assertEquals(Verdict.UNDECODABLE, CandidateRules.judgeDecoded(file, decoded(file, ok = false), null))
        assertEquals(Verdict.UNDECODABLE, CandidateRules.judgeDecoded(file, decoded(file, decodedMs = 0.0), null))
    }

    @Test
    fun aFileShorterThanItSaysIsStillArriving() {
        val file = file()
        assertEquals(Verdict.TRUNCATED, CandidateRules.judgeDecoded(file, decoded(file, decodedMs = 120_000.0), null))
        assertEquals(Verdict.READY, CandidateRules.judgeDecoded(file, decoded(file, decodedMs = 198_500.0), null))
    }

    @Test
    fun aDifferentLengthFromTheSongsIsRefused() {
        val file = file()
        assertEquals(Verdict.DURATION_DIFFERS, CandidateRules.judgeDecoded(file, decoded(file), 210_000))
        assertEquals(Verdict.READY, CandidateRules.judgeDecoded(file, decoded(file), 202_000))
    }

    @Test
    fun aFileThatChangedUnderTheDecoderCountsForNothing() {
        val file = file()
        val grew = decoded(file).copy(sizeBytes = file.sizeBytes + 1)
        assertEquals(Verdict.CHANGED_WHILE_PROBED, CandidateRules.judgeDecoded(file, grew, null))
    }

    @Test
    fun thePersonOverrulesTheLengthsButNotTheDecoder() {
        val file = file()
        assertEquals(Verdict.READY, CandidateRules.judgeDecoded(file, decoded(file, decodedMs = 120_000.0), 200_000, personChose = true))
        assertEquals(Verdict.UNDECODABLE, CandidateRules.judgeDecoded(file, decoded(file, ok = false), null, personChose = true))
    }

    @Test
    fun namesAreSortedOut() {
        assertTrue(CandidateRules.isProtected("a.NCM"))
        assertFalse(CandidateRules.isProtected("a.mp3"))
        assertTrue(CandidateRules.isAudio("a.flac"))
        assertFalse(CandidateRules.isAudio("a.ncm"))
    }
}

class SongMatchTest {
    private fun strength(song: SongIdentity, file: FileFacts, known: String? = null) = SongMatch.strength(song, file, known)

    private fun named(name: String) = FileFacts("/music/$name", name, 1, 1)

    private val song = SongIdentity(title = "晴天", artist = "周杰伦")

    @Test
    fun aNameLikeTheHostsMatches() {
        assertEquals(MatchStrength.TITLE_ARTIST, strength(song, named("周杰伦 - 晴天.mp3")))
    }

    @Test
    fun aNameTheOtherWayRoundMatches() {
        assertEquals(MatchStrength.TITLE_ARTIST, strength(song, named("晴天 - 周杰伦.mp3")))
    }

    @Test
    fun tagsMatchWhateverTheNameSays() {
        val file = named("track01.mp3").copy(title = "晴天", artist = "周杰伦")
        assertEquals(MatchStrength.TITLE_ARTIST, strength(song, file))
    }

    @Test
    fun onlyTheTitleIsAWeakerMatch() {
        assertEquals(MatchStrength.TITLE_ONLY, strength(song, named("晴天.mp3")))
        assertEquals(MatchStrength.TITLE_ONLY, strength(song, named("孙燕姿 - 晴天.mp3")))
    }

    @Test
    fun oneOfSeveralArtistsIsEnough() {
        val duet = SongIdentity(title = "Everything Has Changed", artist = "Taylor Swift/Ed Sheeran")
        assertEquals(MatchStrength.TITLE_ARTIST, strength(duet, named("Taylor Swift,Ed Sheeran - Everything Has Changed.mp3")))
        assertEquals(MatchStrength.TITLE_ARTIST, strength(duet, named("Ed Sheeran - Everything Has Changed.flac")))
    }

    @Test
    fun widthCaseAndPunctuationDoNotMatter() {
        val song = SongIdentity(title = "Don't Stop Believin'", artist = "Journey")
        assertEquals(MatchStrength.TITLE_ARTIST, strength(song, named("JOURNEY - Dont Stop Believin.mp3")))
        val wide = SongIdentity(title = "ＡＢＣ", artist = "ＸＹ")
        assertEquals(MatchStrength.TITLE_ARTIST, strength(wide, named("XY - abc.mp3")))
    }

    @Test
    fun aTitleWithADashInItIsFound() {
        val song = SongIdentity(title = "Song - Live", artist = "Band")
        assertEquals(MatchStrength.TITLE_ARTIST, strength(song, named("Band - Song - Live.mp3")))
    }

    @Test
    fun anotherVersionIsNotTheSameTitle() {
        assertEquals(MatchStrength.NONE, strength(song, named("周杰伦 - 晴天 (Live).mp3")))
    }

    @Test
    fun whatMediaStoreCallsATitleWithoutTagsIsTheName() {
        val row = FileFacts("/music/x", "x.mp3", 1, 1, title = "周杰伦 - 晴天")
        assertEquals(MatchStrength.TITLE_ARTIST, strength(song, row))
    }

    @Test
    fun theFileASongWasFoundInBeforeIsKnownByItsId() {
        val file = named("anything.mp3")
        assertEquals(MatchStrength.SONG_ID, strength(SongIdentity(songId = 1), file, known = file.path))
        assertEquals(MatchStrength.NONE, strength(SongIdentity(songId = 1), file, known = "/elsewhere"))
    }

    @Test
    fun aTitleOfSymbolsStillCompares() {
        val song = SongIdentity(title = "♡", artist = "A")
        assertEquals(MatchStrength.TITLE_ARTIST, strength(song, named("A - ♡.mp3")))
    }

    @Test
    fun sameSongTellsSongsApart() {
        assertTrue(SongIdentity(songId = 1).sameSongAs(SongIdentity(songId = 1, title = "x")))
        assertFalse(SongIdentity(songId = 1).sameSongAs(SongIdentity(songId = 2)))
        assertTrue(SongIdentity(title = "晴天").sameSongAs(SongIdentity(title = " 晴天 ")))
        assertFalse(SongIdentity(title = "a").sameSongAs(SongIdentity(title = "b")))
        assertTrue(SongIdentity(title = "a").sameSongAs(SongIdentity(songId = 2)))
    }
}
