package com.velt

import android.app.Application
import com.velt.data.AppContainer

class VeltApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
