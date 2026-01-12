package com.example.simpletuner.tuner

/**
 * 周波数のブレを抑えるための「中央値フィルタ」。
 *
 * チューナーでは、
 * ・倍音を拾って一瞬だけ 2倍・3倍の周波数が出る
 * ・ノイズで外れ値が混ざる
 * ということがよく起きます。
 *
 * 平均値フィルタだと外れ値に引っ張られやすいので、
 * ここでは「中央値（median）」を使っています。
 */
class MedianFilter(
    // 何個分の履歴から中央値を取るか
    // 小さすぎると効果が弱く、大きすぎると反応が鈍くなる
    private val size: Int = 7
) {
    // 直近の周波数履歴を保持するバッファ
    private val buf = ArrayDeque<Float>()

    /**
     * 新しい値を追加し、現在の中央値を返す。
     *
     * @param v 新しく検出された周波数（Hz）
     * @return 直近 size 個の中央値
     */
    fun push(v: Float): Float {
        // 新しい値を末尾に追加
        buf.addLast(v)

        // サイズを超えたら一番古い値を捨てる
        if (buf.size > size) {
            buf.removeFirst()
        }

        // ソートして中央値を取得
        // 外れ値があっても、中央値はほとんど影響を受けない
        val sorted = buf.sorted()
        return sorted[sorted.size / 2]
    }
}