package io.github.genetzhs.nvrdeck

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Μετά από επανεκκίνηση του κινητού: ξανά οι ειδοποιήσεις, αν είναι ενεργές. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED && i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val s = runCatching { Store(ctx).data.value.settings }.getOrNull() ?: return
        if (s.aiAlerts) runCatching { AlertService.start(ctx) }
    }
}
