package io.ensomi.android.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartRequestRulesTest {
    private val stars = 2.0..5.9
    private val song = 133_000L

    @Test
    fun readsMinutesAndSeconds() {
        assertEquals(32_000, ChartRequestRules.parseTime("00:32"))
        assertEquals(125_000, ChartRequestRules.parseTime("2:05"))
        assertNull(ChartRequestRules.parseTime("1:60"))
        assertNull(ChartRequestRules.parseTime("1:5"))
        assertNull(ChartRequestRules.parseTime(":05"))
        assertNull(ChartRequestRules.parseTime("1:05:00"))
        assertNull(ChartRequestRules.parseTime("a:05"))
    }

    @Test
    fun formatsLengths() {
        assertEquals("2:13", ChartRequestRules.formatDuration(133_900))
        assertEquals("0:08", ChartRequestRules.formatDuration(8_000))
    }

    @Test
    fun seedsAreBlankOrAnUnsignedSixtyFourBitNumber() {
        assertTrue(ChartRequestRules.isSeedValid(""))
        assertTrue(ChartRequestRules.isSeedValid("18446744073709551615"))
        assertFalse(ChartRequestRules.isSeedValid("18446744073709551616"))
        assertFalse(ChartRequestRules.isSeedValid("-1"))
        assertEquals(2914uL, ChartRequestRules.parseSeed(" 2914 "))
    }

    @Test
    fun aSectionLastsEightToThirtyTwoSecondsInsideTheSong() {
        fun problems(start: String, end: String) =
            ChartRequestRules.check(listOf(SectionDraft(1, start, end)), song, stars).map { it.problem }
        assertEquals(emptyList<SectionProblem>(), problems("00:32", "00:48"))
        assertEquals(emptyList<SectionProblem>(), problems("00:00", "00:08"))
        assertEquals(emptyList<SectionProblem>(), problems("00:00", "00:32"))
        assertEquals(listOf(SectionProblem.LENGTH), problems("00:32", "00:36"))
        assertEquals(listOf(SectionProblem.LENGTH), problems("00:00", "00:33"))
        assertEquals(listOf(SectionProblem.END_NOT_AFTER_START), problems("00:40", "00:40"))
        assertEquals(listOf(SectionProblem.OUTSIDE_SONG), problems("02:05", "02:14"))
        assertEquals(listOf(SectionProblem.TIME_FORMAT), problems("0:3", "00:40"))
    }

    @Test
    fun sectionsMayTouchButNotOverlap() {
        val touching = listOf(SectionDraft(1, "00:10", "00:20"), SectionDraft(2, "00:20", "00:30"))
        assertEquals(emptyList<SectionIssue>(), ChartRequestRules.check(touching, song, stars))
        val overlapping = listOf(SectionDraft(1, "00:10", "00:20"), SectionDraft(2, "00:19", "00:30"))
        assertEquals(listOf(SectionIssue(null, SectionProblem.OVERLAP)), ChartRequestRules.check(overlapping, song, stars))
    }

    @Test
    fun aStarSectionUsesTheModelsRange() {
        val section = SectionDraft(1, "00:10", "00:20", kind = SectionKind.STAR, star = 6.5f)
        assertEquals(listOf(SectionIssue(0, SectionProblem.STAR_RANGE)), ChartRequestRules.check(listOf(section), song, stars))
    }

    @Test
    fun starsComeInTenthsBelowTheExclusiveTop() {
        val capabilities = ChartCapabilities("Ensomi R2", minStar = 2.0, maxStarExclusive = 6.0, kinds = emptyList())
        assertEquals(2.0, capabilities.selectableStars.start, 1e-9)
        assertEquals(5.9, capabilities.selectableStars.endInclusive, 1e-9)
        // 2.0, 2.1, ... 5.9 is 40 values: a slider with 38 steps between its ends.
        assertEquals(38, capabilities.starSliderSteps)
    }
}
