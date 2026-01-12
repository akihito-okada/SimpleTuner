package com.example.simpletuner.tuner

import kotlin.math.abs

/**
 * 中心(0)付近だけ吸い付くタイプのヒステリシス。
 * - |cents| <= snapToZeroCents なら 0 を返す
 * - それ以外はそのまま返す
 */
class CentsHysteresis(
    private val snapToZeroCents: Int = 2
) {
    fun apply(cents: Int): Int {
        return if (abs(cents) <= snapToZeroCents) 0 else cents
    }
}
