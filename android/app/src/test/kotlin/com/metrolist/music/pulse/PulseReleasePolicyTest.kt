package com.metrolist.music.pulse

import org.junit.Assert.*
import org.junit.Test

class PulseReleasePolicyTest {
    @Test fun comparesNumbersInsteadOfText() {
        assertTrue(PulseReleasePolicy.isNewer("v13.10.0", "13.9.9"))
        assertFalse(PulseReleasePolicy.isNewer("13.7.0", "13.7.0+abc1234"))
        assertFalse(PulseReleasePolicy.isNewer("13.6.9", "13.7.0"))
    }
    @Test fun neverOffersPreviewOrMalformedReleaseAsStable() {
        listOf("nightly", "14.0.0-beta.1", "v99", "999999999999.1.1").forEach {
            assertFalse(PulseReleasePolicy.isNewer(it, "13.7.0"))
        }
    }
    @Test fun repositoryCannotChangeUrlHostOrInjectParameters() {
        assertTrue(PulseReleasePolicy.validRepository("brandon/pulse-music"))
        listOf("https://github.com/a/b", "a/b?token=x", "a/b/../../c", "a/b#x", "@evil/a", "a/b\nc/d").forEach {
            assertFalse(PulseReleasePolicy.validRepository(it))
        }
    }
}
