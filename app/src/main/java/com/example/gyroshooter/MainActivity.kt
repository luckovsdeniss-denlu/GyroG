package com.example.gyroshooter

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

class MainActivity : Activity() {
    private lateinit var game: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        game = GameView(this)
        setContentView(game)
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION &&
            grantResults.any { it == PackageManager.PERMISSION_GRANTED }) game.startLocation()
    }

    private companion object { const val REQ_LOCATION = 1 }

    override fun onResume() { super.onResume(); game.resume() }
    override fun onPause() { game.pause(); super.onPause() }
}
