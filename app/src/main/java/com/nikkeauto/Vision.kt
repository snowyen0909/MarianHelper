package com.nikkeauto

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 大地圖的顏色辨識。所有座標都在「寬度 720」的縮小畫面上計算。
 *
 * 原理：怪物外型每章不同，所以不認怪物本身，只認遊戲固定的 UI 標記：
 *  - 主線驚嘆號：青藍色菱形外框，中間白底
 *  - 支線驚嘆號：橘色菱形外框（忽略）
 *  - 怪物腳下的紅色橢圓光圈
 */
object Vision {
    const val WIDTH = 720

    data class Blob(val l: Int, val t: Int, val r: Int, val b: Int, val area: Int) {
        val w get() = r - l + 1
        val h get() = b - t + 1
        val cx get() = (l + r) / 2f
        val cy get() = (t + b) / 2f
        val fill get() = area.toFloat() / (w * h)
        val aspect get() = w.toFloat() / h
    }

    data class Pt(val x: Float, val y: Float)

    data class MapScan(
        /** 主線目標：驚嘆號 + 其下方怪物的位置（null 代表下方看不到怪）。 */
        val mains: List<Pair<Blob, Pt?>>,
        /** 不屬於主線也不屬於支線的紅圈怪。 */
        val redRings: List<Blob>,
        val sideDiamonds: List<Blob>,
    )

    private const val M_CYAN: Byte = 1
    private const val M_ORANGE: Byte = 2
    private const val M_RED: Byte = 3
    private const val M_WHITE: Byte = 4

    /** 把截圖縮成寬 720 的軟體 Bitmap。 */
    fun normalize(src: Bitmap): Bitmap {
        val h = (src.height * WIDTH.toFloat() / src.width).toInt()
        val soft = if (src.config == Bitmap.Config.HARDWARE)
            src.copy(Bitmap.Config.ARGB_8888, false) else src
        return Bitmap.createScaledBitmap(soft, WIDTH, h, true)
    }

    /**
     * @param topCut 這條線以上都是 UI（資源列、任務欄），依任務欄文字的實際位置算出，換設備也適用。
     * @param exclude 額外要排除的範圍，例如開著的小地圖。
     */
    fun scanMap(bmp: Bitmap, topCut: Float, exclude: List<android.graphics.RectF> = emptyList()): MapScan {
        uiTop = topCut
        uiExclude = exclude
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val cls = classify(px)

        val cyan = blobs(cls, w, h, M_CYAN)
        val orange = blobs(cls, w, h, M_ORANGE)
        val red = blobs(cls, w, h, M_RED)

        val mainDiamonds = cyan.filter { isDiamond(it, cls, w) && !inUi(it, w, h) }
        val sideDiamonds = orange.filter { isDiamond(it, cls, w) && !inUi(it, w, h) }
        val rings = red.filter { isRing(it) && !inUi(it, w, h) }

        val used = HashSet<Blob>()
        val mains = mainDiamonds.map { d ->
            ringBelow(d, rings)?.let { used += it }
            d to monsterBelow(px, w, h, d)
        }
        // 支線驚嘆號下方的圈不算普通怪
        sideDiamonds.forEach { d -> ringBelow(d, rings)?.let { used += it } }

        return MapScan(mains, rings.filter { it !in used }, sideDiamonds)
    }

    /** 我方隊伍位置：鏡頭跟隨隊伍，約在畫面正中央。 */
    fun player(w: Int, h: Int) = Pt(w / 2f, h * 0.49f)

    /** 找出擋在「隊伍 → 目標」路線上的紅圈怪。 */
    fun blocker(scan: MapScan, from: Pt, to: Pt, corridor: Float = 70f): Blob? {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len2 = dx * dx + dy * dy
        if (len2 < 1f) return null
        return scan.redRings
            .mapNotNull { r ->
                val t = ((r.cx - from.x) * dx + (r.cy - from.y) * dy) / len2
                if (t < 0.08f || t > 0.92f) return@mapNotNull null
                val px = from.x + t * dx
                val py = from.y + t * dy
                val d = hypot(r.cx - px, r.cy - py)
                if (d <= corridor) r to t else null
            }
            .minByOrNull { it.second }?.first
    }

    fun nearest(rings: List<Blob>, p: Pt): Blob? =
        rings.minByOrNull { hypot(it.cx - p.x, it.cy - p.y) }

    // ---------------- 內部 ----------------

    private fun classify(px: IntArray): ByteArray {
        val out = ByteArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val b = c and 0xff
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val v = mx
            val d = mx - mn
            val s = if (mx == 0) 0f else d.toFloat() / mx
            if (s <= 0.2f && v >= 200) { out[i] = M_WHITE; continue }
            if (d == 0) continue
            var hue = when (mx) {
                r -> 60f * ((g - b).toFloat() / d)
                g -> 60f * ((b - r).toFloat() / d) + 120f
                else -> 60f * ((r - g).toFloat() / d) + 240f
            }
            if (hue < 0) hue += 360f
            out[i] = when {
                hue in 164f..200f && s >= 0.55f && v >= 190 -> M_CYAN
                hue in 16f..48f && s >= 0.59f && v >= 170 -> M_ORANGE
                (hue <= 16f || hue >= 336f) && s >= 0.35f && v >= 120 -> M_RED
                else -> 0
            }
        }
        return out
    }

    /** 8 連通的連通元件標記。 */
    private fun blobs(cls: ByteArray, w: Int, h: Int, target: Byte, minArea: Int = 80): List<Blob> {
        val seen = BooleanArray(cls.size)
        val stack = IntArray(cls.size)
        val res = ArrayList<Blob>()
        for (start in cls.indices) {
            if (seen[start] || cls[start] != target) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var l = w; var t = h; var r = 0; var b = 0; var area = 0
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w
                val y = p / w
                area++
                if (x < l) l = x; if (x > r) r = x
                if (y < t) t = y; if (y > b) b = y
                for (oy in -1..1) for (ox in -1..1) {
                    if (ox == 0 && oy == 0) continue
                    val nx = x + ox
                    val ny = y + oy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val q = ny * w + nx
                    if (!seen[q] && cls[q] == target) {
                        seen[q] = true
                        stack[sp++] = q
                    }
                }
            }
            if (area >= minArea) res += Blob(l, t, r, b, area)
        }
        return res
    }

    /** 空心菱形外框，中間有白色底。 */
    private fun isDiamond(bl: Blob, cls: ByteArray, w: Int): Boolean {
        if (bl.w !in 30..170 || bl.h !in 30..170) return false
        if (bl.aspect !in 0.65f..1.6f) return false
        if (bl.fill !in 0.08f..0.65f) return false
        // 中央 50% 區域的白色比例
        val x0 = bl.l + bl.w / 4; val x1 = bl.r - bl.w / 4
        val y0 = bl.t + bl.h / 4; val y1 = bl.b - bl.h / 4
        var white = 0; var total = 0
        for (y in y0..y1) for (x in x0..x1) {
            total++
            if (cls[y * w + x] == M_WHITE) white++
        }
        return total > 0 && white.toFloat() / total >= 0.12f
    }

    /**
     * 在驚嘆號正下方找主線怪。主線怪腳下是「粉紅光圈＋亮藍六角形」，
     * 常被怪物的腳擋成好幾段，所以不找完整橢圓，而是：
     * 由上往下掃，找到第一列有粉紅/亮藍像素的位置，取該段的中心。
     */
    private fun monsterBelow(px: IntArray, w: Int, h: Int, d: Blob): Pt? {
        val x0 = max(0, (d.cx - d.w).toInt())
        val x1 = min(w - 1, (d.cx + d.w).toInt())
        val y0 = d.b + 4
        val y1 = min((h * 0.9f).toInt(), (d.b + 4 + d.h * 4.5f).toInt())
        if (y0 >= y1) return null
        val hsv = FloatArray(3)
        fun hit(x: Int, y: Int): Boolean {
            android.graphics.Color.colorToHSV(px[y * w + x], hsv)
            val hu = hsv[0]; val s = hsv[1]; val v = hsv[2] * 255f
            val pink = (hu >= 315f || hu <= 16f) && s >= 0.28f && v >= 140f
            val blue = hu in 195f..235f && s >= 0.45f && v >= 150f
            return pink || blue
        }
        var start = -1
        for (y in y0..y1) {
            var n = 0
            for (x in x0..x1) if (hit(x, y)) n++
            if (n >= 3) { start = y; break }
        }
        if (start < 0) return null
        val end = min(y1, start + (d.h * 1.4f).toInt())
        var sx = 0f; var sy = 0f; var n = 0
        for (y in start..end) for (x in x0..x1) if (hit(x, y)) { sx += x; sy += y; n++ }
        return if (n >= 40) Pt(sx / n, sy / n) else null
    }

    /** 俯視角下的扁橢圓光圈：寬大於高、中空。 */
    private fun isRing(bl: Blob): Boolean =
        bl.w in 40..280 && bl.h in 15..150 &&
            bl.aspect in 1.15f..3.8f && bl.fill <= 0.6f && bl.area >= 120

    private fun ringBelow(d: Blob, rings: List<Blob>): Blob? =
        rings.filter { r ->
            abs(r.cx - d.cx) <= d.w * 1.3f &&
                r.cy > d.b - 10 && r.cy < d.b + d.h * 4.5f
        }.minByOrNull { abs(it.cy - d.b) + abs(it.cx - d.cx) }

    private var uiTop = 0f
    private var uiExclude: List<android.graphics.RectF> = emptyList()

    /** 遊戲固定 UI 的位置，這些地方的顏色不算目標。 */
    private fun inUi(bl: Blob, w: Int, h: Int): Boolean {
        if (bl.cy < uiTop) return true                    // 資源列、任務欄
        if (bl.cy > h * 0.89f) return true                // 返回鍵、區域切換
        return uiExclude.any { it.contains(bl.cx, bl.cy) }
    }

    /** 由「0/14」計數推算小地圖的範圍（正方形）。 */
    fun minimapRect(w: Float, counter: android.graphics.Rect): android.graphics.RectF {
        val cr = counter.right.toFloat()
        val right: Float
        val size: Float
        if (counter.exactCenterX() > w * 0.6f) {          // 放大的置中地圖
            right = (cr - 0.055f * w) / 0.89f
            size = 2 * right - w
        } else {                                          // 左上角小地圖
            val left = 0.035f * w
            right = (cr - 0.055f * left) / 0.945f
            size = right - left
        }
        val bottom = counter.exactCenterY() + 0.11f * size
        return android.graphics.RectF(right - size, bottom - size, right, bottom)
    }

    /**
     * 由小地圖下方「0/14」計數的位置，推算右上角 X 的位置。
     * 小地圖是正方形；左上角小地圖左緣約在 3.5% 寬，放大後的地圖則置中。
     * 推算後再在附近找白色叉叉的像素，取中心點微調。
     */
    fun minimapClosePoint(bmp: Bitmap, counter: android.graphics.Rect): Pt? {
        val box = minimapRect(bmp.width.toFloat(), counter)
        val size = box.width()
        val px = box.right - 0.11f * size
        val py = box.top + 0.09f * size

        val r = (0.07f * size).toInt().coerceAtLeast(6)
        val hsv = FloatArray(3)
        var sx = 0f; var sy = 0f; var n = 0
        for (y in (py.toInt() - r)..(py.toInt() + r)) {
            if (y !in 0 until bmp.height) continue
            for (x in (px.toInt() - r)..(px.toInt() + r)) {
                if (x !in 0 until bmp.width) continue
                android.graphics.Color.colorToHSV(bmp.getPixel(x, y), hsv)
                if (hsv[1] < 0.16f && hsv[2] > 0.84f) { sx += x; sy += y; n++ }
            }
        }
        // 找不到白色叉叉就不點，避免畫面切換中點到別的東西
        return if (n >= 8) Pt(sx / n, sy / n) else null
    }

    /** 在指定範圍內找符合顏色的像素，回傳中心點；數量不足回傳 null。 */
    fun colorCenter(
        bmp: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int,
        hueMin: Float, hueMax: Float, sMin: Float, vMin: Float, minCount: Int,
    ): Pt? {
        val hsv = FloatArray(3)
        var sx = 0f; var sy = 0f; var n = 0
        for (y in y0.coerceAtLeast(0)..y1.coerceAtMost(bmp.height - 1)) {
            for (x in x0.coerceAtLeast(0)..x1.coerceAtMost(bmp.width - 1)) {
                android.graphics.Color.colorToHSV(bmp.getPixel(x, y), hsv)
                if (hsv[0] in hueMin..hueMax && hsv[1] >= sMin && hsv[2] >= vMin) {
                    sx += x; sy += y; n++
                }
            }
        }
        return if (n >= minCount) Pt(sx / n, sy / n) else null
    }

    /** 白色叉叉（X 按鈕）的中心。 */
    fun whiteCenter(bmp: Bitmap, cx: Float, cy: Float, r: Int): Pt? {
        val hsv = FloatArray(3)
        var sx = 0f; var sy = 0f; var n = 0
        for (y in (cy.toInt() - r)..(cy.toInt() + r)) {
            if (y !in 0 until bmp.height) continue
            for (x in (cx.toInt() - r)..(cx.toInt() + r)) {
                if (x !in 0 until bmp.width) continue
                android.graphics.Color.colorToHSV(bmp.getPixel(x, y), hsv)
                if (hsv[1] < 0.16f && hsv[2] > 0.84f) { sx += x; sy += y; n++ }
            }
        }
        return if (n >= 8) Pt(sx / n, sy / n) else null
    }

    /** 兩次截圖的平均差異，用來判斷畫面是否卡住。 */
    fun diff(a: Bitmap, b: Bitmap): Float {
        if (a.width != b.width || a.height != b.height) return 1f
        val step = 8
        var sum = 0L; var n = 0
        var y = 0
        while (y < a.height) {
            var x = 0
            while (x < a.width) {
                val p = a.getPixel(x, y); val q = b.getPixel(x, y)
                sum += abs(((p shr 8) and 0xff) - ((q shr 8) and 0xff))
                n++
                x += step
            }
            y += step
        }
        return if (n == 0) 0f else sum.toFloat() / n / 255f
    }
}
