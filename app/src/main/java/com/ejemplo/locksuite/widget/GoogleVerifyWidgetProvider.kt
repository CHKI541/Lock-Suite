package com.ejemplo.locksuite.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.ejemplo.locksuite.R
import com.ejemplo.locksuite.ui.verification.GoogleVerificationActivity

/**
 * Widget "Verificar Google" (22/10/2026, B.92): un toque y se abre directo el escáner de
 * QR del microvisor. Es el camino corto para el día en que la computadora pide
 * "Verificá tu información para continuar" con un código QR.
 *
 * Ojo: el lanzador kosher propio de LockSuite NO aloja widgets. En esos equipos el
 * acceso es Ajustes Kosher → "Verificación de Google (escanear QR)".
 */
class GoogleVerifyWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_google_verify)

            val intent = Intent(context, GoogleVerificationActivity::class.java).apply {
                putExtra(GoogleVerificationActivity.EXTRA_START_SCAN, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            // requestCode propio (101/102/103 son de los otros widgets, B.91).
            val pendingIntent = PendingIntent.getActivity(context, 104, intent, flags)
            views.setOnClickPendingIntent(R.id.widget_verify_root, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
