// OrdersWidget.kt

package com.particleformen.orderswidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.ConnectException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

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
}

// -------------------------------------------------------------
// Constants
// -------------------------------------------------------------

private const val TAG = "OrdersWidget"

private const val SUMMARY_URL =
    "https://www.particleformen.com/wp-json/orderswidget/v1/summary"

private const val API_KEY =
    "pFM_9rJ8dJx7F3w6uQ1Zk5Vt2Nn8Bb4Hs0Ly3Cq7Wd9Xa2Pe6Rm1Tg5Uk9Mh3"

// Slightly longer timeouts for mobile links
private val HTTP by lazy {
    OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

// -------------------------------------------------------------
// Minimal logging helpers (Logcat + file)
// -------------------------------------------------------------

private fun logMsg(ctx: Context, where: String, msg: String) {
    Log.i(TAG, "$where: $msg")
    FileLogger.append(ctx, "$where: $msg")
}
private fun logWarn(ctx: Context, where: String, msg: String) {
    Log.w(TAG, "$where: $msg")
    FileLogger.append(ctx, "$where WARN: $msg")
}
private fun logErr(ctx: Context, where: String, t: Throwable) {
    Log.e(TAG, "$where: ${t.javaClass.simpleName}: ${t.message}", t)
    FileLogger.append(ctx, "$where ERROR: ${t.javaClass.simpleName}: ${t.message}")
}
private fun ceh(ctx: Context) = CoroutineExceptionHandler { _, t -> logErr(ctx, "Coroutine", t) }

// -------------------------------------------------------------
// Network helpers: prefer validated WIFI, else CELL; request CELL if needed
// -------------------------------------------------------------

private data class NetChoice(val network: Network, val via: String)

private fun chooseValidatedNetwork(ctx: Context): NetChoice? {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    var wifi: NetChoice? = null
    var cell: NetChoice? = null
    var any:  NetChoice? = null
    for (n in cm.allNetworks.orEmpty()) {
        val caps = cm.getNetworkCapabilities(n) ?: continue
        val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!ok) continue
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> if (wifi == null) wifi = NetChoice(n, "WIFI")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> if (cell == null) cell = NetChoice(n, "CELL")
            else -> if (any == null) any = NetChoice(n, "OTHER")
        }
    }
    return wifi ?: cell ?: any
}

// Works on API 24+. We implement our own timeout and swallow OEM SecurityException.
private suspend fun requestCellular(ctx: Context, timeoutMs: Long = 1800): Network? =
    suspendCancellableCoroutine { cont ->
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val handler = Handler(Looper.getMainLooper())
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (cont.isActive) cont.resume(network)
                runCatching { cm.unregisterNetworkCallback(this) }
            }
            override fun onUnavailable() {
                if (cont.isActive) cont.resume(null)
                runCatching { cm.unregisterNetworkCallback(this) }
            }
        }

        val timeoutRunnable = Runnable {
            if (cont.isActive) {
                cont.resume(null)
                runCatching { cm.unregisterNetworkCallback(cb) }
            }
        }
        handler.postDelayed(timeoutRunnable, timeoutMs)

        try { cm.requestNetwork(req, cb) }
        catch (se: SecurityException) {
            // Some Samsung firmwares are picky here; just log and fall back.
            logErr(ctx, "requestCellular", se)
            handler.removeCallbacks(timeoutRunnable)
            runCatching { cm.unregisterNetworkCallback(cb) }
            cont.resume(null)
        }

        cont.invokeOnCancellation {
            handler.removeCallbacks(timeoutRunnable)
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
    }

// Build an OkHttp client bound to the chosen network, with explicit DNS to avoid SAM issues.
private fun clientForNetwork(choice: NetChoice): OkHttpClient {
    val dns: Dns =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    choice.network.getAllByName(hostname).toList()
            }
        } else {
            Dns.SYSTEM
        }

    return HTTP.newBuilder()
        .socketFactory(choice.network.socketFactory)
        .dns(dns)
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
        if (isLoading) {
            logWarn(context, "manual", "tap ignored; already LOADING=1")
            return
        }

        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { p ->
            p.toMutablePreferences().apply { this[PrefKeys.LOADING] = 1 }
        }
        OrdersWidget().update(context, glanceId)
        logMsg(context, "manual", "tap -> fetch start")

        val result = try {
            fetchOrdersSummary(context, SUMMARY_URL)
        } catch (t: Throwable) {
            logErr(context, "manual fetch", t)
            Result.failure(t)
        }

        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { p ->
            p.toMutablePreferences().apply {
                if (result.isSuccess) {
                    val dto = result.getOrNull()!!
                    this[PrefKeys.COUNT]   = dto.count
                    this[PrefKeys.UPDATED] = dto.hhmm

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
                    logMsg(context, "manual", "success count=${dto.count} hhmm=${dto.hhmm}")
                } else {
                    this[PrefKeys.UPDATED] = "ERR"
                    logWarn(context, "manual", "failure -> ERR (${result.exceptionOrNull()?.javaClass?.simpleName})")
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
// Network fetch with retries + validated-network binding
// -------------------------------------------------------------

private data class OrdersSummaryDto(val count: Int, val hhmm: String)

private suspend fun <T> withNetRetries(
    ctx: Context,
    attempts: Int = 3,
    baseDelayMs: Long = 400,
    block: suspend () -> T
): T {
    var last: Throwable? = null
    repeat(attempts) { i ->
        try { return block() } catch (t: Throwable) {
            last = t
            val retryable = t is java.net.UnknownHostException ||
                    t is java.net.SocketTimeoutException ||
                    t is ConnectException
            if (!retryable || i == attempts - 1) throw t
            val backoff = baseDelayMs * (1 shl i) // 400, 800
            logWarn(ctx, "net", "retry ${i+1}/$attempts after ${t.javaClass.simpleName} (sleep=${backoff}ms)")
            // NOTE: we are inside a suspend function; delay() is OK here
            delay(backoff)
        }
    }
    throw last ?: IllegalStateException("unreachable")
}

private suspend fun fetchOrdersSummary(ctx: Context, url: String): Result<OrdersSummaryDto> =
    withContext(Dispatchers.IO) {
        try {
            // 1) Pick a validated network (WIFI preferred, else CELL)
            var choice = chooseValidatedNetwork(ctx)

            // 2) If none validated yet, briefly request CELL (browser-like behavior)
            if (choice == null) {
                logWarn(ctx, "fetch", "no validated network; requesting CELL briefly")
                val cell = requestCellular(ctx)
                if (cell != null) choice = NetChoice(cell, "CELL(requested)")
            }

            if (choice == null) {
                logWarn(ctx, "fetch", "still no validated network → abort")
                return@withContext Result.failure(IllegalStateException("no_internet"))
            }

            logMsg(ctx, "fetch", "using network=${choice.via}")

            val bust = System.currentTimeMillis()
            val sep = if (url.contains("?")) "&" else "?"
            val fullUrl = "$url${sep}key=$API_KEY&t=$bust"

            val dto = withNetRetries(ctx) {
                val client = clientForNetwork(choice!!)
                val req = Request.Builder()
                    .url(fullUrl)
                    .get()
                    .header("Cache-Control", "no-cache")
                    .build()

                val t0 = System.nanoTime()
                val resp = client.newCall(req).execute()
                val ms = (System.nanoTime() - t0) / 1_000_000

                if (!resp.isSuccessful) {
                    resp.close()
                    logWarn(ctx, "fetch", "http=${resp.code} in ${ms}ms")
                    throw Exception("http_${resp.code}")
                }

                val body = resp.body?.string().orEmpty()
                val j     = JSONObject(body)
                val count = j.optInt("count", -1)
                val iso   = j.optString("updated_at_utc", j.optString("current_time_utc"))
                val hhmm  = isoUtcToLocalHhMm(iso)
                logMsg(ctx, "fetch", "ok in ${ms}ms count=$count hhmm=$hhmm")

                if (count < 0) throw Exception("bad_json")
                OrdersSummaryDto(count, hhmm)
            }

            Result.success(dto)
        } catch (t: Throwable) {
            logErr(ctx, "fetch", t)
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
        val pr = try { goAsync() } catch (_: Throwable) { null } // Samsung quirk guard

        ensureAutoRefreshScheduled(context)

        CoroutineScope(Dispatchers.IO + ceh(context)).launch {
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

                val r = try { fetchOrdersSummary(context, SUMMARY_URL) }
                catch (t: Throwable) { logErr(context, "onEnabled fetch", t); Result.failure(t) }

                updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { p ->
                    p.toMutablePreferences().apply {
                        if (r.isSuccess) {
                            val dto = r.getOrNull()!!
                            this[PrefKeys.COUNT]   = dto.count
                            this[PrefKeys.UPDATED] = dto.hhmm
                            logMsg(context, "onEnabled", "success count=${dto.count} hhmm=${dto.hhmm}")
                        } else {
                            this[PrefKeys.UPDATED] = "ERR"
                            logWarn(context, "onEnabled", "failure → ERR (${r.exceptionOrNull()?.javaClass?.simpleName})")
                        }
                        this[PrefKeys.LOADING] = 0
                    }
                }
                OrdersWidget().update(context, id)
            }
        }.invokeOnCompletion { pr?.finish() }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        ensureAutoRefreshScheduled(context)

        CoroutineScope(Dispatchers.IO + ceh(context)).launch {
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

                    val r = try { fetchOrdersSummary(context, SUMMARY_URL) }
                    catch (t: Throwable) { logErr(context, "onUpdate fetch", t); Result.failure(t) }

                    updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { p ->
                        p.toMutablePreferences().apply {
                            if (r.isSuccess) {
                                val dto = r.getOrNull()!!
                                this[PrefKeys.COUNT]   = dto.count
                                this[PrefKeys.UPDATED] = dto.hhmm
                                logMsg(context, "onUpdate", "success count=${dto.count} hhmm=${dto.hhmm}")
                            } else {
                                this[PrefKeys.UPDATED] = "ERR"
                                logWarn(context, "onUpdate", "failure → ERR (${r.exceptionOrNull()?.javaClass?.simpleName})")
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
                            logMsg(applicationContext, "worker", "success count=${dto.count} hhmm=${dto.hhmm}")
                        } else {
                            this[PrefKeys.UPDATED] = "ERR"
                            logWarn(applicationContext, "worker", "failure → ERR (${r.exceptionOrNull()?.javaClass?.simpleName})")
                        }
                        this[PrefKeys.LOADING] = 0
                    }
                }
            }
            ids.forEach { OrdersWidget().update(applicationContext, it) }

            androidx.work.ListenableWorker.Result.success()
        } catch (t: Throwable) {
            logErr(applicationContext, "worker", t)
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
