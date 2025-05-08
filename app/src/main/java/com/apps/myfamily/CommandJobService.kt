package com.apps.myfamily

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import androidx.core.content.ContextCompat


class CommandJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val intent = Intent(this, CommandService::class.java)
        ContextCompat.startForegroundService(this, intent)
        return false
    }

    override fun onStopJob(params: JobParameters?) = true
}
