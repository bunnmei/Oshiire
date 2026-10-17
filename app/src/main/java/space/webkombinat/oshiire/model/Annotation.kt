package space.webkombinat.oshiire.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 写真への書き込み。座標は画像の幅・高さを 1 とした正規化座標（0..1）で持つので、
 * 表示サイズ・書き出しサイズが変わっても同じ位置に描ける。
 * 線の太さ・文字の大きさは画像の短辺に対する割合。
 */
@Serializable
data class Pt(val x: Float, val y: Float) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
}

@Serializable
sealed interface Shape {
    /** ARGB */
    val color: Long
    val width: Float

    fun translate(d: Pt): Shape

    @Serializable
    @SerialName("pen")
    data class Pen(val points: List<Pt>, override val color: Long, override val width: Float) : Shape {
        override fun translate(d: Pt) = copy(points = points.map { it + d })
    }

    @Serializable
    @SerialName("ellipse")
    data class Ellipse(val start: Pt, val end: Pt, override val color: Long, override val width: Float) : Shape {
        override fun translate(d: Pt) = copy(start = start + d, end = end + d)
    }

    @Serializable
    @SerialName("rect")
    data class Rect(val start: Pt, val end: Pt, override val color: Long, override val width: Float) : Shape {
        override fun translate(d: Pt) = copy(start = start + d, end = end + d)
    }

    @Serializable
    @SerialName("arrow")
    data class Arrow(val start: Pt, val end: Pt, override val color: Long, override val width: Float) : Shape {
        override fun translate(d: Pt) = copy(start = start + d, end = end + d)
    }

    /** [width] は文字の高さ（短辺比） */
    @Serializable
    @SerialName("text")
    data class Text(val at: Pt, val text: String, override val color: Long, override val width: Float) : Shape {
        override fun translate(d: Pt) = copy(at = at + d)
    }
}

@Serializable
data class AnnotationDoc(
    val version: Int = 1,
    val shapes: List<Shape> = emptyList(),
) {
    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }

        fun fromJson(json: String): AnnotationDoc = JSON.decodeFromString(serializer(), json)
    }
}

/** 描画・当たり判定の計算。ピクセル座標 (px) で計算する */
object AnnotationGeometry {

    /** 矢じりの 2 点。[length] は矢じりの長さ */
    fun arrowHead(sx: Float, sy: Float, ex: Float, ey: Float, length: Float, angleDeg: Float = 28f): List<Pair<Float, Float>> {
        val theta = atan2(ey - sy, ex - sx)
        val a = Math.toRadians(angleDeg.toDouble())
        return listOf(theta + Math.PI - a, theta + Math.PI + a).map { t ->
            (ex + length * cos(t)).toFloat() to (ey + length * sin(t)).toFloat()
        }
    }

    /** 点 p から線分 a-b までの距離 */
    fun distanceToSegment(p: Pt, a: Pt, b: Pt): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    /**
     * タップ位置 [p]（ピクセル）に一番近い図形の番号。[tolerance] ピクセル以内になければ -1。
     * 上に描いたもの（リストの後ろ）を優先する。
     */
    fun hitTest(shapes: List<Shape>, p: Pt, w: Float, h: Float, tolerance: Float, textWidth: (Shape.Text) -> Float): Int {
        fun px(q: Pt) = Pt(q.x * w, q.y * h)
        for (i in shapes.indices.reversed()) {
            val d = when (val s = shapes[i]) {
                is Shape.Pen -> s.points.map(::px).zipWithNext { a, b -> distanceToSegment(p, a, b) }.minOrNull()
                    ?: s.points.firstOrNull()?.let { hypot(p.x - it.x * w, p.y - it.y * h) } ?: Float.MAX_VALUE
                is Shape.Arrow -> distanceToSegment(p, px(s.start), px(s.end))
                is Shape.Rect, is Shape.Ellipse -> {
                    // 枠の内側をタップしても選べるようにする
                    val (a, b) = if (s is Shape.Rect) px(s.start) to px(s.end) else px((s as Shape.Ellipse).start) to px(s.end)
                    val inside = p.x in minOf(a.x, b.x)..maxOf(a.x, b.x) && p.y in minOf(a.y, b.y)..maxOf(a.y, b.y)
                    if (inside) 0f else Float.MAX_VALUE
                }
                is Shape.Text -> {
                    val size = s.width * minOf(w, h)
                    val a = px(s.at)
                    val inside = p.x in a.x..(a.x + textWidth(s)) && p.y in (a.y - size)..(a.y + size * 0.3f)
                    if (inside) 0f else Float.MAX_VALUE
                }
            }
            if (d <= tolerance) return i
        }
        return -1
    }
}
