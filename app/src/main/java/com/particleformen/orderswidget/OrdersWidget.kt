// OrdersWidget.kt
package com.particleformen.orderswidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// -------------------------------------------------------------
// Pref keys
// -------------------------------------------------------------

object PrefKeys {
    val COUNT            = intPreferencesKey("count")
    val UPDATED          = stringPreferencesKey("updated")
    val LOADING          = intPreferencesKey("loading") // 0=false, 1=true
    val ALERT_THRESHOLD  = intPreferencesKey("alert_threshold")
    val ALERTS_ENABLED   = intPreferencesKey("alerts_enabled")
    val LAST_ALERT_AT    = longPreferencesKey("last_alert_at")
    val COOLDOWN_MINUTES = intPreferencesKey("cooldown_minutes")

    val SHOW_TOP_PRODUCTS  = intPreferencesKey("show_top_products")
}

// -------------------------------------------------------------
// Constants
// -------------------------------------------------------------

private const val TAG = "OrdersWidget"

private const val SUMMARY_URL =
    "https://www.particleformen.com/wp-json/orderswidget/v1/summary"

private const val API_KEY =
    "pFM_9rJ8dJx7F3w6uQ1Zk5Vt2Nn8Bb4Hs0Ly3Cq7Wd9Xa2Pe6Rm1Tg5Uk9Mh3"

// Simple OkHttp client
private val HTTP by lazy {
    OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

// -------------------------------------------------------------
// Widget UI
// -------------------------------------------------------------

class OrdersWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode =
        SizeMode.Responsive(setOf(DpSize(120.dp, 60.dp), DpSize(200.dp, 100.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs      = currentState<Preferences>()
            val count      = prefs[PrefKeys.COUNT] ?: -1
            val loading    = (prefs[PrefKeys.LOADING] ?: 0) == 1
            val updatedRaw = prefs[PrefKeys.UPDATED] ?: ""
            val updated    = if (updatedRaw.isBlank()) "-:-" else updatedRaw
            val countText  = if (count < 0) "-" else count.toString()

            val size    = LocalSize.current
            val compact = size.width < 160.dp || size.height < 90.dp

            val titleSize = if (compact) 11.sp else 12.sp
            val countSize = if (compact) 28.sp else 36.sp
            val stampSize = if (compact) 10.sp else 12.sp

            val isError   = updatedRaw == "ERR"
            val stampText = if (isError) "Error, tap to retry" else "Upd at: $updated"

            val pad       = if (compact) 10.dp  else 14.dp
            val iconSize  = if (compact) 14.dp else 16.dp
            val logoSize  = if (compact) 12.dp else 14.dp

            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(Color(0xBF222A58), Color(0xBF222A58)))
                    .cornerRadius(8.dp)
                    .padding(pad)
                    .clickable(actionRunCallback<FetchMockAction>())
            ) {
                Column(
                    modifier = GlanceModifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                        Image(
                            provider = ImageProvider(R.drawable.p_logo),
                            contentDescription = "Logo",
                            modifier = GlanceModifier.size(logoSize)
                        )
                        Spacer(GlanceModifier.width(6.dp))
                        Text(
                            text = "Hourly Orders",
                            style = TextStyle(
                                fontSize   = titleSize,
                                fontWeight = FontWeight.Medium,
                                color      = ColorProvider(Color.White, Color.White)
                            ),
                            maxLines = 1
                        )
                    }

                    Spacer(GlanceModifier.defaultWeight())

                    Text(
                        text  = countText,
                        style = TextStyle(
                            fontSize   = countSize,
                            fontWeight = FontWeight.Bold,
                            color      = ColorProvider(Color.White, Color.White)
                        ),
                        maxLines = 1
                    )

                    Spacer(GlanceModifier.defaultWeight())

                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Vertical.CenterVertically
                    ) {
                        Spacer(GlanceModifier.size(iconSize))

                        Text(
                            text = stampText,
                            modifier = GlanceModifier.defaultWeight(),
                            style = TextStyle(
                                fontSize  = stampSize,
                                color     = ColorProvider(Color.White, Color.White),
                                textAlign = TextAlign.Center
                            ),
                            maxLines = 1
                        )

                        if (loading && !isError) {
                            CircularProgressIndicator(
                                modifier = GlanceModifier.size(iconSize),
                                color = ColorProvider(Color.White, Color.White)
                            )
                        } else {
                            Image(
                                provider = ImageProvider(R.drawable.update),
                                contentDescription = "Update",
                                modifier = GlanceModifier
                                    .size(iconSize)
                                    .clickable(actionRunCallback<FetchMockAction>())
                            )
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// Action: manual fetch (tap)
// -------------------------------------------------------------

class FetchMockAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val isLoading = androidx.glance.appwidget.state.getAppWidgetState(
            context, PreferencesGlanceStateDefinition, glanceId
        )[PrefKeys.LOADING] == 1
        if (isLoading) return

        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { p ->
            p.toMutablePreferences().apply { this[PrefKeys.LOADING] = 1 }
        }
        OrdersWidget().update(context, glanceId)

        val result = fetchOrdersSummary(context, SUMMARY_URL)

        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { p ->
            p.toMutablePreferences().apply {
                if (result.isSuccess) {
                    val dto = result.getOrNull()!!
                    this[PrefKeys.COUNT]   = dto.count
                    this[PrefKeys.UPDATED] = dto.hhmm

                    // Optional alerts (assumes helpers exist in your project)
                    val (enabled, threshold) = readAlertSettings(context)
                    if (enabled && dto.count < threshold) {
                        val now = System.currentTimeMillis()
                        val last = readLastAlertAt(context)
                        val cooldownMin = readCooldownMinutes(context).coerceAtLeast(0)
                        if (cooldownMin == 0 || now - last >= cooldownMin * 60 * 1000L) {
                            ensureAlertChannel(context)
                            notifyLowOrders(context, dto.count, threshold)
                            writeLastAlertAt(context, now)
                        }
                    }
                } else {
                    this[PrefKeys.UPDATED] = "ERR"
                }
                this[PrefKeys.LOADING] = 0
            }
        }
        OrdersWidget().update(context, glanceId)
    }
}

// -------------------------------------------------------------
// Time conversion
// -------------------------------------------------------------

private fun isoUtcToLocalHhMm(iso: String): String {
    val m = Regex("""T(\d{2}):(\d{2})""").find(iso) ?: return "-:-"
    val h = m.groupValues[1].toIntOrNull() ?: return "-:-"
    val min = m.groupValues[2].toIntOrNull() ?: return "-:-"

    val utcTotal = h * 60 + min
    val offsetMin = 180 // fixed +3 hours
    var localTotal = (utcTotal + offsetMin) % (24 * 60)
    if (localTotal < 0) localTotal += 24 * 60

    val hh = localTotal / 60
    val mm = localTotal % 60
    return String.format("%02d:%02d", hh, mm)
}

// -------------------------------------------------------------
// Network fetch (simple, only error logs)
// -------------------------------------------------------------

private data class OrdersSummaryDto(val count: Int, val hhmm: String)

private suspend fun fetchOrdersSummary(
    ctx: Context,
    url: String
): Result<OrdersSummaryDto> = withContext(Dispatchers.IO) {
    try {
        val bust = System.currentTimeMillis()
        val sep = if (url.contains("?")) "&" else "?"
        val fullUrl = "$url${sep}key=$API_KEY&t=$bust"

        val req = Request.Builder()
            .url(fullUrl)
            .get()
            .header("Cache-Control", "no-cache")
            .build()

        HTTP.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.e(TAG, "fetch failed: http_${resp.code}")
                return@withContext Result.failure(Exception("http_${resp.code}"))
            }

            val body = resp.body?.string().orEmpty()
            val j     = JSONObject(body)
            val count = j.optInt("count", -1)
            val iso   = j.optString("updated_at_utc", j.optString("current_time_utc"))
            val hhmm  = isoUtcToLocalHhMm(iso)

            if (count < 0) {
                Log.e(TAG, "fetch failed: bad_json")
                return@withContext Result.failure(Exception("bad_json"))
            }

            Result.success(OrdersSummaryDto(count, hhmm))
        }
    } catch (t: Throwable) {
        Log.e(TAG, "fetch failed: ${t.javaClass.simpleName}: ${t.message}")
        Result.failure(t)
    }
}

// -------------------------------------------------------------
// Work scheduling (idempotent)
// -------------------------------------------------------------

private const val UNIQUE_WORK = "orders_widget_auto_refresh"

private fun ensureAutoRefreshScheduled(ctx: Context) {
    val request = PeriodicWorkRequestBuilder<OrdersAutoRefreshWorker>(
        20, TimeUnit.MINUTES
    ).setConstraints(
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    ).build()

    WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
        UNIQUE_WORK, ExistingPeriodicWorkPolicy.KEEP, request
    )
}

// -------------------------------------------------------------
// Receiver
// -------------------------------------------------------------

class OrdersWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = OrdersWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)

        ensureAutoRefreshScheduled(context)

        CoroutineScope(Dispatchers.IO).launch {
            val manager = GlanceAppWidgetManager(context)
            val ids = manager.getGlanceIds(OrdersWidget::class.java)

            ids.forEach { id ->
                updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { p ->
                    p.toMutablePreferences().apply {
                        this[PrefKeys.COUNT] = -1
                        this[PrefKeys.UPDATED] = ""
                        this[PrefKeys.LOADING] = 1
                    }
                }
                OrdersWidget().update(context, id)

                val r = fetchOrdersSummary(context, SUMMARY_URL)

                updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { p ->
                    p.toMutablePreferences().apply {
                        if (r.isSuccess) {
                            val dto = r.getOrNull()!!
                            this[PrefKeys.COUNT]   = dto.count
                            this[PrefKeys.UPDATED] = dto.hhmm
                        } else {
                            this[PrefKeys.UPDATED] = "ERR"
                        }
                        this[PrefKeys.LOADING] = 0
                    }
                }
                OrdersWidget().update(context, id)
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        ensureAutoRefreshScheduled(context)

        CoroutineScope(Dispatchers.IO).launch {
            val gm = GlanceAppWidgetManager(context)
            appWidgetIds.forEach { appWidgetId ->
                val glanceId = gm.getGlanceIdBy(appWidgetId) ?: return@forEach

                val prefs = androidx.glance.appwidget.state.getAppWidgetState(
                    context, PreferencesGlanceStateDefinition, glanceId
                )
                val hasData = (prefs[PrefKeys.UPDATED] ?: "").isNotBlank()
                val stuckLoading = (prefs[PrefKeys.LOADING] ?: 0) == 1 &&
                        (prefs[PrefKeys.UPDATED] ?: "") == "ERR"

                if (stuckLoading) {
                    updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { p ->
                        p.toMutablePreferences().apply { this[PrefKeys.LOADING] = 0 }
                    }
                    OrdersWidget().update(context, glanceId)
                }

                if (!hasData) {
                    updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { p ->
                        p.toMutablePreferences().apply { this[PrefKeys.LOADING] = 1 }
                    }
                    OrdersWidget().update(context, glanceId)

                    val r = fetchOrdersSummary(context, SUMMARY_URL)

                    updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { p ->
                        p.toMutablePreferences().apply {
                            if (r.isSuccess) {
                                val dto = r.getOrNull()!!
                                this[PrefKeys.COUNT]   = dto.count
                                this[PrefKeys.UPDATED] = dto.hhmm
                            } else {
                                this[PrefKeys.UPDATED] = "ERR"
                            }
                            this[PrefKeys.LOADING] = 0
                        }
                    }
                    OrdersWidget().update(context, glanceId)
                }
            }
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
    }
}

// -------------------------------------------------------------
// Periodic background refresh
// -------------------------------------------------------------

class OrdersAutoRefreshWorker(
    appContext: Context,
    params: androidx.work.WorkerParameters
) : androidx.work.CoroutineWorker(appContext, params) {

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val manager = GlanceAppWidgetManager(applicationContext)
        val ids = manager.getGlanceIds(OrdersWidget::class.java)
        return try {
            ids.forEach { id ->
                updateAppWidgetState(applicationContext, PreferencesGlanceStateDefinition, id) { p ->
                    p.toMutablePreferences().apply { this[PrefKeys.LOADING] = 1 }
                }
            }
            ids.forEach { OrdersWidget().update(applicationContext, it) }

            val r = fetchOrdersSummary(applicationContext, SUMMARY_URL)

            ids.forEach { id ->
                updateAppWidgetState(applicationContext, PreferencesGlanceStateDefinition, id) { p ->
                    p.toMutablePreferences().apply {
                        if (r.isSuccess) {
                            val dto = r.getOrNull()!!
                            this[PrefKeys.COUNT]   = dto.count
                            this[PrefKeys.UPDATED] = dto.hhmm

                            // Optional alerts (assumes helpers exist)
                            val (enabled, threshold) = readAlertSettings(applicationContext)
                            if (enabled && dto.count < threshold) {
                                val now = System.currentTimeMillis()
                                val last = readLastAlertAt(applicationContext)
                                val cooldownMin = readCooldownMinutes(applicationContext).coerceAtLeast(0)
                                if (cooldownMin == 0 || now - last >= cooldownMin * 60 * 1000L) {
                                    ensureAlertChannel(applicationContext)
                                    notifyLowOrders(applicationContext, dto.count, threshold)
                                    writeLastAlertAt(applicationContext, now)
                                }
                            }
                        } else {
                            this[PrefKeys.UPDATED] = "ERR"
                        }
                        this[PrefKeys.LOADING] = 0
                    }
                }
            }
            ids.forEach { OrdersWidget().update(applicationContext, it) }

            androidx.work.ListenableWorker.Result.success()
        } catch (t: Throwable) {
            // Only error log on failure
            Log.e(TAG, "worker fetch failed: ${t.javaClass.simpleName}: ${t.message}")
            ids.forEach { id ->
                updateAppWidgetState(applicationContext, PreferencesGlanceStateDefinition, id) { p ->
                    p.toMutablePreferences().apply {
                        this[PrefKeys.UPDATED] = "ERR"
                        this[PrefKeys.LOADING] = 0
                    }
                }
            }
            ids.forEach { OrdersWidget().update(applicationContext, it) }
            androidx.work.ListenableWorker.Result.retry()
        }
    }
}
