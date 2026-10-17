package space.webkombinat.oshiire.model

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class AnnotationTest {

    private val red = 0xFFF44336

    @Test
    fun json_roundTripsAllShapes() {
        val doc = AnnotationDoc(
            shapes = listOf(
                Shape.Pen(listOf(Pt(0.1f, 0.1f), Pt(0.2f, 0.3f)), red, 0.01f),
                Shape.Ellipse(Pt(0.2f, 0.2f), Pt(0.4f, 0.5f), red, 0.01f),
                Shape.Rect(Pt(0.1f, 0.1f), Pt(0.9f, 0.9f), red, 0.02f),
                Shape.Arrow(Pt(0f, 0f), Pt(1f, 1f), red, 0.006f),
                Shape.Text(Pt(0.5f, 0.5f), "ここ！", 0xFFFFFFFF, 0.06f),
            )
        )
        val json = doc.toJson()
        assertEquals(doc, AnnotationDoc.fromJson(json))
        assert(json.contains("\"type\":\"arrow\""))
    }

    @Test
    fun json_ignoresUnknownKeys() {
        val doc = AnnotationDoc.fromJson("""{"version":1,"future":true,"shapes":[{"type":"rect","start":{"x":0,"y":0},"end":{"x":1,"y":1},"color":1,"width":0.1}]}""")
        assertEquals(1, doc.shapes.size)
    }

    @Test
    fun translate_movesEveryPoint() {
        val d = Pt(0.1f, -0.1f)
        assertEquals(Shape.Arrow(Pt(0.1f, -0.1f), Pt(0.6f, 0.4f), red, 1f), Shape.Arrow(Pt(0f, 0f), Pt(0.5f, 0.5f), red, 1f).translate(d))
        assertEquals(listOf(Pt(0.1f, -0.1f)), (Shape.Pen(listOf(Pt(0f, 0f)), red, 1f).translate(d) as Shape.Pen).points)
    }

    @Test
    fun arrowHead_pointsBackFromTip() {
        // 右向きの矢印 → 矢じりは先端より左、上下対称
        val head = AnnotationGeometry.arrowHead(0f, 0f, 100f, 0f, length = 10f)
        assertEquals(2, head.size)
        head.forEach { (x, y) ->
            assert(x < 100f)
            assertEquals(10f, hypot(100f - x, y), 0.01f)
        }
        assertEquals(head[0].second, -head[1].second, 0.01f)
    }

    @Test
    fun distanceToSegment() {
        assertEquals(5f, AnnotationGeometry.distanceToSegment(Pt(5f, 5f), Pt(0f, 0f), Pt(10f, 0f)), 0.001f)
        // 線分の外側は端点からの距離
        assertEquals(5f, AnnotationGeometry.distanceToSegment(Pt(13f, 4f), Pt(0f, 0f), Pt(10f, 0f)), 0.001f)
        assertEquals(5f, AnnotationGeometry.distanceToSegment(Pt(3f, 4f), Pt(0f, 0f), Pt(0f, 0f)), 0.001f)
    }

    @Test
    fun hitTest_prefersTopmostAndRespectsTolerance() {
        val shapes = listOf(
            Shape.Rect(Pt(0f, 0f), Pt(0.5f, 0.5f), red, 0.01f),
            Shape.Arrow(Pt(0f, 0.25f), Pt(1f, 0.25f), red, 0.01f),
        )
        val w = 200f
        val h = 100f
        fun hit(x: Float, y: Float) = AnnotationGeometry.hitTest(shapes, Pt(x, y), w, h, tolerance = 4f) { 0f }
        assertEquals(1, hit(50f, 26f))   // 矢印の上（上に描いた方が優先）
        assertEquals(0, hit(50f, 10f))   // 四角の内側
        assertEquals(1, hit(180f, 23f))  // 四角の外、矢印の近く
        assertEquals(-1, hit(180f, 80f)) // 何もない
    }

    @Test
    fun hitTest_text_usesMeasuredWidth() {
        val shapes = listOf(Shape.Text(Pt(0.1f, 0.5f), "abc", red, 0.1f))
        // 画像 100x100、文字の高さ 10px、幅 30px とする
        val inside = AnnotationGeometry.hitTest(shapes, Pt(25f, 47f), 100f, 100f, 1f) { 30f }
        val outside = AnnotationGeometry.hitTest(shapes, Pt(45f, 47f), 100f, 100f, 1f) { 30f }
        assertEquals(0, inside)
        assertEquals(-1, outside)
        assert(abs(inside) == 0)
    }
}
