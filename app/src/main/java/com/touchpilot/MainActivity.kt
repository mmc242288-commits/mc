package com.touchpilot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.content.pm.PackageManager
import android.widget.*

class MainActivity : Activity() {
    private lateinit var apps: List<android.content.pm.ApplicationInfo>
    private lateinit var spinner: Spinner
    private val captureRequest = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { packageManager.getApplicationLabel(it).toString() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 24)
        }
        root.addView(TextView(this).apply { text = "TouchPilot"; textSize = 28f })
        root.addView(TextView(this).apply {
            text = "اختر التطبيق الذي تريد التحكم فيه. ستعمل نقاط اللمس داخله فقط."
            textSize = 16f
            setPadding(0, 12, 0, 16)
        })
        spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            apps.map { packageManager.getApplicationLabel(it).toString() }
        )
        root.addView(spinner)

        addButton(root, "1) السماح بالظهور فوق التطبيقات") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        addButton(root, "2) تفعيل خدمة TouchPilot") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addButton(root, "3) تفعيل التقاط الشاشة") {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
        }
        addButton(root, "فتح التطبيق المختار") { launchSelected() }
        root.addView(TextView(this).apply {
            text = "بعد منح الصلاحيات، افتح التطبيق المختار. استخدم اللوحة العائمة لالتقاط صورة الشاشة وتحديد النقاط."
            textSize = 13f
            setPadding(0, 18, 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun addButton(root: LinearLayout, label: String, action: () -> Unit) {
        root.addView(Button(this).apply { text = label; setOnClickListener { action() } })
    }

    private fun launchSelected() {
        val app = apps.getOrNull(spinner.selectedItemPosition) ?: return
        getSharedPreferences("tp", 0).edit().putString("target", app.packageName).apply()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "امنح صلاحية الظهور فوق التطبيقات أولاً", Toast.LENGTH_LONG).show()
            return
        }
        startService(Intent(this, TouchService::class.java).setAction(TouchService.ACTION_SHOW_PANEL))
        packageManager.getLaunchIntentForPackage(app.packageName)?.let { startActivity(it) }
    }

    @Deprecated("Legacy result API retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, TouchService::class.java).apply {
                action = TouchService.ACTION_START_PROJECTION
                putExtra(TouchService.EXTRA_RESULT_CODE, resultCode)
                putExtra(TouchService.EXTRA_RESULT_DATA, data)
            }
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
            Toast.makeText(this, "تم منح إذن التقاط الشاشة. اختر التطبيق وافتحه.", Toast.LENGTH_LONG).show()
        }
    }
}
