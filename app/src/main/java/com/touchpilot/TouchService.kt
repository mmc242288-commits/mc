package com.touchpilot

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.*
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

class TouchService : Service() {
    private lateinit var windowManager: WindowManager
    private val pointViews = mutableListOf<View>()
    private val pointCoords = mutableListOf<Pair<Float, Float>>()
    private var panel: View? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var running = false
    private var longPress = false
    private val handler = Handler(Looper.getMainLooper())
    private val notificationId = 91

    private val repeatTask = object : Runnable {
        override fun run() {
            if (!running) return
            val accessibility = TouchAccessibilityService.instance
            if (TouchAccessibilityService.isTargetForeground && accessibility != null && pointCoords.isNotEmpty()) {
                val duration = if (longPress) 1200L else 70L
                val accepted = accessibility.dispatchTogether(pointCoords.toList(), duration)
                if (!accepted) {
                    running = false
                    updatePointTouchability(false)
                    Toast.makeText(this@TouchService, "تعذر إرسال اللمسات. تأكد من تفعيل خدمة TouchPilot.", Toast.LENGTH_LONG).show()
                    return
                }
                // Never start a new dispatch while the previous long gesture is still active.
                handler.postDelayed(this, duration + 180L)
            } else {
                handler.postDelayed(this, 350L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PROJECTION -> startProjection(intent)
            ACTION_SHOW_PANEL -> showPanel()
            ACTION_REFRESH_POINTS -> refreshPointViews()
            ACTION_RESTORE_OVERLAYS -> restoreOverlays()
        }
        return START_STICKY
    }

    private fun startProjection(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") (intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent)
        if (resultCode != Activity.RESULT_OK || data == null) return
        startForeground(notificationId, buildNotification(), if (Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection?.stop()
        projection = manager.getMediaProjection(resultCode, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                releaseCaptureResources()
                projection = null
            }
        }, handler)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel("touchpilot_capture", "TouchPilot screen capture", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, "touchpilot_capture").setContentTitle("TouchPilot").setContentText("إذن التقاط الشاشة نشط").setSmallIcon(android.R.drawable.ic_menu_camera).setContentIntent(pending).build()
        else Notification.Builder(this).setContentTitle("TouchPilot").setContentText("إذن التقاط الشاشة نشط").setSmallIcon(android.R.drawable.ic_menu_camera).build()
    }

    private fun overlayParams(x: Int, y: Int, touchable: Boolean = true): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }
    }

    private fun chip(label: String, color: Int) = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(15, 10, 15, 10)
        background = GradientDrawable().apply { setColor(color); cornerRadius = 48f; setStroke(2, Color.WHITE) }
    }

    private fun showPanel() {
        if (panel != null) { panel?.visibility = View.VISIBLE; return }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(8, 8, 8, 8)
            background = GradientDrawable().apply { setColor(0xEE172033.toInt()); cornerRadius = 20f }
        }
        val header = chip("TOUCH PILOT", 0xFF6750A4.toInt())
        box.addView(header)
        addPanelAction(box, "التقاط صورة وتحديد النقاط") { captureScreen() }
        addPanelAction(box, "إضافة نقطة في المنتصف") { addCenterPoint() }
        val modeAction = TextView(this).apply {
            text = if (longPress) "نوع الضغط: مطوّل" else "نوع الضغط: عادي"
            textSize = 13f; setTextColor(Color.WHITE); setPadding(12, 9, 12, 9)
            setOnClickListener {
                longPress = !longPress
                text = if (longPress) "نوع الضغط: مطوّل" else "نوع الضغط: عادي"
                Toast.makeText(this@TouchService, if (longPress) "تم اختيار الضغط المطوّل" else "تم اختيار الضغط العادي", Toast.LENGTH_SHORT).show()
            }
        }
        box.addView(modeAction)
        addPanelAction(box, if (running) "إيقاف" else "تشغيل") { toggleRun() }
        addPanelAction(box, "إغلاق اللوحة") { stopSelf() }
        panel = box
        windowManager.addView(box, overlayParams(24, 170))
        header.setOnTouchListener(dragListener(box))
        loadSavedPoints()
    }

    private fun addPanelAction(parent: LinearLayout, title: String, action: () -> Unit) {
        parent.addView(TextView(this).apply {
            text = title; textSize = 13f; setTextColor(Color.WHITE); setPadding(12, 9, 12, 9)
            setOnClickListener { action() }
        })
    }

    private fun dragListener(view: View): View.OnTouchListener {
        var downX = 0f; var downY = 0f; var originX = 0; var originY = 0
        return View.OnTouchListener { _, event ->
            val params = view.layoutParams as WindowManager.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; originX = params.x; originY = params.y; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = originX + (event.rawX - downX).toInt()
                    params.y = originY + (event.rawY - downY).toInt()
                    windowManager.updateViewLayout(view, params); true
                }
                else -> false
            }
        }
    }

    private fun addCenterPoint() {
        val metrics = resources.displayMetrics
        addPoint(metrics.widthPixels / 2f, metrics.heightPixels / 2f)
        persistPoints()
    }

    private fun addPoint(x: Float, y: Float) {
        val index = pointCoords.size + 1
        pointCoords.add(x to y)
        val view = chip(index.toString(), 0xFFE64A79.toInt())
        pointViews.add(view)
        windowManager.addView(view, overlayParams(max(0, x.toInt() - 22), max(0, y.toInt() - 22), !running))
        view.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val p = v.layoutParams as WindowManager.LayoutParams
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx=e.rawX; sy=e.rawY; ox=p.x; oy=p.y; return true }
                    MotionEvent.ACTION_MOVE -> {
                        p.x=ox+(e.rawX-sx).toInt(); p.y=oy+(e.rawY-sy).toInt()
                        windowManager.updateViewLayout(v,p)
                        val idx=pointViews.indexOf(v)
                        if (idx>=0) pointCoords[idx]=p.x+v.width/2f to p.y+v.height/2f
                        return true
                    }
                    MotionEvent.ACTION_UP -> { persistPoints(); return true }
                }
                return false
            }
        })
    }

    private fun loadSavedPoints() {
        val raw = getSharedPreferences("tp", 0).getString("points", null) ?: return
        try {
            val array = JSONArray(raw)
            val dm = resources.displayMetrics
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                addPoint(item.getDouble("x").toFloat() * dm.widthPixels, item.getDouble("y").toFloat() * dm.heightPixels)
            }
        } catch (_: Exception) { }
    }

    private fun refreshPointViews() {
        panel?.visibility = View.VISIBLE
        pointViews.forEach { try { windowManager.removeView(it) } catch (_: Exception) { } }
        pointViews.clear()
        pointCoords.clear()
        loadSavedPoints()
        restoreOverlays()
    }

    private fun persistPoints() {
        val dm = resources.displayMetrics
        val array = JSONArray()
        pointCoords.forEach { (x, y) ->
            array.put(org.json.JSONObject().put("x", x / dm.widthPixels).put("y", y / dm.heightPixels))
        }
        getSharedPreferences("tp", 0).edit().putString("points", array.toString()).apply()
    }

    private fun updatePointTouchability(disableTouch: Boolean) {
        pointViews.forEach { view ->
            val p = view.layoutParams as WindowManager.LayoutParams
            p.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                if (disableTouch) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0
            windowManager.updateViewLayout(view, p)
        }
    }

    private fun toggleRun() {
        if (!running && pointCoords.isEmpty()) {
            Toast.makeText(this, "حدد نقطة واحدة على الأقل قبل التشغيل", Toast.LENGTH_LONG).show()
            return
        }
        if (!running && TouchAccessibilityService.instance == null) {
            Toast.makeText(this, "فعّل خدمة إمكانية الوصول الخاصة بـ TouchPilot أولاً", Toast.LENGTH_LONG).show()
            return
        }
        running = !running
        updatePointTouchability(running)
        if (running) handler.post(repeatTask) else handler.removeCallbacks(repeatTask)
        Toast.makeText(this, if (running) "بدأ التنفيذ عند فتح التطبيق المحدد" else "تم الإيقاف", Toast.LENGTH_SHORT).show()
    }

    private fun captureScreen() {
        if (projection == null) {
            Toast.makeText(this, "فعّل التقاط الشاشة من التطبيق الرئيسي أولاً", Toast.LENGTH_LONG).show()
            return
        }
        // Hide controls so the screenshot is a clean view of the target app.
        (listOfNotNull(panel) + pointViews).forEach { it.visibility = View.INVISIBLE }
        handler.postDelayed({
            try {
                val dm = resources.displayMetrics
                imageReader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 2)
                if (virtualDisplay == null) {
                    virtualDisplay = projection?.createVirtualDisplay(
                        "TouchPilotCapture", dm.widthPixels, dm.heightPixels, dm.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, handler
                    )
                } else {
                    virtualDisplay?.setSurface(imageReader?.surface)
                }
                imageReader?.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val plane = image.planes[0]
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * image.width
                        val padded = Bitmap.createBitmap(
                            image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
                        )
                        padded.copyPixelsFromBuffer(plane.buffer)
                        val bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                        padded.recycle()
                        val file = File(getExternalFilesDir(null), "capture-${System.currentTimeMillis()}.png")
                        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                        virtualDisplay?.setSurface(null)
                        try { imageReader?.close() } catch (_: Exception) { }
                        imageReader = null
                        val editor = Intent(this, PointEditorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        editor.putExtra("image_path", file.absolutePath)
                        startActivity(editor)
                    } catch (e: Exception) {
                        Toast.makeText(this, "فشل حفظ الصورة: ${e.message}", Toast.LENGTH_LONG).show()
                        detachCaptureReader()
                        restoreOverlays()
                    } finally { image.close() }
                }, handler)
                handler.postDelayed({
                    if (imageReader != null) {
                        Toast.makeText(this, "لم تصل صورة من النظام. جرّب مرة أخرى.", Toast.LENGTH_LONG).show()
                        detachCaptureReader(); restoreOverlays()
                    }
                }, 2500)
            } catch (e: Exception) {
                Toast.makeText(this, "تعذر التقاط الشاشة: ${e.message}", Toast.LENGTH_LONG).show()
                detachCaptureReader(); restoreOverlays()
            }
        }, 250)
    }

    private fun restoreOverlays() {
        (listOfNotNull(panel) + pointViews).forEach { it.visibility = View.VISIBLE }
    }

    private fun detachCaptureReader() {
        try { virtualDisplay?.setSurface(null) } catch (_: Exception) { }
        try { imageReader?.close() } catch (_: Exception) { }
        imageReader = null
    }

    private fun releaseCaptureResources() {
        detachCaptureReader()
        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(repeatTask)
        releaseCaptureResources()
        try { projection?.stop() } catch (_: Exception) { }
        projection = null
        (listOfNotNull(panel) + pointViews).forEach { try { windowManager.removeView(it) } catch (_: Exception) { } }
        pointViews.clear()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        const val ACTION_START_PROJECTION = "com.touchpilot.START_PROJECTION"
        const val ACTION_SHOW_PANEL = "com.touchpilot.SHOW_PANEL"
        const val ACTION_REFRESH_POINTS = "com.touchpilot.REFRESH_POINTS"
        const val ACTION_RESTORE_OVERLAYS = "com.touchpilot.RESTORE_OVERLAYS"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }
}
