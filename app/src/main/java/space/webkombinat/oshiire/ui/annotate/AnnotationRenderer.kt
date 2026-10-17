package space.webkombinat.oshiire.ui.annotate

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import space.webkombinat.oshiire.model.AnnotationGeometry
import space.webkombinat.oshiire.model.Shape
import kotlin.math.hypot

/**
 * 書き込みを android.graphics.Canvas に描く。画面のプレビューと画像への書き出しの両方で使うので、
 * 見たままの仕上がりになる。[w] [h] は描画先での画像の大きさ。
 */
object AnnotationRenderer {

    fun draw(canvas: Canvas, shapes: List<Shape>, w: Float, h: Float, selected: Int = -1) {
        shapes.forEachIndexed { i, s -> drawShape(canvas, s, w, h, highlight = i == selected) }
    }

    fun textPaint(s: Shape.Text, w: Float, h: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = s.color.toInt()
        textSize = s.width * minOf(w, h)
        typeface = Typeface.DEFAULT_BOLD
    }

    fun textWidth(s: Shape.Text, w: Float, h: Float): Float = textPaint(s, w, h).measureText(s.text)

    private fun drawShape(canvas: Canvas, s: Shape, w: Float, h: Float, highlight: Boolean) {
        val unit = minOf(w, h)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = s.color.toInt()
            strokeWidth = s.width * unit
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        // 写真のどんな色の上でも見えるよう、細い縁取りを下に敷く
        val outline = Paint(stroke).apply {
            color = 0x66000000
            strokeWidth = stroke.strokeWidth + unit * 0.004f
        }
        if (highlight) {
            val hl = Paint(stroke).apply {
                color = 0x8800B0FF.toInt()
                strokeWidth = stroke.strokeWidth + unit * 0.02f
            }
            drawGeometry(canvas, s, w, h, hl)
        }
        when (s) {
            is Shape.Text -> {
                val p = textPaint(s, w, h)
                val shadow = Paint(p).apply {
                    style = Paint.Style.STROKE
                    color = 0xAA000000.toInt()
                    strokeWidth = p.textSize * 0.12f
                    strokeJoin = Paint.Join.ROUND
                }
                canvas.drawText(s.text, s.at.x * w, s.at.y * h, shadow)
                canvas.drawText(s.text, s.at.x * w, s.at.y * h, p)
            }
            else -> {
                drawGeometry(canvas, s, w, h, outline)
                drawGeometry(canvas, s, w, h, stroke)
            }
        }
    }

    private fun drawGeometry(canvas: Canvas, s: Shape, w: Float, h: Float, paint: Paint) {
        when (s) {
            is Shape.Pen -> {
                if (s.points.size == 1) {
                    val p = s.points[0]
                    canvas.drawPoint(p.x * w, p.y * h, paint)
                } else if (s.points.isNotEmpty()) {
                    val path = Path()
                    path.moveTo(s.points[0].x * w, s.points[0].y * h)
                    for (p in s.points.drop(1)) path.lineTo(p.x * w, p.y * h)
                    canvas.drawPath(path, paint)
                }
            }
            is Shape.Ellipse -> canvas.drawOval(rect(s.start.x * w, s.start.y * h, s.end.x * w, s.end.y * h), paint)
            is Shape.Rect -> canvas.drawRect(rect(s.start.x * w, s.start.y * h, s.end.x * w, s.end.y * h), paint)
            is Shape.Arrow -> {
                val sx = s.start.x * w
                val sy = s.start.y * h
                val ex = s.end.x * w
                val ey = s.end.y * h
                canvas.drawLine(sx, sy, ex, ey, paint)
                val len = (paint.strokeWidth * 4f).coerceAtMost(hypot(ex - sx, ey - sy) * 0.6f).coerceAtLeast(paint.strokeWidth * 2)
                AnnotationGeometry.arrowHead(sx, sy, ex, ey, len).forEach { (x, y) -> canvas.drawLine(ex, ey, x, y, paint) }
            }
            is Shape.Text -> {
                val p = textPaint(s, w, h)
                val x = s.at.x * w
                val y = s.at.y * h
                canvas.drawRect(x, y - p.textSize, x + p.measureText(s.text), y + p.textSize * 0.3f, paint)
            }
        }
    }

    private fun rect(x1: Float, y1: Float, x2: Float, y2: Float) = RectF(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
}
