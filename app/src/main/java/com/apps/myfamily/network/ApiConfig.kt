package com.apps.myfamily.network
import android.util.Log

object ApiConfig {
init {
        Log.d("ApiConfig", "Base URL: $BASE_URL")
    }
    const val BASE_URL = "http://20.20.20.55:8080"
}