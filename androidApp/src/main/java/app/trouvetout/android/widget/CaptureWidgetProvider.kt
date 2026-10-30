package app.trouvetout.android.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import app.trouvetout.android.R
import app.trouvetout.android.capture.CaptureActivity

/**
 * Home screen widget (B5): two buttons, speak or type, nothing else — no object, place or count is ever shown
 * (H4). Plain RemoteViews: no library and no periodic update (N6), the buttons never change.
 */
class CaptureWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val views =
            RemoteViews(context.packageName, R.layout.widget_capture).apply {
                setOnClickPendingIntent(
                    R.id.widget_voice,
                    CaptureActivity.pendingIntent(context, CaptureActivity.MODE_VOICE),
                )
                setOnClickPendingIntent(
                    R.id.widget_text,
                    CaptureActivity.pendingIntent(context, CaptureActivity.MODE_TEXT),
                )
            }
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
    }
}
