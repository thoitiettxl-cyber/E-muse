package io.github.thoitiet.emuse

import android.app.Application

class MuseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HiddenApi.applyDefaults(this)
    }
}
