package com.collabora.whisperlive.android

import kotlin.math.floor

object Resampler {

    /**
     * Linear resampler from PCM16 mono input to PCM16 mono output.
     *
     * @param input PCM samples
     * @param inputLen valid samples count in input
     */
    fun resampleLinear(
        input: ShortArray,
        inputLen: Int,
        inRate: Int,
        outRate: Int,
    ): ShortArray {
        if (inputLen <= 1 || inRate == outRate) return input.copyOfRange(0, inputLen)

        val ratio = outRate.toDouble() / inRate.toDouble()
        val outLen = (inputLen * ratio).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)

        for (i in 0 until outLen) {
            val srcIndex = i / ratio
            val idx = floor(srcIndex).toInt()
            val frac = (srcIndex - idx)

            val s0 = input[idx].toInt()
            val s1 = input[minOf(idx + 1, inputLen - 1)].toInt()
            val v = (s0 + (s1 - s0) * frac).toInt()
            out[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        return out
    }
}

