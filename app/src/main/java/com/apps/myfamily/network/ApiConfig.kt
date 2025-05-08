package com.apps.myfamily.network
import android.util.Log

object ApiConfig {
init {
        Log.d("ApiConfig", "Base URL: $BASE_URL")
    }
    const val BASE_URL = "http://168.192.1.128:8080"
}