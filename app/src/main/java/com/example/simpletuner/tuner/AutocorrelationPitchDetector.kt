package com.example.simpletuner.tuner

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Autocorrelation（自己相関）を用いたピッチ検出器。
 *
 * ・FFTを使わず、時間領域の周期性から基本周波数を推定する
 * ・単音（ギター等）用途を想定
 * ・ノイズや無音時の誤検出を極力避ける設計
 */
class AutocorrelationPitchDetector(
    private val minHz: Float = 60f,          // 想定する最低周波数（Hz）
    private val maxHz: Float = 1000f,        // 想定する最高周波数（Hz）
    private val minCorrelation: Float = 0.25f, // 周期性の信頼度下限
    private val minRmsDb: Float = -45f,      // 入力音量が小さすぎる場合は無視
    private val maxHzMargin: Float = 0.95f   // 上限付近の誤検出を落とすためのマージン
) : PitchDetector {

    /**
     * PCM サンプル配列から基本周波数（Hz）を推定する
     *
     * @return 推定Hz、または信頼できない場合は null
     */
    override fun estimateFrequencyHz(
        samples: FloatArray,
        sampleRate: Int
    ): Float? {
        // 不正入力チェック
        if (samples.isEmpty()) return null
        if (sampleRate <= 0) return null

        // ------------------------------------------------------------------
        // 1) DC除去（平均値を引く）
        //    マイク入力に含まれる直流成分を除去し、
        //    自己相関の lag=0 付近が異常に強くなるのを防ぐ
        // ------------------------------------------------------------------
        val x = removeDc(samples)

        // ------------------------------------------------------------------
        // 2) 簡易ゲート（RMS dB）
        //    音量が小さすぎる場合はピッチ検出を行わない
        // ------------------------------------------------------------------
        val (energy, rms) = energyAndRms(x)

        // RMS を dB に変換
        val rmsDb = 20f * log10(rms.coerceAtLeast(EPS))
        if (rmsDb < minRmsDb) return null

        // ------------------------------------------------------------------
        // 3) 探索範囲（lag）の決定
        //    lag = 周期（サンプル数）
        //    Hz = sampleRate / lag
        // ------------------------------------------------------------------
        val maxLag = (sampleRate / minHz)
            .toInt()
            .coerceAtMost(x.size - 1)

        val minLag = (sampleRate / maxHz)
            .toInt()
            .coerceAtLeast(1)

        if (minLag >= maxLag) return null

        // ------------------------------------------------------------------
        // 4) 自己相関（非正規化）
        //    周期性の強さを lag ごとに算出
        // ------------------------------------------------------------------
        val corr = autocorrelation(x, minLag, maxLag)

        // ------------------------------------------------------------------
        // 5) 最初の谷の後ろから最大ピークを探す
        //    lag=0 の自己相関ピークを避け、
        //    基音周期に対応するピークを選択する
        // ------------------------------------------------------------------
        val best = findBestLagAfterFirstValley(corr, minLag, maxLag)
            ?: return null

        val bestLag = best.first
        val bestValue = best.second

        // lag → 周波数へ変換
        val hz = sampleRate.toFloat() / bestLag.toFloat()

        // ------------------------------------------------------------------
        // 6) 上限付近の誤検出を除外
        //    lag が小さすぎる場合に出やすい高音誤検出対策
        // ------------------------------------------------------------------
        if (hz > maxHz * maxHzMargin) return null

        // ------------------------------------------------------------------
        // 7) 相関の強さで信頼性チェック
        //    自己相関ピークが全体エネルギーに対して
        //    十分に強いかを判定
        // ------------------------------------------------------------------
        val norm = bestValue / max(energy, EPS)
        if (norm < minCorrelation) return null

        return hz
    }

    /**
     * DC成分（平均値）を除去する
     */
    private fun removeDc(samples: FloatArray): FloatArray {
        var mean = 0f
        for (v in samples) mean += v
        mean /= samples.size

        return FloatArray(samples.size) { i ->
            samples[i] - mean
        }
    }

    /**
     * エネルギーと RMS を同時に計算する
     *
     * energy = Σ(x^2)
     * rms    = sqrt(energy / N)
     */
    private fun energyAndRms(x: FloatArray): Pair<Float, Float> {
        var energy = 0f
        for (v in x) energy += v * v
        val rms = sqrt(energy / x.size)
        return energy to rms
    }

    /**
     * 自己相関を計算する（非正規化）
     *
     * corr[lag] = Σ x[i] * x[i + lag]
     */
    private fun autocorrelation(
        x: FloatArray,
        minLag: Int,
        maxLag: Int
    ): FloatArray {
        // lag ごとの相関値を入れる配列
        // index = lag（ずらし量）
        val corr = FloatArray(maxLag + 1)
        // lag を小さい方から順に試していく
        for (lag in minLag..maxLag) {
            // この lag に対する相関の合計値
            var sum = 0f
            // i + lag が配列範囲を超えないようにするための上限
            val n = x.size - lag

            var i = 0
            while (i < n) {
                // 元の波形 x[i] と
                // lag だけずらした波形 x[i + lag] を掛け合わせる
                //
                // ・形が似ていると正の値が積み上がる
                // ・似ていないと正負が打ち消し合う
                sum += x[i] * x[i + lag]
                i++
            }
            // この lag に対する自己相関値
            corr[lag] = sum
        }
        // lag ごとの相関値をまとめて返す
        return corr
    }

    /**
     * 最初の谷（自己相関が下げ止まり、上昇に転じる点）を見つけ、
     * その後ろで最大となるピークの lag を返す。
     *
     * 戻り値: Pair(bestLag, bestValue)
     */
    private fun findBestLagAfterFirstValley(
        corr: FloatArray,
        minLag: Int,
        maxLag: Int
    ): Pair<Int, Float>? {

        // 谷探索（単純な「上り始め」検出）
        var start = minLag
        while (start + 1 <= maxLag && corr[start + 1] > corr[start]) {
            start++
        }

        // 谷以降で最大ピークを探す
        var bestLag = -1
        var bestValue = 0f
        for (lag in start..maxLag) {
            val v = corr[lag]
            if (v > bestValue) {
                bestValue = v
                bestLag = lag
            }
        }

        return if (bestLag > 0) bestLag to bestValue else null
    }

    private companion object {
        // 0除算・log(0)防止用の極小値
        private const val EPS = 1e-9f
    }
}
