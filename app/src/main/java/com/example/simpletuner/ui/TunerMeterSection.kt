package com.example.simpletuner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * cents 表示をなめらかにするための簡易スムージング関数。
 *
 * ロジック側で計算された cents は瞬間値なので、
 * そのまま UI に出すと数字や針が細かく揺れ続けます。
 *
 * ここでは「表示用」だけを平滑化し、
 * ピッチ検出そのものの値は一切変更しません。
 */
@Composable
fun rememberSmoothedCents(
    rawCents: Int,        // 生の cents（ロジック側の結果）
    hasSignal: Boolean,   // 音が取れているかどうか
    alpha: Float = 0.25f  // 平滑化の強さ（0に近いほどゆっくり追従）
): Int {

    // 平滑化後の内部状態（floatで保持）
    var smoothed by remember { mutableFloatStateOf(0f) }

    /**
     * rawCents または hasSignal が変わったときに更新する。
     *
     * Compose 的には「副作用」として扱うので LaunchedEffect を使う。
     */
    LaunchedEffect(rawCents, hasSignal) {
        smoothed = if (!hasSignal) {
            // 音が途切れたら 0 に戻す
            // （ここを「直前値を保持」にしてもOK。UI方針次第）
            0f
        } else {
            // 指数移動平均（Exponential Moving Average）
            //
            // smoothed = 前回値 * (1 - alpha) + 新しい値 * alpha
            //
            // alpha が小さいほど「ヌルっと」動く
            smoothed * (1f - alpha) + rawCents * alpha
        }
    }

    // UI表示は Int で十分なので丸めて返す
    return smoothed.toInt()
}
