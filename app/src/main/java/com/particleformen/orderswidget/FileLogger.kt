package com.particleformen.orderswidget

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLogger {
    private const val NAME = "orders_widget_debug.txt"
    private const val MAX_BYTES = 100 * 1024 // ~100KB cap

    fun append(ctx: Context, line: String) {
        try {
            val f = File(ctx.filesDir, NAME)
            if (f.length() > MAX_BYTES) f.writeText("") // truncate
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            f.appendText("$ts $line\n")
        } catch (_: Throwable) { /* swallow */ }
    }

    fun path(ctx: Context): String = File(ctx.filesDir, NAME).absolutePath
}
