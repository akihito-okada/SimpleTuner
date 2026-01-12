package com.example.simpletuner.tuner

interface PitchDetector {
    /**
     * @param samples PCM [-1, 1] の Float 配列（mono）
     * @param sampleRate サンプルレート
     * @return 推定周波数(Hz)。推定不能なら null
     */
    fun estimateFrequencyHz(samples: FloatArray, sampleRate: Int): Float?
}
