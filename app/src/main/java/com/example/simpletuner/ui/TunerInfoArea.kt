package com.example.simpletuner.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.simpletuner.tuner.PitchResult

/**
 * チューナーの「数値表示エリア」。
 *
 * ここは UI の都合で、
 * - 周波数（Hz）
 * - 音名（noteName）
 * - ズレ量（cents）
 * - HOLD状態（直前値を表示しているか）
 *
 * をまとめて出しています。
 *
 * displayPitch は「表示したいピッチ結果」で、
 * live（今取れている値）でも hold（直前の値）でもOK、という前提です。
 */
@SuppressLint("DefaultLocale") // String.format を使うため（Locale依存警告を抑制）
@Composable
fun TunerInfoArea(
    displayPitch: PitchResult?,

    // 表示用cents（例: smoothCents）
    // ピッチ検出結果そのものの cents ではなく、UIで整形した cents を渡したいときに使う
    displayCents: Int?,

    // hold 表示中かどうか（音が途切れたが、直前の値を見せている状態）
    isHold: Boolean,

    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp), // ここは高さを固定して、表示が切り替わってもレイアウトが暴れないようにする
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ---------------------------------------------------------
        // 入力がない（pitchが取れていない）ときの表示
        // ---------------------------------------------------------
        if (displayPitch == null) {
            // とりあえず「何も取れていない」を分かりやすく
            Text("…", style = MaterialTheme.typography.displayMedium)

            Spacer(Modifier.height(8.dp))

            Text(
                "音を入力してください（単音推奨）",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            // ここは hold ではないので false 固定
            HoldLabel(isHold = false)
        } else {
            // ---------------------------------------------------------
            // pitchが取れているときの表示
            // ---------------------------------------------------------

            // 周波数表示（Hz）
            // 例: 110.0 Hz のように小数1桁に整形
            Text(
                String.format("%.1f Hz", displayPitch.frequencyHz),
                style = MaterialTheme.typography.displayMedium
            )

            // 音名表示（例: A2, C#4）
            Text(displayPitch.noteName, style = MaterialTheme.typography.headlineLarge)

            // cents 表示
            // 針（メーター）と同期したいので、displayPitch.cents ではなく displayCents を優先する
            val cents = displayCents ?: displayPitch.cents
            val centsText = if (cents >= 0) "+$cents cents" else "$cents cents"
            Text(centsText, style = MaterialTheme.typography.titleLarge)

            Spacer(Modifier.height(8.dp))

            // HOLD 表示（live では消え、hold のときだけ表示）
            HoldLabel(isHold = isHold)
        }
    }
}


/**
 * HOLD ラベルだけを分離。
 *
 * 「表示/非表示でレイアウトが動く」のが嫌なので、
 * alpha で消す（スペースは確保したまま）方式にしています。
 */
@Composable
private fun HoldLabel(isHold: Boolean) {
    Box(
        modifier = Modifier
            .height(20.dp)   // ラベルの高さを固定してレイアウト安定化
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "HOLD",
            style = MaterialTheme.typography.labelMedium,

            // isHold=false のときは透明にして「存在はするが見えない」状態にする
            // → 画面がガタつかない
            modifier = Modifier.alpha(if (isHold) 1f else 0f)
        )
    }
}
