package com.particleformen.orderswidget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlin.math.max

// App-level DataStore (same name you used in MainActivity)
val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "orders_widget")

suspend fun readAlertSettings(ctx: Context): Pair<Boolean, Int> {
    val prefs = ctx.appDataStore.data.first()
    val enabled = (prefs[PrefKeys.ALERTS_ENABLED] ?: 1) == 1
    val threshold = max(0, prefs[PrefKeys.ALERT_THRESHOLD] ?: 3)
    return enabled to threshold
}

suspend fun readLastAlertAt(ctx: Context): Long {
    val prefs = ctx.appDataStore.data.first()
    return prefs[PrefKeys.LAST_ALERT_AT] ?: 0L
}

suspend fun writeLastAlertAt(ctx: Context, ts: Long) {
    ctx.appDataStore.edit { it[PrefKeys.LAST_ALERT_AT] = ts }
}


suspend fun readCooldownMinutes(ctx: Context): Int {
    val prefs = ctx.appDataStore.data.first()
    return (prefs[PrefKeys.COOLDOWN_MINUTES] ?: 60).coerceAtLeast(0)
}
