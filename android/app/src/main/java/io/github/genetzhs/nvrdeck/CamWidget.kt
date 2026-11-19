package io.github.genetzhs.nvrdeck

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Widget αρχικής οθόνης: η τελευταία εικόνα της κύριας κάμερας, πάτημα ανοίγει την εφαρμογή. */
class CamWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = render(ctx, mgr, ids)

    companion object {
        fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val p = ctx.getSharedPreferences("widget", 0)
            val at = p.getLong("at", 0)
            val rv = RemoteViews(ctx.packageName, R.layout.widget)
            val f = widgetFile(ctx)
            if (f.exists()) BitmapFactory.decodeFile(f.path)?.let { rv.setImageViewBitmap(R.id.w_img, it) }
            rv.setTextViewText(R.id.w_title, p.getString("name", null) ?: "NVRDeck")
            rv.setTextViewText(R.id.w_time, if (at == 0L) "Open the app once" else "Last seen " + SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(at)))
            val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            rv.setOnClickPendingIntent(R.id.w_root, pi)
            mgr.updateAppWidget(ids, rv)
        }
    }
}
