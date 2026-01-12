package com.example.simpletuner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.simpletuner.tuner.PitchResult
import kotlinx.coroutines.delay

/**
 * 2段階ホールド：
 * - pitch が途切れた直後は shortHoldMs だけ粘る（瞬断対策）
 * - しばらく有効値が続いていたら、途切れたときに longHoldMs まで粘る（視認性）
 */
@Composable
fun rememberHeldPitch2Stage(
    pitch: PitchResult?,
    shortHoldMs: Long = 600L,
    longHoldMs: Long = 2000L,
    longHoldAfterMs: Long = 1200L,
): Any? {
    var held by remember { mutableStateOf<Any?>(null) }
    var lastNonNullAt by remember { mutableStateOf<Long?>(null) }
    var lastBecameNonNullAt by remember { mutableStateOf<Long?>(null) }
    var useLongHold by remember { mutableStateOf(false) }

    // pitch が来たら即 held 更新、連続時間計測
    LaunchedEffect(pitch) {
        val now = System.currentTimeMillis()
        if (pitch != null) {
            held = pitch
            lastNonNullAt = now
            if (lastBecameNonNullAt == null) lastBecameNonNullAt = now
            // 連続で有効値が続いたら長ホールド可
            val start = lastBecameNonNullAt ?: now
            useLongHold = (now - start) >= longHoldAfterMs
        } else {
            // null になったら、短 or 長ホールド分だけ保持して消す
            val holdMs = if (useLongHold) longHoldMs else shortHoldMs
            val snapshot = held
            if (snapshot != null) {
                delay(holdMs)
                // その間に再び値が来ていたら消さない
                if (System.currentTimeMillis() - (lastNonNullAt ?: 0L) >= holdMs) {
                    held = null
                    lastBecameNonNullAt = null
                    useLongHold = false
                }
            } else {
                lastBecameNonNullAt = null
                useLongHold = false
            }
        }
    }

    return held
}
