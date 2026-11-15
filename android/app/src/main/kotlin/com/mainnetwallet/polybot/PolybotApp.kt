package com.mainnetwallet.polybot

import android.app.Application

class PolybotApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        graph.start()
    }
}
