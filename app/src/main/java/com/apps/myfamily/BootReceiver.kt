package com.apps.myfamily

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName


class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val component = ComponentName(context, CommandJobService::class.java)
            val jobInfo = JobInfo.Builder(1, component)
                .setMinimumLatency(500)
                .setOverrideDeadline(3000)
                .setPersisted(true)
                .build()
            scheduler.schedule(jobInfo)
        }
    }
}
