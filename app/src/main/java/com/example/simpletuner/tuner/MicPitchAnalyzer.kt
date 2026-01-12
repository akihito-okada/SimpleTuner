package com.example.simpletuner.tuner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.example.simpletuner.tuner.MedianFilter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * マイク入力 → ピッチ検出 → UI向けの PitchResult を Flow で流すクラス
 *
 * ・AudioRecord の生PCMを扱う
 * ・「揺れる」「途切れる」前提で安定化処理を挟む
 * ・UIが嘘をつかないようにするための調整レイヤ
 */
class MicPitchAnalyzer(
    private val context: Context,

    // Hz を推定するロジック（自己相関など）
    private val detector: PitchDetector,

    // Hz → note / cents に変換するマッパ
    private val mapper: NoteMapper = NoteMapper(),

    // 音声処理は重いのでバックグラウンドで実行
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,

    // 1秒あたりのサンプル数（Hz計算の基準）
    private val sampleRate: Int = 48_000,

    // --- 安定化パラメータ（体感調整しやすい） ---

    // 弦を弾いた直後（アタック音）はノイズが多いので無視する時間
    private val attackIgnoreMs: Long = 100L,

    // 周波数の中央値フィルタサイズ（外れ値除去）
    private val medianSize: Int = 3,

    // cents を 0 に吸着させる幅（±2cent 以内は 0 扱い）
    private val centsDeadband: Int = 2,

    // --- 検出フレーム設定 ---

    // ピッチ検出に使う固定長フレームサイズ
    // 低音ほど長い波形が必要なので固定にしている
    private val detectFrameSize: Int = 4096,

    // --- ゲート設定（環境依存） ---

    // 音が入ったと判断する音量（dB）
    private val openDb: Float = -72f,

    // 音が切れたと判断する音量（dB）
    private val closeDb: Float = -80f,
) {

    // ---- 状態を持つフィルタ群 ----
    // Flow 内で毎回作り直さず、Analyzer に保持する

    // アタック音を除外するためのフィルタ
    private val stability = PitchStabilityFilter(attackIgnoreMs = attackIgnoreMs)

    // 周波数のブレを抑える中央値フィルタ
    private val median = MedianFilter(size = medianSize)

    // cents の小さな揺れを吸収するヒステリシス
    private val hysteresis = CentsHysteresis(snapToZeroCents = centsDeadband)

    // 音量に基づく開閉ゲート
    private val gate = GateState(openDb = openDb, closeDb = closeDb)

    /**
     * ピッチ検出結果を Flow として返す
     *
     * ・音が安定していないときは null
     * ・安定したときだけ PitchResult を流す
     */
    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun pitchFlow(): Flow<PitchResult?> = callbackFlow {

        // --- パーミッションチェック ---
        if (!hasRecordAudioPermission()) {
            trySend(null)
            close(SecurityException("RECORD_AUDIO permission not granted"))
            return@callbackFlow
        }

        var record: AudioRecord? = null

        // 音声処理は別スレッドで実行
        val job = launch(dispatcher) {
            try {
                // --- AudioRecord 初期化 ---
                val config = AudioConfig(sampleRate = sampleRate)

                record = createAudioRecordOrNull(config) ?: run {
                    trySend(null)
                    close(IllegalStateException("AudioRecord init failed"))
                    return@launch
                }

                // 端末依存の自動音声補正を無効化
                // （チューナーでは予期しない変化になる）
                disableAudioEffects(record)

                // PCM16bit → ShortArray
                val shortBuf = ShortArray(config.bufferSizeInBytes / 2)

                // 固定長フレームを作るためのリングバッファ
                val ring = FloatRingBuffer(capacity = detectFrameSize)

                // --- 計測開始 ---
                record.startRecording()

                while (isActive) {

                    // マイクから読み込み
                    val read = record.read(shortBuf, 0, shortBuf.size)
                    if (read <= 0) continue

                    // PCM(short) → 正規化された float
                    val floatBuf = pcm16ToFloat(shortBuf, read)

                    // リングバッファに追加
                    ring.push(floatBuf)

                    // フレームが溜まるまでは何も出さない
                    if (!ring.isReady) {
                        trySend(null)
                        continue
                    }

                    // --- 音量ゲート判定 ---
                    // 最新の read 分だけで判定することで反応を良くする
                    val rmsDb = rmsDb(floatBuf)
                    val hasSignal = gate.update(rmsDb)

                    // アタック除外＋ゲート
                    val stablePhase = stability.filter(hasSignal)
                    if (!stablePhase || !hasSignal) {
                        trySend(null)
                        continue
                    }

                    // --- ピッチ検出 ---
                    // 固定長フレームを取得
                    val frame = ring.snapshot()

                    val hzRaw = detector.estimateFrequencyHz(frame, sampleRate)
                    if (hzRaw == null) {
                        trySend(null)
                        continue
                    }

                    // 周波数の中央値フィルタでブレを抑える
                    val hz = median.push(hzRaw)

                    // Hz → note / cents に変換
                    val mapped = mapper.map(hz)

                    // cents のヒステリシス（小さな揺れを抑制）
                    val stabilizedCents = hysteresis.apply(mapped.cents)

                    // UI向けの最終結果を流す
                    trySend(
                        mapped.copy(
                            frequencyHz = hz,
                            cents = stabilizedCents
                        )
                    ).isSuccess
                }
            } catch (_: Throwable) {
                // stop/release時の端末差例外は握る
            }
        }

        // Flow がキャンセルされたときの後始末
        awaitClose {
            job.cancel()
            record?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
        }
    }

    /**
     * RECORD_AUDIO パーミッション確認
     */
    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * AudioRecord 設定まとめ
     */
    private data class AudioConfig(
        val sampleRate: Int,
        val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
        val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
        val bufferSizeInBytes: Int = run {
            // 最低サイズの2倍 or 100ms分のどちらか大きい方
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            (minBuf * 2).coerceAtLeast(sampleRate / 10 * 2)
        }
    )

    /**
     * AudioRecord を安全に生成する
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createAudioRecordOrNull(config: AudioConfig): AudioRecord? {
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                config.sampleRate,
                config.channelConfig,
                config.audioFormat,
                config.bufferSizeInBytes
            )
        } catch (_: Throwable) {
            null
        }

        return r?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
    }

    /**
     * 端末依存の音声エフェクトを無効化
     */
    private fun disableAudioEffects(record: AudioRecord) {
        val sessionId = record.audioSessionId
        runCatching { AutomaticGainControl.create(sessionId) }
            .getOrNull()?.apply { enabled = false }
        runCatching { NoiseSuppressor.create(sessionId) }
            .getOrNull()?.apply { enabled = false }
        runCatching { AcousticEchoCanceler.create(sessionId) }
            .getOrNull()?.apply { enabled = false }
    }

    /**
     * PCM16bit(short) → 正規化された float(-1..1)
     */
    private fun pcm16ToFloat(shortBuf: ShortArray, read: Int): FloatArray {
        val out = FloatArray(read)
        for (i in 0 until read) {
            out[i] = shortBuf[i] / 32768f
        }
        return out
    }

    /**
     * 音量（RMS）を dB に変換
     */
    private fun rmsDb(samples: FloatArray): Float {
        var sum = 0f
        for (v in samples) sum += v * v
        val rms = sqrt(sum / samples.size).coerceAtLeast(EPS)
        return 20f * log10(rms)
    }

    /**
     * 固定長の float リングバッファ
     * ・常に最新 detectFrameSize 分を保持
     */
    private class FloatRingBuffer(
        capacity: Int
    ) {
        private val ring = FloatArray(capacity)
        private var pos = 0
        private var filled = 0

        // フレームが満たされたか
        val isReady: Boolean get() = filled >= ring.size

        // 新しいデータを追加
        fun push(input: FloatArray) {
            for (v in input) {
                ring[pos] = v
                pos = (pos + 1) % ring.size
                if (filled < ring.size) filled++
            }
        }

        // 現在のフレームを時系列順で取得
        fun snapshot(): FloatArray {
            val out = FloatArray(ring.size)
            val tail = ring.size - pos
            System.arraycopy(ring, pos, out, 0, tail)
            System.arraycopy(ring, 0, out, tail, pos)
            return out
        }
    }

    private companion object {
        private const val EPS = 1e-9f
    }
}
