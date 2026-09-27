package com.aiqu.watermark

import android.app.Application
import android.content.Context

class AqWatermarkApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ctx = applicationContext
    }

    companion object {
        lateinit var ctx: Context
            private set
    }
}
