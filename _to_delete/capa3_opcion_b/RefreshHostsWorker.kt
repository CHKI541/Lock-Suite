package com.ejemplo.locksuite.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ejemplo.locksuite.mdm.NetworkFilterController

class RefreshHostsWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            android.util.Log.i("KosherVPN_B", "Ejecutando actualización de hosts periódica.")
            NetworkFilterController.refreshKosherHostsList(applicationContext)
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
