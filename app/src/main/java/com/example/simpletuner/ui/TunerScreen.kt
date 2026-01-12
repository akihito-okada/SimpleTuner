package com.example.simpletuner.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.simpletuner.tuner.AutocorrelationPitchDetector
import com.example.simpletuner.tuner.MicPitchAnalyzer
import com.example.simpletuner.tuner.PitchResult
import com.example.simpletuner.ui.theme.SimpleTunerTheme
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

/**
 * 「合ってる」判定の許容範囲。
 * たとえば ±5 cent 以内なら in tune とする。
 */
private const val IN_TUNE_CENTS = 5

/**
 * チューナー画面（Screenレベル）
 *
 * ここでやっていることは大きく2つだけです。
 * 1) マイク権限を取る
 * 2) ピッチ検出結果（PitchResult）をUIに流し込む
 *
 * なお、この画面でいう pitch は「周波数(Hz) + 近い音名 + cents(ズレ量)」のセットです。
 */
@Composable
fun TunerScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // ---------------------------------------------------------------------
    // 1) マイク権限（RECORD_AUDIO）
    // ---------------------------------------------------------------------
    // 初期状態で権限があるかどうかを確認して State に保持します。
    // ※ remember は「再コンポーズしても値を保持したい」ため。
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    // 権限リクエスト用の launcher。
    // 「権限許可ダイアログ」から帰ってきた結果（granted）で state を更新します。
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    // ---------------------------------------------------------------------
    // 2) ピッチ解析器（MicPitchAnalyzer）
    // ---------------------------------------------------------------------
    // remember しないと、再コンポーズのたびに Analyzer を作り直してしまうので注意。
    // Analyzer は内部にフィルタ状態などを持つので、基本的に Screen と寿命を合わせるのが安全です。
    val analyzer = remember {
        MicPitchAnalyzer(
            context = context.applicationContext,
            detector = AutocorrelationPitchDetector()
        )
    }

    // ---------------------------------------------------------------------
    // 3) 解析結果の State（live）
    // ---------------------------------------------------------------------
    // pitchFlow() から流れてくる「生の推定結果」を保持します。
    // ここでいう live は「今この瞬間にマイクから取れた推定結果」という意味です。
    var pitch by remember { mutableStateOf<PitchResult?>(null) }

    // 権限が取れたら Flow の購読を開始します。
    // collectLatest にしているので、UIが重い瞬間に古い値が溜まるのを避けられます。
    LaunchedEffect(hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        analyzer.pitchFlow().collectLatest { pitch = it }
    }

    // ---------------------------------------------------------------------
    // 4) hold（最後の値を少し保持する）
    // ---------------------------------------------------------------------
    // live の pitch は「瞬間値」なので、音が途切れた瞬間に null になります。
    // そのまま表示すると UI がチカチカするので「直前の値を少し保持」して見た目を安定させます。
    //
    // ここでいう hold は「ロジックをねじ曲げる」ためではなく、
    // 「表示が途切れない」ための UI 仕様です。
    val heldPitch = rememberHeldPitch2Stage(
        pitch = pitch,
    ) as PitchResult?

    // ---------------------------------------------------------------------
    // 5) 見た目のフェード制御（live/hold/none）
    // ---------------------------------------------------------------------
    // 「ライブがある → はっきり表示」
    // 「hold だけ → 少し薄く表示（今は計測できてないけど直前の値は見せる）」
    // 「どっちもない → さらに薄く（待機）」
    val meterAlpha = when {
        pitch != null -> 1f
        heldPitch != null -> 0.75f
        else -> 0.45f
    }

    // keepScreenOn（画面スリープ防止）は「計測中だけ」有効化。
    // チューナーは使ってる間ずっと画面を見るので、地味に効きます。
    val measuring = hasPermission && (pitch != null || heldPitch != null)

    Surface(
        modifier = modifier
            .fillMaxSize()
            .then(if (measuring) Modifier.keepScreenOn() else Modifier)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // -----------------------------------------------------------------
            // 権限がないとき：権限取得UIを出すだけ
            // -----------------------------------------------------------------
            if (!hasPermission) {
                Text("マイク権限が必要です。")
                Button(onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text("権限を許可")
                }
                return@Column
            }

            // -----------------------------------------------------------------
            // 権限があるとき：チューナーUI
            // -----------------------------------------------------------------
            val isHold = pitch == null && heldPitch != null
            val hasLive = pitch != null
            val hasHeld = heldPitch != null

            // 表示は「live が優先」。live がなければ hold を使います。
            val displayPitch = pitch ?: heldPitch

            // メーターに渡す cents（±50 でクランプ）
            // cents は「近い音名からどれだけズレているか」の単位で、
            // 0 がちょうど合ってる、+ が高い、- が低い、という UI 向けの指標です。
            val meterCentsRaw = (displayPitch?.cents?.coerceIn(-50, 50)) ?: 0

            // -----------------------------------------------------------------
            // 表示用 cents の平滑化（UIだけ）
            // -----------------------------------------------------------------
            // 生の cents は細かく揺れるので、UIでは少しだけなめらかにします。
            val smoothCents = rememberSmoothedCents(
                rawCents = meterCentsRaw,
                hasSignal = displayPitch != null,
                alpha = 0.25f
            )

            // -----------------------------------------------------------------
            // 針の表示（live/hold で透過度を変える）
            // -----------------------------------------------------------------
            // 「live のときは濃く」「hold だけなら薄く」「何もなければ消す」
            val needleAlphaTarget = when {
                hasLive -> 1f
                hasHeld -> 0.6f
                else -> 0f
            }

            // animateFloatAsState で alpha を滑らかに遷移させます。
            val needleAlpha by animateFloatAsState(
                targetValue = needleAlphaTarget,
                animationSpec = tween(durationMillis = 180),
                label = "needleAlpha"
            )

            // -----------------------------------------------------------------
            // IN TUNE 判定は「live のときだけ」にする
            // -----------------------------------------------------------------
            // hold は「直前の値」なので、hold だけの状態で in tune 表示すると誤認しやすいです。
            val isInTune = hasLive && abs(smoothCents) <= IN_TUNE_CENTS

            // -----------------------------------------------------------------
            // メーター表示
            // -----------------------------------------------------------------
            AnalogTunerMeter(
                cents = smoothCents,
                isInTune = isInTune,
                needleAlpha = needleAlpha,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .padding(top = 32.dp, bottom = 32.dp)
                    .alpha(meterAlpha)
            )

            // -----------------------------------------------------------------
            // テキスト情報表示（音名 / Hz / cents など）
            // -----------------------------------------------------------------
            TunerInfoArea(
                displayPitch = displayPitch,
                displayCents = smoothCents,
                isHold = isHold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TunerScreenPreview() {
    SimpleTunerTheme {
        TunerScreen()
    }
}
