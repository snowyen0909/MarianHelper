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
        /** 主線目標：驚嘆號 + 其下方的怪物圈（可能為 null，代表下方看不到怪）。 */
        val mains: List<Pair<Blob, Blob?>>,
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

    fun scanMap(bmp: Bitmap): MapScan {
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
            val ring = ringBelow(d, rings)
            if (ring != null) used += ring
            d to ring
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

    /** 俯視角下的扁橢圓光圈：寬大於高、中空。 */
    private fun isRing(bl: Blob): Boolean =
        bl.w in 40..280 && bl.h in 15..150 &&
            bl.aspect in 1.15f..3.8f && bl.fill <= 0.6f && bl.area >= 120

    private fun ringBelow(d: Blob, rings: List<Blob>): Blob? =
        rings.filter { r ->
            abs(r.cx - d.cx) <= d.w * 1.3f &&
                r.cy > d.b - 10 && r.cy < d.b + d.h * 4.5f
        }.minByOrNull { abs(it.cy - d.b) + abs(it.cx - d.cx) }

    /** 遊戲固定 UI 的位置（以畫面比例表示），這些地方的顏色不算目標。 */
    private fun inUi(bl: Blob, w: Int, h: Int): Boolean {
        val fx = bl.cx / w
        val fy = bl.cy / h
        return fy < 0.15f ||                              // 資源列、任務列
            (fx < 0.42f && fy < 0.20f) ||                 // 左上「野外」標籤一帶（小地圖會先被關掉）
            fy > 0.89f                                     // 返回鍵、區域切換
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
