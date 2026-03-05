package com.collabora.whisperlive.android

import org.junit.Assert.assertEquals
import org.junit.Test

class ResamplerTest {

    @Test
    fun resampleLinear_48000_to_16000_keeps_duration() {
        val inRate = 48_000
        val outRate = 16_000
        val secs = 1
        val inputLen = inRate * secs
        val input = ShortArray(inputLen) { 0 }
        val out = Resampler.resampleLinear(input, inputLen, inRate, outRate)
        assertEquals(outRate * secs, out.size)
    }
}

