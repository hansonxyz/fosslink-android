package xyz.hanson.fosslink

import android.app.Application
import android.util.Log

class FossLinkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("FossLinkApp", "Application started")
    }
}
