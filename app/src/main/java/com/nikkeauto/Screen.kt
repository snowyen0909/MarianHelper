package com.nikkeauto

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.tasks.await

/** 用文字判斷目前在哪個畫面。 */
enum class ScreenType(val label: String) {
    FAILED("戰鬥失敗"),
    STORY("劇情"),
    TAP_ANY("結算/解鎖"),
    PREPARE("出擊準備"),
    MAP("大地圖"),
    UNKNOWN("戰鬥中/讀取中"),
}

data class TextLine(val text: String, val box: Rect) {
    val norm = text.replace(" ", "").uppercase()
}

class ScreenReader {
    private val recognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    suspend fun read(bmp: Bitmap): List<TextLine> {
        val result = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
        return result.textBlocks.flatMap { b ->
            b.lines.mapNotNull { l -> l.boundingBox?.let { TextLine(l.text, it) } }
        }
    }

    fun close() = recognizer.close()

    companion object {
        fun find(lines: List<TextLine>, vararg keys: String): TextLine? =
            lines.firstOrNull { l -> keys.any { l.norm.contains(it) } }

        fun classify(lines: List<TextLine>, height: Int): ScreenType {
            if (find(lines, "FAILED", "重新挑戰", "重新挑战") != null) return ScreenType.FAILED
            val skip = find(lines, "SKIP")
            if (skip != null && skip.box.centerY() < height * 0.12f) return ScreenType.STORY
            if (find(lines, "點擊任意", "点击任意", "點擊繼續", "点击继续") != null) return ScreenType.TAP_ANY
            if (find(lines, "進入戰鬥", "进入战斗") != null) return ScreenType.PREPARE
            if (find(lines, "CURRENTAREA", "野外") != null) return ScreenType.MAP
            return ScreenType.UNKNOWN
        }

        /** 讀取「14-9 STAGE」這類關卡編號，失敗通知用。 */
        fun stageName(lines: List<TextLine>): String? {
            val re = Regex("""\d{1,2}-\d{1,2}""")
            return lines.firstNotNullOfOrNull { re.find(it.norm)?.value }
        }

        /** 判斷「快速戰鬥」按鈕是不是亮橘色（可點擊）。 */
        fun isOrangeButton(bmp: Bitmap, box: Rect): Boolean {
            val hsv = FloatArray(3)
            var hit = 0; var n = 0
            // 文字左右兩側取樣按鈕底色
            val ys = listOf(box.top, box.centerY(), box.bottom)
            val xs = listOf(box.left - 12, box.right + 12)
            for (y in ys) for (x in xs) {
                if (x !in 0 until bmp.width || y !in 0 until bmp.height) continue
                Color.colorToHSV(bmp.getPixel(x, y), hsv)
                n++
                if (hsv[0] in 10f..45f && hsv[1] > 0.5f && hsv[2] > 0.6f) hit++
            }
            return n > 0 && hit * 2 >= n
        }
    }
}
