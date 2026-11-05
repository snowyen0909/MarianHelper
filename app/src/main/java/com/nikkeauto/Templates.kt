package com.nikkeauto

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 圖片比對（模板比對）：在畫面上找長得和樣本圖一樣的地方。
 *
 * 樣本圖放在 assets/templates/，是從 1080 寬的截圖裁下來的；
 * 畫面分析用 720 寬，所以載入時先縮成 2/3，比對時再試幾種縮放比例以適應不同手機。
 * 演算法：灰階正規化互相關（NCC），先在縮小的畫面粗找，再到原尺寸細修。
 */
object Templates {

    private class Gray(val w: Int, val h: Int, val p: FloatArray)

    data class Match(val x: Float, val y: Float, val score: Float)

    private val tpl = HashMap<String, Bitmap>()

    /** 樣本圖原本截圖的寬度。 */
    private const val SOURCE_WIDTH = 1080f

    fun load(assets: AssetManager) {
        val names = assets.list("templates") ?: return
        for (file in names) {
            if (!file.endsWith(".png")) continue
            assets.open("templates/$file").use { input ->
                val b = BitmapFactory.decodeStream(input) ?: return@use
                val k = Vision.WIDTH / SOURCE_WIDTH
                tpl[file.removeSuffix(".png")] = Bitmap.createScaledBitmap(
                    b, max(4, (b.width * k).roundToInt()), max(4, (b.height * k).roundToInt()), true,
                )
            }
        }
    }

    fun has(name: String) = tpl.containsKey(name)

    /**
     * 在 (x0,y0)-(x1,y1)（畫面比例）範圍內找樣本 name，回傳最佳位置（中心點）與分數 0~1。
     * 找不到樣本或範圍太小時回傳 null。
     */
    fun find(
        bmp: Bitmap, name: String,
        x0: Float = 0f, y0: Float = 0f, x1: Float = 1f, y1: Float = 1f,
        scales: FloatArray = floatArrayOf(0.88f, 1f, 1.12f),
    ): Match? {
        val t = tpl[name] ?: return null
        val rx = (bmp.width * x0).toInt().coerceIn(0, bmp.width - 1)
        val ry = (bmp.height * y0).toInt().coerceIn(0, bmp.height - 1)
        val rw = ((bmp.width * x1).toInt() - rx).coerceAtMost(bmp.width - rx)
        val rh = ((bmp.height * y1).toInt() - ry).coerceAtMost(bmp.height - ry)
        if (rw < 8 || rh < 8) return null
        val g = gray(bmp, rx, ry, rw, rh)

        var best: Match? = null
        for (s in scales) {
            val tw = (t.width * s).roundToInt()
            val th = (t.height * s).roundToInt()
            if (tw < 6 || th < 6 || tw > rw || th > rh) continue
            val ts = Bitmap.createScaledBitmap(t, tw, th, true)
            val tg = gray(ts, 0, 0, tw, th)
            val m = search(g, tg) ?: continue
            if (best == null || m.score > best.score) {
                best = Match(rx + m.x + tw / 2f, ry + m.y + th / 2f, m.score)
            }
        }
        return best
    }

    // ---------------- 內部 ----------------

    private fun gray(b: Bitmap, x: Int, y: Int, w: Int, h: Int): Gray {
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, x, y, w, h)
        val out = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            out[i] = 0.299f * ((c shr 16) and 0xff) + 0.587f * ((c shr 8) and 0xff) + 0.114f * (c and 0xff)
        }
        return Gray(w, h, out)
    }

    /** 每 k×k 取平均縮小。 */
    private fun shrink(g: Gray, k: Int): Gray {
        if (k <= 1) return g
        val w = g.w / k
        val h = g.h / k
        val out = FloatArray(w * h)
        val inv = 1f / (k * k)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (dy in 0 until k) {
                val row = (y * k + dy) * g.w + x * k
                for (dx in 0 until k) s += g.p[row + dx]
            }
            out[y * w + x] = s * inv
        }
        return Gray(w, h, out)
    }

    /** 積分圖，用來快速算任意矩形的總和與平方和。 */
    private class Integral(g: Gray) {
        val w = g.w + 1
        val s = DoubleArray(w * (g.h + 1))
        val s2 = DoubleArray(w * (g.h + 1))

        init {
            for (y in 0 until g.h) {
                var row = 0.0
                var row2 = 0.0
                for (x in 0 until g.w) {
                    val v = g.p[y * g.w + x].toDouble()
                    row += v; row2 += v * v
                    s[(y + 1) * w + x + 1] = s[y * w + x + 1] + row
                    s2[(y + 1) * w + x + 1] = s2[y * w + x + 1] + row2
                }
            }
        }

        fun sum(a: DoubleArray, x: Int, y: Int, tw: Int, th: Int): Double =
            a[(y + th) * w + x + tw] - a[y * w + x + tw] - a[(y + th) * w + x] + a[y * w + x]
    }

    private class Prepared(val g: Gray, val zm: FloatArray, val norm: Double)

    private fun prepare(t: Gray): Prepared {
        val mean = t.p.average().toFloat()
        val zm = FloatArray(t.p.size) { t.p[it] - mean }
        var n = 0.0
        for (v in zm) n += v * v
        return Prepared(t, zm, sqrt(n))
    }

    private fun ncc(g: Gray, ii: Integral, t: Prepared, x: Int, y: Int): Float {
        if (t.norm < 1e-6) return 0f
        val tw = t.g.w
        val th = t.g.h
        val n = tw * th
        val s = ii.sum(ii.s, x, y, tw, th)
        val s2 = ii.sum(ii.s2, x, y, tw, th)
        val varG = s2 - s * s / n
        if (varG < 1e-6) return 0f
        var dot = 0.0
        for (j in 0 until th) {
            val gr = (y + j) * g.w + x
            val tr = j * tw
            for (i in 0 until tw) dot += t.zm[tr + i] * g.p[gr + i]
        }
        return (dot / (t.norm * sqrt(varG))).toFloat()
    }

    private class Hit(val x: Int, val y: Int, val score: Float)

    /** 粗找（縮小 k 倍）→ 取前幾名 → 原尺寸附近細找。回傳左上角座標。 */
    private fun search(g: Gray, t: Gray): Hit? {
        val k = max(1, min(t.w, t.h) / 12)
        val gc = shrink(g, k)
        val tc = prepare(shrink(t, k))
        if (tc.g.w > gc.w || tc.g.h > gc.h) return null
        val iic = Integral(gc)

        // 粗找：保留分數最高、彼此不重疊的 3 個位置
        val tops = ArrayList<Hit>()
        for (y in 0..gc.h - tc.g.h) for (x in 0..gc.w - tc.g.w) {
            val sc = ncc(gc, iic, tc, x, y)
            if (tops.size == 3 && sc <= tops.last().score) continue
            val near = tops.indexOfFirst { kotlin.math.abs(it.x - x) < tc.g.w / 2 && kotlin.math.abs(it.y - y) < tc.g.h / 2 }
            if (near >= 0) {
                if (sc > tops[near].score) tops[near] = Hit(x, y, sc) else continue
            } else {
                tops += Hit(x, y, sc)
            }
            tops.sortByDescending { it.score }
            while (tops.size > 3) tops.removeAt(tops.size - 1)
        }

        // 細找
        val tf = prepare(t)
        val ii = Integral(g)
        var best: Hit? = null
        for (c in tops) {
            val cx = c.x * k
            val cy = c.y * k
            val r = k * 2
            for (y in max(0, cy - r)..min(g.h - t.h, cy + r)) for (x in max(0, cx - r)..min(g.w - t.w, cx + r)) {
                val sc = ncc(g, ii, tf, x, y)
                if (best == null || sc > best.score) best = Hit(x, y, sc)
            }
        }
        return best
    }
}
