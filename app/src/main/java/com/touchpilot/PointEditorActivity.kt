package com.touchpilot

import android.app.Activity
import android.graphics.*
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

class PointEditorActivity : Activity() {
    private lateinit var editor: PointCanvas
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("image_path")
        val bitmap = BitmapFactory.decodeFile(path)
        if (bitmap == null) {
            Toast.makeText(this, "تعذر فتح صورة الشاشة", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        editor = PointCanvas(bitmap)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply {
            text = "اضغط على الصورة لإضافة نقاط مرقمة. اسحب النقطة لتحريكها."
            textSize = 15f
            setPadding(16, 14, 16, 14)
        })
        root.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply {
            text = "حفظ النقاط والعودة"
            setOnClickListener {
                val array = JSONArray()
                editor.normalizedPoints().forEach { (x, y) ->
                    array.put(JSONObject().put("x", x).put("y", y))
                }
                getSharedPreferences("tp", 0).edit().putString("points", array.toString()).apply()
                startService(android.content.Intent(this@PointEditorActivity, TouchService::class.java)
                    .setAction(TouchService.ACTION_REFRESH_POINTS))
                finish()
            }
        })
        setContentView(root)
    }


    override fun onDestroy() {
        startService(android.content.Intent(this, TouchService::class.java)
            .setAction(TouchService.ACTION_RESTORE_OVERLAYS))
        super.onDestroy()
    }

    private inner class PointCanvas(private val bitmap: Bitmap) : View(this@PointEditorActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val points = mutableListOf<PointF>()
        private var selected = -1
        private var scale = 1f
        private var left = 0f
        private var top = 0f

        init {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            left = (width - bitmap.width * scale) / 2f
            top = (height - bitmap.height * scale) / 2f
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bitmap, null, RectF(left, top, left + bitmap.width * scale, top + bitmap.height * scale), paint)
            points.forEachIndexed { i, p ->
                val x = left + p.x * scale
                val y = top + p.y * scale
                paint.color = 0xDDDB315A.toInt()
                canvas.drawCircle(x, y, 25f, paint)
                paint.color = Color.WHITE
                paint.textSize = 25f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText((i + 1).toString(), x, y + 9f, paint)
            }
        }

        fun normalizedPoints(): List<Pair<Float, Float>> =
            points.map { it.x / bitmap.width.toFloat() to it.y / bitmap.height.toFloat() }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val imageX = (event.x - left) / scale
                    val imageY = (event.y - top) / scale
                    selected = points.indexOfLast {
                        kotlin.math.hypot(it.x - imageX, it.y - imageY) < 55f
                    }
                    if (selected < 0 && imageX in 0f..bitmap.width.toFloat() && imageY in 0f..bitmap.height.toFloat()) {
                        points.add(PointF(imageX, imageY))
                        selected = points.lastIndex
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (selected >= 0) {
                        val x = ((event.x - left) / scale).coerceIn(0f, bitmap.width.toFloat())
                        val y = ((event.y - top) / scale).coerceIn(0f, bitmap.height.toFloat())
                        points[selected].set(x, y)
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> { selected = -1; return true }
            }
            return true
        }
    }
}
