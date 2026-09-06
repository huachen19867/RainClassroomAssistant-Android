package com.zaqizaba.rainassistant.service

import android.content.Context
import android.content.Intent
import com.zaqizaba.rainassistant.model.WorkPhase
import com.zaqizaba.rainassistant.model.WorkStatus

object StatusBus {
    const val ACTION_STATUS_CHANGED = "com.zaqizaba.rainassistant.STATUS_CHANGED"
    private const val PREFS = "work_status"
    private const val KEY_PHASE = "phase"
    private const val KEY_DETAIL = "detail"
    private const val KEY_UPDATED_AT = "updated_at"

    fun publish(context: Context, phase: WorkPhase, detail: String) {
        val updatedAt = System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PHASE, phase.name)
            .putString(KEY_DETAIL, detail)
            .putLong(KEY_UPDATED_AT, updatedAt)
            .apply()

        context.sendBroadcast(
            Intent(ACTION_STATUS_CHANGED)
                .setPackage(context.packageName)
                .putExtra(KEY_PHASE, phase.name)
                .putExtra(KEY_DETAIL, detail)
                .putExtra(KEY_UPDATED_AT, updatedAt),
        )
    }

    fun read(context: Context): WorkStatus {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val phase = runCatching {
            WorkPhase.valueOf(prefs.getString(KEY_PHASE, WorkPhase.STOPPED.name).orEmpty())
        }.getOrDefault(WorkPhase.STOPPED)
        return WorkStatus(
            phase = phase,
            detail = prefs.getString(KEY_DETAIL, "尚未启动").orEmpty(),
            updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L),
        )
    }
}

