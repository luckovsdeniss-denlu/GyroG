package com.example.gyroshooter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Surface
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.random.Random

class GameView(context: Context) : SurfaceView(context), Runnable, SensorEventListener,
    LocationListener {

    // Flip these to -1f if aiming feels reversed on your phone
    private val invertX = 1f
    private val invertY = 1f
    private val sensitivity = 900f   // pixels per radian

    private data class Target(var x: Float, var y: Float, var r: Float,
                              var vx: Float, var vy: Float, var life: Float)
    private data class Flash(val x: Float, val y: Float, var t: Float)

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31)
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private var vibrationOn = prefs.getBoolean("vibration", true)
    private val vibButton = RectF()
    private var touchOnButton = false

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var location: Location? = null
    private var locationActive = false

    private val rotSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rotMatrix = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)
    private var heading = 0f   // degrees clockwise from north, 0..360

    private var thread: Thread? = null
    @Volatile private var running = false
    private val lock = Any()

    private var cx = 0f; private var cy = 0f
    private val targets = mutableListOf<Target>()
    private val flashes = mutableListOf<Flash>()
    private var score = 0; private var lives = 5
    private var spawnTimer = 0f; private var gameOver = false
    private var lastTouchX = 0f; private var lastTouchY = 0f; private var moved = false

    private val bg = Paint().apply { color = Color.rgb(12, 16, 32) }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 80, 80) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 5f }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(80, 255, 140); style = Paint.Style.STROKE; strokeWidth = 4f }
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW }
    private val compassBg = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = 3f }
    private val northPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 80, 80); textSize = 40f; textAlign = Paint.Align.CENTER
        isFakeBoldText = true }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 40f; textAlign = Paint.Align.CENTER }
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(80, 255, 140); strokeWidth = 5f }
    private val buttonOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 140, 80) }
    private val buttonOff = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 90, 100) }
    private val buttonText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 34f; textAlign = Paint.Align.CENTER }
    private val gpsText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(140, 200, 255); textSize = 36f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 48f }

    fun resume() {
        gyro?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        rotSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        startLocation()
        running = true
        thread = Thread(this).also { it.start() }
    }

    private fun hasLocationPermission() = Build.VERSION.SDK_INT < 23 ||
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    fun startLocation() {
        val lm = locationManager ?: return
        if (locationActive || !hasLocationPermission()) return
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!lm.allProviders.contains(provider)) continue
            try {
                lm.requestLocationUpdates(provider, 1000L, 0f, this, Looper.getMainLooper())
                lm.getLastKnownLocation(provider)?.let { onLocationChanged(it) }
            } catch (e: SecurityException) { return }
        }
        locationActive = true
    }

    private fun stopLocation() {
        locationManager?.removeUpdates(this)
        locationActive = false
    }

    override fun onLocationChanged(loc: Location) {
        synchronized(lock) {
            val cur = location
            // Prefer GPS fixes; accept network fixes only when no recent GPS fix
            if (cur == null || loc.provider == LocationManager.GPS_PROVIDER ||
                cur.provider != LocationManager.GPS_PROVIDER ||
                loc.time - cur.time > 10_000) location = loc
        }
    }

    // Overridden so older Android versions (which call these) don't crash
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    fun pause() {
        stopLocation()
        sensorManager.unregisterListener(this)
        running = false
        thread?.join()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        synchronized(lock) {
            cx = w / 2f; cy = h / 2f
            vibButton.set(w - 250f, 20f, w - 20f, 100f)
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.timestamp == 0L) return
        if (e.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            updateHeading(e.values)
            return
        }
        val dt = 1f / 60f
        synchronized(lock) {
            // Landscape: device X axis = screen vertical, device Y axis = screen horizontal
            cx += -e.values[0] * sensitivity * dt * invertX
            cy += e.values[1] * sensitivity * dt * invertY
            cx = cx.coerceIn(0f, width.toFloat())
            cy = cy.coerceIn(0f, height.toFloat())
        }
    }

    @Suppress("DEPRECATION")
    private fun updateHeading(values: FloatArray) {
        SensorManager.getRotationMatrixFromVector(rotMatrix, values)
        val (axisX, axisY) = when (display?.rotation ?: windowManagerRotation()) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rotMatrix, axisX, axisY, remapped)
        SensorManager.getOrientation(remapped, orientation)
        val deg = Math.toDegrees(orientation[0].toDouble()).toFloat()
        synchronized(lock) { heading = (deg + 360f) % 360f }
    }

    @Suppress("DEPRECATION")
    private fun windowManagerRotation(): Int =
        (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.rotation

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onTouchEvent(e: MotionEvent): Boolean {
        synchronized(lock) {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = e.x; lastTouchY = e.y; moved = false
                    touchOnButton = vibButton.contains(e.x, e.y)
                }
                MotionEvent.ACTION_MOVE -> if (gyro == null && !touchOnButton) {
                    // Fallback aiming by dragging when no gyroscope
                    cx = (cx + e.x - lastTouchX).coerceIn(0f, width.toFloat())
                    cy = (cy + e.y - lastTouchY).coerceIn(0f, height.toFloat())
                    if (hypot(e.x - lastTouchX, e.y - lastTouchY) > 4) moved = true
                    lastTouchX = e.x; lastTouchY = e.y
                }
                MotionEvent.ACTION_UP -> if (touchOnButton) {
                    if (vibButton.contains(e.x, e.y)) toggleVibration()
                } else if (gameOver) restart() else if (!moved) shoot()
            }
        }
        return true
    }

    private fun toggleVibration() {
        vibrationOn = !vibrationOn
        prefs.edit().putBoolean("vibration", vibrationOn).apply()
        vibrate(80)
    }

    @Suppress("DEPRECATION")
    private fun vibrate(ms: Long) {
        if (!vibrationOn) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        // Full amplitude: short pulses at default strength are barely felt on many phones
        val amp = if (v.hasAmplitudeControl()) 255 else VibrationEffect.DEFAULT_AMPLITUDE
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(VibrationEffect.createOneShot(ms, amp),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
        } else if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createOneShot(ms, amp),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
        } else {
            v.vibrate(ms)
        }
    }

    private fun shoot() {
        flashes += Flash(cx, cy, 0.15f)
        val hit = targets.firstOrNull { hypot(it.x - cx, it.y - cy) <= it.r }
        if (hit != null) { targets.remove(hit); score++; vibrate(80) } else vibrate(40)
    }

    private fun restart() {
        targets.clear(); flashes.clear(); score = 0; lives = 5; gameOver = false
        cx = width / 2f; cy = height / 2f
    }

    private fun update(dt: Float) {
        if (gameOver || width == 0) return
        spawnTimer -= dt
        if (spawnTimer <= 0f) {
            spawnTimer = (1.4f - score * 0.03f).coerceAtLeast(0.4f)
            val r = Random.nextFloat() * 40f + 45f
            targets += Target(
                Random.nextFloat() * (width - 2 * r) + r,
                Random.nextFloat() * (height - 2 * r) + r, r,
                (Random.nextFloat() - 0.5f) * 250f, (Random.nextFloat() - 0.5f) * 250f, 3.5f)
        }
        val it = targets.iterator()
        while (it.hasNext()) {
            val t = it.next()
            t.x += t.vx * dt; t.y += t.vy * dt; t.life -= dt
            if (t.x < t.r || t.x > width - t.r) t.vx = -t.vx
            if (t.y < t.r || t.y > height - t.r) t.vy = -t.vy
            if (t.life <= 0f) {
                it.remove(); lives--
                if (lives <= 0) { gameOver = true; vibrate(500) } else vibrate(150)
            }
        }
        flashes.forEach { f -> f.t -= dt }
        flashes.removeAll { f -> f.t <= 0f }
    }

    private fun drawCompass(c: Canvas) {
        val w = width.toFloat()
        val stripW = w * 0.5f
        val left = (w - stripW) / 2f
        val pxPerDeg = stripW / 120f   // 120 degrees visible
        c.drawRect(left, 10f, left + stripW, 100f, compassBg)
        val first = ((heading - 60f) / 15f).toInt() * 15 - 15
        var d = first
        while (d <= heading + 75f) {
            val x = w / 2f + (d - heading) * pxPerDeg
            if (x >= left && x <= left + stripW) {
                val deg = ((d % 360) + 360) % 360
                val name = when (deg) {
                    0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"; else -> null
                }
                if (name != null) {
                    c.drawText(name, x, 60f, if (deg == 0) northPaint else labelPaint)
                } else if (deg % 45 == 0) {
                    c.drawText(deg.toString(), x, 60f, labelPaint.apply { textSize = 28f })
                    labelPaint.textSize = 40f
                } else {
                    c.drawLine(x, 40f, x, 60f, tickPaint)
                }
                c.drawLine(x, 70f, x, 90f, tickPaint)
            }
            d += 15
        }
        c.drawLine(w / 2f, 10f, w / 2f, 100f, pointerPaint)
        c.drawText("${heading.roundToInt() % 360}\u00B0", w / 2f, 140f, labelPaint)
    }

    private fun drawGps(c: Canvas) {
        val loc = location
        val msg = when {
            locationManager == null -> "GPS: not available"
            !hasLocationPermission() -> "GPS: no permission"
            loc == null -> "GPS: searching..."
            else -> String.format(java.util.Locale.US, "GPS: %.5f, %.5f  \u00B1%dm",
                loc.latitude, loc.longitude, loc.accuracy.roundToInt()) +
                (if (loc.hasSpeed()) "  ${(loc.speed * 3.6f).roundToInt()} km/h" else "")
        }
        c.drawText(msg, 30f, height - 30f, gpsText)
    }

    private fun render(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        if (rotSensor != null) drawCompass(c)
        for (t in targets) {
            val rr = t.r * (0.5f + 0.5f * (t.life / 3.5f))
            c.drawCircle(t.x, t.y, rr, targetPaint)
            c.drawCircle(t.x, t.y, rr * 0.55f, ringPaint)
        }
        for (f in flashes) c.drawCircle(f.x, f.y, 30f * (f.t / 0.15f), flashPaint)
        c.drawCircle(cx, cy, 36f, crossPaint)
        c.drawLine(cx - 55, cy, cx + 55, cy, crossPaint)
        c.drawLine(cx, cy - 55, cx, cy + 55, crossPaint)
        c.drawText("Score: $score   Lives: $lives", 30f, 70f, text)
        c.drawRoundRect(vibButton, 20f, 20f, if (vibrationOn) buttonOn else buttonOff)
        c.drawText(if (vibrationOn) "Vibro: ON" else "Vibro: OFF",
            vibButton.centerX(), vibButton.centerY() + 12f, buttonText)
        drawGps(c)
        if (gyro == null) c.drawText("No gyroscope: drag to aim, tap to shoot", 30f, 130f, text)
        if (gameOver) {
            c.drawText("GAME OVER  -  Score $score", width / 2f - 300f, height / 2f, text)
            c.drawText("Tap to play again", width / 2f - 200f, height / 2f + 70f, text)
        }
    }

    override fun run() {
        var last = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val dt = ((now - last) / 1e9f).coerceAtMost(0.05f); last = now
            val h: SurfaceHolder = holder
            if (!h.surface.isValid) { Thread.sleep(16); continue }
            val c = h.lockCanvas() ?: continue
            try { synchronized(lock) { update(dt); render(c) } }
            finally { h.unlockCanvasAndPost(c) }
        }
    }
}
