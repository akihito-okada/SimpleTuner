package com.example.simpletuner.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * アナログ風のチューナーメーター。
 *
 * -50..+50 cents を、半円の針として描画します。
 */
@Composable
fun AnalogTunerMeter(
    cents: Int,                  // 表示したいズレ量（-50..+50 を想定）
    isInTune: Boolean,           // 合っている状態か（色などの演出に使う）
    needleAlpha: Float,          // 針のフェード（0..1）
    modifier: Modifier = Modifier,
    minCents: Int = -50,
    maxCents: Int = 50,
    startAngle: Float = 180f,    // 左端（180°）
    sweepDegrees: Float = 180f,  // 半円（180°）
) {
    // 表示範囲外の値が来ても破綻しないようにクランプ
    val clamped = cents.coerceIn(minCents, maxCents)

    // cents → 針の角度へ変換
    val needleTargetAngle = angleForCents(
        cents = clamped,
        minCents = minCents,
        maxCents = maxCents,
        startAngle = startAngle,
        sweepDegrees = sweepDegrees
    )

    // 針の角度をスプリングで滑らかに追従させる（UIが落ち着く）
    val needleAngle by animateFloatAsState(
        targetValue = needleTargetAngle,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "needleAngle"
    )

    // in tune のときだけアクセント色（それ以外は通常色）
    val accentBase = if (isInTune) MaterialTheme.colorScheme.tertiary
    else MaterialTheme.colorScheme.onSurface

    // 針はフェード可能にする（hold時や無音時の演出に便利）
    val needleColor = accentBase.copy(alpha = needleAlpha.coerceIn(0f, 1f))

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            // 描画の基本パラメータ（サイズに追従させる）
            val w = size.width
            val h = size.height

            // 支点は下寄せ（メーターっぽく見える）
            val center = Offset(w / 2f, h * 0.85f)

            // 半径は画面サイズから決める（縦横どちらでも破綻しにくい）
            val radius = min(w, h) * 0.45f

            // アーク（円弧）を描くための矩形
            val arcRect = Rect(
                left = center.x - radius,
                top = center.y - radius,
                right = center.x + radius,
                bottom = center.y + radius
            )

            // -----------------------------------------------------------------
            // 1) アーク（外周）
            // -----------------------------------------------------------------
            drawArc(
                color = Color.LightGray,
                startAngle = startAngle,
                sweepAngle = sweepDegrees,
                useCenter = false,
                topLeft = arcRect.topLeft,
                size = arcRect.size,
                style = Stroke(width = 8.dp.toPx())
            )

            // -----------------------------------------------------------------
            // 2) 目盛り（tick）
            // -----------------------------------------------------------------
            val majorStep = 10
            val minorStep = 5
            for (c in minCents..maxCents step minorStep) {
                val isMajor = (c % majorStep == 0)

                // 主要目盛りは長く・太く
                val tickLen = if (isMajor) radius * 0.16f else radius * 0.10f
                val tickW = if (isMajor) 4.dp.toPx() else 2.dp.toPx()

                val angle = angleForCents(
                    cents = c,
                    minCents = minCents,
                    maxCents = maxCents,
                    startAngle = startAngle,
                    sweepDegrees = sweepDegrees
                )

                // 角度 → 単位ベクトル（方向）
                val dir = angleToUnitVector(angle)

                // 目盛り線の始点・終点
                val p1 = center + dir * (radius - tickLen)
                val p2 = center + dir * radius

                drawLine(
                    color = Color.Gray,
                    start = p1,
                    end = p2,
                    strokeWidth = tickW
                )
            }

            // -----------------------------------------------------------------
            // 3) センター線（0 cents）
            // -----------------------------------------------------------------
            run {
                val angle0 = angleForCents(
                    cents = 0,
                    minCents = minCents,
                    maxCents = maxCents,
                    startAngle = startAngle,
                    sweepDegrees = sweepDegrees
                )

                val dir = angleToUnitVector(angle0)
                val p1 = center + dir * (radius * 0.80f)
                val p2 = center + dir * radius

                // 「合っている」を強調したいので inTune 時は少し太め
                drawLine(
                    color = accentBase.copy(alpha = 0.9f),
                    start = p1,
                    end = p2,
                    strokeWidth = if (isInTune) 8.dp.toPx() else 6.dp.toPx()
                )
            }

            // -----------------------------------------------------------------
            // 4) 針（needle）
            // -----------------------------------------------------------------
            run {
                val needleLen = radius * 0.95f
                val needleHalfWidth = 6.dp.toPx()

                // 針の方向（dir）と、直交方向（perp）を作る
                val dir = angleToUnitVector(needleAngle)
                val perp = Offset(-dir.y, dir.x)

                // 三角形の頂点（先端）と左右の根元
                val tip = center + dir * needleLen
                val left = center + perp * needleHalfWidth
                val right = center - perp * needleHalfWidth

                val path = Path().apply {
                    moveTo(left.x, left.y)
                    lineTo(tip.x, tip.y)
                    lineTo(right.x, right.y)
                    close()
                }

                // needleAlpha=0 なら見えない（色のalphaで制御）
                drawPath(path = path, color = needleColor)
            }

            // -----------------------------------------------------------------
            // 5) 支点（pivot）
            // -----------------------------------------------------------------
            // 針が消えても支点だけ残ると、それっぽさが出る
            drawCircle(Color.Black, radius = 10.dp.toPx(), center = center)
            drawCircle(Color.White, radius = 5.dp.toPx(), center = center)
        }
    }
}

/**
 * cents（-50..+50）を角度に変換する。
 * 例: -50 → startAngle（左端）, +50 → startAngle+sweep（右端）
 */
private fun angleForCents(
    cents: Int,
    minCents: Int,
    maxCents: Int,
    startAngle: Float,
    sweepDegrees: Float
): Float {
    val clamped = cents.coerceIn(minCents, maxCents)

    // 0..1 に正規化
    val t = (clamped - minCents).toFloat() / (maxCents - minCents).toFloat()

    // startAngle から sweep の範囲で線形に変換
    return startAngle + sweepDegrees * t
}

/**
 * 角度（度）を「方向ベクトル」に変換する。
 * Canvas は (cos, sin) の向きで座標を使えるので、そこに合わせている。
 */
private fun angleToUnitVector(angleDeg: Float): Offset {
    val rad = Math.toRadians(angleDeg.toDouble())
    return Offset(cos(rad).toFloat(), sin(rad).toFloat())
}
