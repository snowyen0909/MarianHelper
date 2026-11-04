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
    DIALOG_LOG("對話紀錄"),
    TAP_ANY("結算/解鎖"),
    PREPARE("出擊準備"),
    MAP("大地圖"),
    UNKNOWN("戰鬥中/讀取中"),
}

/** isWord = true 表示單一個字詞（OCR element），false 表示整行。 */
data class TextLine(val text: String, val box: Rect, val isWord: Boolean = false) {
    val norm = text.replace(" ", "").uppercase()
}

class ScreenReader {
    private val recognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    suspend fun read(bmp: Bitmap): List<TextLine> {
        val result = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
        val out = ArrayList<TextLine>()
        for (b in result.textBlocks) for (l in b.lines) {
            l.boundingBox?.let { out += TextLine(l.text, it) }
            for (e in l.elements) e.boundingBox?.let { out += TextLine(e.text, it, isWord = true) }
        }
        return out
    }

    fun close() = recognizer.close()

    companion object {
        fun find(lines: List<TextLine>, vararg keys: String): TextLine? =
            lines.firstOrNull { l -> !l.isWord && keys.any { l.norm.contains(it) } }

        /** 找單一個字詞，例如「HIDE AUTO LOG SKIP」裡只要 SKIP 那個字的位置。 */
        fun findWord(lines: List<TextLine>, vararg keys: String): TextLine? =
            lines.firstOrNull { l -> l.isWord && keys.any { l.norm.contains(it) } }

        /** 左上小地圖下方的「0/14」計數，有它代表小地圖開著。 */
        fun minimapCounter(lines: List<TextLine>, w: Int, h: Int): TextLine? {
            val re = Regex("""\d{1,3}/\d{1,3}$""")
            // 小地圖在左上，放大後會跑到畫面中央，所以只排除頂部資源列與底部按鈕
            return lines.firstOrNull { l ->
                re.containsMatchIn(l.norm) &&
                    l.box.exactCenterY() in (h * 0.12f)..(h * 0.88f)
            }
        }

        fun classify(lines: List<TextLine>, height: Int): ScreenType {
            if (find(lines, "FAILED", "重新挑戰", "重新挑战") != null) return ScreenType.FAILED
            val dialog = find(lines, "DIALOGUE")
            if (dialog != null && dialog.box.centerY() < height * 0.12f) return ScreenType.DIALOG_LOG
            val skip = findWord(lines, "SKIP")
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
