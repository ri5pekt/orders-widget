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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// -------------------------------------------------------------

object PrefKeys {
    val COUNT            = intPreferencesKey("count")
    val UPDATED          = stringPreferencesKey("updated")
    val LOADING          = intPreferencesKey("loading") // 0=false, 1=true
    val ALERT_THRESHOLD  = intPreferencesKey("alert_threshold")
    val ALERTS_ENABLED   = intPreferencesKey("alerts_enabled")
    val LAST_ALERT_AT    = longPreferencesKey("last_alert_at")
    val COOLDOWN_MINUTES = intPreferencesKey("cooldown_minutes")
}

private const val TAG = "OrdersWidget"

private const val SUMMARY_URL =
    "https://www.particleformen.com/wp-json/orderswidget/v1/summary"

private const val API_KEY =
    "pFM_9rJ8dJx7F3w6uQ1Zk5Vt2Nn8Bb4Hs0Ly3Cq7Wd9Xa2Pe6Rm1Tg5Uk9Mh3"

private val HTTP by lazy {
    OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
}

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

            // Render breadcrumb (lightweight, one line per render)
            Log.i(TAG, "render: loading=$loading, updatedRaw='$updatedRaw', count=$count")

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
                    .background(
                        ColorProvider(
                            day   = Color(0xBF222A58),
                            night = Color(0xBF222A58)
                        )
                    )
                    .cornerRadius(8.dp)
                    .padding(pad)
                    // Tap anywhere to refetch
                    .clickable(actionRunCallback<FetchMockAction>())
            ) {
                Column(
                    modifier = GlanceModifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // --- TOP: title row ---
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

                    // --- MIDDLE: big number ---
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

                    // --- BOTTOM: centered timestamp + icon on the right
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
// Action: manual fetch
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

        val result = try {
            Log.i(TAG, "manual: fetch start for $glanceId")
            FileLogger.append(context, "manual: fetch start")
            fetchOrdersSummary(SUMMARY_URL)
        } catch (t: Throwable) {
            Log.e(TAG, "manual fetch threw: ${t.javaClass.simpleName}: ${t.message}")
            Result.failure(t)
        }

        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { p ->
            p.toMutablePreferences().apply {
                if (result.isSuccess) {
                    val dto = result.getOrNull()!!
                    this[PrefKeys.COUNT]   = dto.count
                    this[PrefKeys.UPDATED] = dto.hhmm

                    // --- Alerts: low orders check (cooldown minutes, 0 = no cooldown)
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
                    Log.i(TAG, "manual: success count=${dto.count}, hhmm=${dto.hhmm}")
                    FileLogger.append(context, "manual: success count=${dto.count} hhmm=${dto.hhmm}")
                } else {
                    this[PrefKeys.UPDATED] = "ERR"
                    Log.e(TAG, "manual: result failure, marking ERR")
                    FileLogger.append(context, "manual: failure -> ERR")
                }
                this[PrefKeys.LOADING] = 0
            }
        }
        OrdersWidget().update(context, glanceId)
    }
}

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
// Network
// -------------------------------------------------------------

private data class OrdersSummaryDto(val count: Int, val hhmm: String)

private suspend fun fetchOrdersSummary(url: String): Result<OrdersSummaryDto> =
    withContext(Dispatchers.IO) {
        try {
            val bust = System.currentTimeMillis()
            val sep = if (url.contains("?")) "&" else "?"
            val fullUrl = "$url${sep}key=$API_KEY&t=$bust"
            val req = Request.Builder()
                .url(fullUrl)
                .get()
                .header("Cache-Control", "no-cache")
                .build()
            val resp = HTTP.newCall(req).execute()

            if (!resp.isSuccessful) {
                Log.e(TAG, "fetch failed: HTTP ${resp.code}")
                return@withContext Result.failure(Exception("http_${resp.code}"))
            }

            val body = resp.body?.string().orEmpty()

            val j     = JSONObject(body)
            val count = j.optInt("count", -1)
            val iso   = j.optString("updated_at_utc", j.optString("current_time_utc"))
            val hhmm  = isoUtcToLocalHhMm(iso)

            if (count < 0) {
                Log.e(TAG, "fetch failed: bad_json (count < 0)")
                return@withContext Result.failure(Exception("bad_json"))
            } else {
                Result.success(OrdersSummaryDto(count, hhmm))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "fetch error: ${t.javaClass.simpleName}: ${t.message}")
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

    // KEEP = if already scheduled, do nothing (don’t reset the 20-min window)
    WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
        UNIQUE_WORK,
        ExistingPeriodicWorkPolicy.KEEP,
        request
    )
}

// -------------------------------------------------------------
// Receiver
// -------------------------------------------------------------

class OrdersWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = OrdersWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)

        val pr = goAsync()

        // 1) Schedule auto-refresh reliably
        ensureAutoRefreshScheduled(context)

        // 2) Do initial state & first fetch in a coroutine; finish receiver when done
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

                val r = try {
                    Log.i(TAG, "onEnabled: fetch start for $id")
                    FileLogger.append(context, "onEnabled: fetch start")
                    fetchOrdersSummary(SUMMARY_URL)
                } catch (t: Throwable) {
                    Log.e(TAG, "onEnabled fetch threw: ${t.javaClass.simpleName}: ${t.message}")
                    Result.failure(t)
                }
                Log.i(TAG, "onEnabled: result isSuccess=${r.isSuccess}")
                FileLogger.append(context, "onEnabled: success=${r.isSuccess}")

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
        }.invokeOnCompletion { pr.finish() }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)

        // Re-ensure the schedule in case it was ever lost (KEEP is idempotent)
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
                    Log.w(TAG, "onUpdate: clearing stuck LOADING for $glanceId (ERR state)")
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

                    val r = try {
                        Log.i(TAG, "onUpdate: fetch start for $glanceId")
                        FileLogger.append(context, "onUpdate: fetch start")
                        fetchOrdersSummary(SUMMARY_URL)
                    } catch (t: Throwable) {
                        Log.e(TAG, "onUpdate fetch threw: ${t.javaClass.simpleName}: ${t.message}")
                        Result.failure(t)
                    }
                    Log.i(TAG, "onUpdate: result isSuccess=${r.isSuccess}")
                    FileLogger.append(context, "onUpdate: success=${r.isSuccess}")

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

            val r = fetchOrdersSummary(SUMMARY_URL)

            ids.forEach { id ->
                updateAppWidgetState(applicationContext, PreferencesGlanceStateDefinition, id) { p ->
                    p.toMutablePreferences().apply {
                        if (r.isSuccess) {
                            val dto = r.getOrNull()!!
                            this[PrefKeys.COUNT]   = dto.count
                            this[PrefKeys.UPDATED] = dto.hhmm

                            // Alerts in background too
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
                            // Mark error but keep cadence going
                            this[PrefKeys.UPDATED] = "ERR"
                        }
                        this[PrefKeys.LOADING] = 0
                    }
                }
            }
            ids.forEach { OrdersWidget().update(applicationContext, it) }

            androidx.work.ListenableWorker.Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "worker error: ${t.javaClass.simpleName}: ${t.message}")
            FileLogger.append(applicationContext, "worker error: ${t.javaClass.simpleName}: ${t.message}")
            // Make sure spinners stop and user sees retry hint
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
