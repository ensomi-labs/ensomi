package io.ensomi.capture

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TakeManifestValidatorTest {
    private fun valid(): TakeManifest = TakeManifest(
        recordingID = "6f1d9c2a-3b7e-4c8d-9a10-5e2f4b6c8d0e",
        createdAt = "2026-10-23T09:00:00Z",
        stoppedAt = "2026-10-23T09:02:30Z",
        durationMS = 150_000,
        audioFileName = "phone-room-3m-20261023-090000.caf",
        recordingGroup = "phone-in-a-room",
        takeLabel = "3m-with-tv",
        sampleRate = 48_000.0,
        channelCount = 1,
        targetAsset = TakeTargetAsset(
            id = "0b6f1c2d-4e3a-4b5c-8d7e-9f0a1b2c3d4e",
            displayPath = "Music/example.flac",
            fileName = "example.flac",
            sha256 = "a".repeat(64),
            durationMS = 210_000,
            title = "Example",
            artists = listOf("Artist"),
        ),
        captureSource = CaptureSource.MIC,
        deviceModel = "Pixel 9",
        osVersion = "Android 16",
        appVersion = "0.1.0",
        commit = "eecff2b",
        storageAlias = "phone-room-3m-20261023-090000",
    )

    @Test
    fun validManifestHasNoErrors() {
        assertEquals(emptyList(), TakeManifestValidator.validate(valid()))
    }

    @Test
    fun bystandersWithoutConsentAreRejected() {
        val manifest = valid().copy(bystandersAudible = true)
        assertContains(TakeManifestValidator.validate(manifest), "consentRecordedAt")
    }

    @Test
    fun consentExpiryMustFollowItsRecording() {
        val manifest = valid().copy(
            bystandersAudible = true,
            consentRecordedAt = "2026-10-23T09:00:00Z",
            consentExpiresAt = "2026-10-23T09:00:00Z",
        )
        assertContains(TakeManifestValidator.validate(manifest), "consentExpiresAt")
    }

    @Test
    fun reportedLatencyNeedsAValue() {
        val manifest = valid().copy(reportsLatency = true)
        assertContains(TakeManifestValidator.validate(manifest), "outputLatencyMS")
    }

    @Test
    fun aTakeIsOneFileNotAPath() {
        val manifest = valid().copy(audioFileName = "LocalFixtures/take.caf")
        assertContains(TakeManifestValidator.validate(manifest), "audioFileName")
    }

    @Test
    fun theSourceHashIsSixtyFourLowercaseHexCharacters() {
        val manifest = valid().let { it.copy(targetAsset = it.targetAsset.copy(sha256 = "ABC")) }
        assertContains(TakeManifestValidator.validate(manifest), "targetAsset.sha256")
    }

    @Test
    fun aTakeCannotStopBeforeItStarts() {
        val manifest = valid().copy(stoppedAt = "2026-10-23T08:59:00Z")
        assertContains(TakeManifestValidator.validate(manifest), "stoppedAt")
    }

    @Test
    fun twoSourcesNeedTwoChannels() {
        val manifest = valid().copy(captureSource = CaptureSource.MIC_AND_LOOPBACK)
        assertContains(TakeManifestValidator.validate(manifest), "channelCount")
    }

    @Test
    fun everyFieldSurvivesJson() {
        val manifest = valid().copy(
            notes = "television audible",
            bystandersAudible = true,
            consentRecordedAt = "2026-10-23T08:59:00Z",
            consentExpiresAt = "2027-10-23T08:59:00Z",
            distanceM = 3.0,
            room = "living room",
            outputLatencyMS = 42.0,
            reportsLatency = true,
            captureSource = CaptureSource.MIC_AND_LOOPBACK,
            channelCount = 2,
        )

        val encoded = TakeManifestJson.encodeToString(TakeManifest.serializer(), manifest)
        val decoded = TakeManifestJson.decodeFromString(TakeManifest.serializer(), encoded)

        assertEquals(manifest, decoded)
        assertTrue(encoded.contains("\"schemaVersion\": 2"))
    }
}
