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
    CONFIRM_SKIP("確認跳過"),
    CHAPTER_LIST("章節清單"),
    MISSION("任務視窗"),
    TAP_ANY("結算/解鎖"),
    PREPARE("出擊準備"),
    ENTER_PANEL("設施面板"),
    MAP("野外"),
    OUTPOST("前哨基地"),
    ARK("方舟"),
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

        /** 左上角的場景標籤：野外／前哨基地／方舟。只看左上角，避免讀到任務欄裡的「前往【前哨基地】」。 */
        fun scene(lines: List<TextLine>, w: Int, h: Int): ScreenType? {
            val tl = lines.filter { it.box.exactCenterX() < w * 0.3f && it.box.exactCenterY() < h * 0.06f }
            return when {
                tl.any { it.norm.startsWith("野外") } -> ScreenType.MAP
                tl.any { it.norm.startsWith("前哨基地") } -> ScreenType.OUTPOST
                tl.any { it.norm.startsWith("方舟") } -> ScreenType.ARK
                else -> null
            }
        }

        /** 上方任務欄的文字，例如「通關【17-30】」或「前往【方舟】的【米西利斯總部】」。 */
        fun questLine(lines: List<TextLine>, w: Int, h: Int): TextLine? =
            lines.firstOrNull { l ->
                !l.isWord && l.box.exactCenterY() < h * 0.12f && l.box.exactCenterX() > w * 0.25f &&
                    (l.norm.contains("前往") || l.norm.contains("通關") || l.norm.contains("通关"))
            }

        /** 任務「通關【7-1】」的章節數字 7。 */
        fun questChapter(quest: TextLine): Int? =
            Regex("""(\d{1,2})-\d{1,2}""").find(quest.norm)?.groupValues?.get(1)?.toIntOrNull()

        /** 右下角「CURRENT AREA」的大數字（目前所在區域）。左右兩邊較小的是上一區、下一區。 */
        fun currentArea(lines: List<TextLine>, w: Int, h: Int): Int? =
            lines.filter { l ->
                l.box.exactCenterX() > w * 0.5f && l.box.exactCenterY() > h * 0.85f &&
                    Regex("""^\d{1,2}$""").matches(l.norm)
            }.maxByOrNull { it.box.height() }?.norm?.toIntOrNull()

        /** 任務要前往的目標名稱：取最後一組【】裡的文字。 */
        fun questTarget(quest: TextLine): String? {
            val names = Regex("""[【\[]([^】\]]+)[】\]]""").findAll(quest.text).map { it.groupValues[1].trim() }.toList()
            return names.lastOrNull()?.takeIf { it.length >= 2 }
        }

        // 按鈕字體特殊，「戰鬥」常被讀成「戰門」等，所以只比對前兩三個字，並搭配面板上的其他文字
        private val ENTER_BATTLE = arrayOf("進入戰", "进入战", "進入战", "进入戰")
        private val QUICK_BATTLE = arrayOf("快速戰", "快速战")
        private val PREPARE_HINTS = arrayOf(
            "戰場資訊", "战场资讯", "战场資訊", "獎勵資訊", "奖励资讯",
            "WEAKPOINT", "EFFECTIVERANGE", "基準戰鬥力", "我的戰鬥力", "殲滅戰", "歼灭战",
        )

        fun isPrepare(lines: List<TextLine>): Boolean =
            find(lines, *ENTER_BATTLE) != null || lines.count { l -> PREPARE_HINTS.any { l.norm.contains(it) } } >= 2

        fun enterBattle(lines: List<TextLine>): TextLine? = find(lines, *ENTER_BATTLE)
        fun quickBattle(lines: List<TextLine>): TextLine? = find(lines, *QUICK_BATTLE)

        /** 「進入」按鈕（前面可能被讀成打勾符號）。 */
        fun isEnterButton(l: TextLine): Boolean =
            !l.isWord && l.norm.length <= 3 && (l.norm.endsWith("進入") || l.norm.endsWith("进入"))

        fun classify(lines: List<TextLine>, width: Int, height: Int): ScreenType {
            if (find(lines, "FAILED", "重新挑戰", "重新挑战") != null) return ScreenType.FAILED
            val dialog = find(lines, "DIALOGUE")
            if (dialog != null && dialog.box.centerY() < height * 0.12f) return ScreenType.DIALOG_LOG
            if (find(lines, "是否跳過", "是否跳过") != null) return ScreenType.CONFIRM_SKIP
            val skip = findWord(lines, "SKIP")
            if (skip != null && skip.box.centerY() < height * 0.12f) return ScreenType.STORY
            if (find(lines, "CHAPTERLIST") != null) return ScreenType.CHAPTER_LIST
            val mission = find(lines, "MISSION")
            if (mission != null && mission.box.centerY() < height * 0.35f) return ScreenType.MISSION
            if (find(lines, "點擊任意", "点击任意", "點擊繼續", "点击继续", "點擊將開啟", "点击将开启",
                    "下一章節", "下一章节", "MISSIONCLEAR") != null
            ) return ScreenType.TAP_ANY
            if (isPrepare(lines)) return ScreenType.PREPARE
            // 設施面板：下方有「關閉」和「進入」兩顆按鈕
            if (lines.any { isEnterButton(it) } &&
                lines.any { it.norm.contains("關閉") || it.norm.contains("关闭") }
            ) return ScreenType.ENTER_PANEL
            scene(lines, width, height)?.let { return it }
            if (find(lines, "CURRENTAREA") != null) return ScreenType.MAP
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
