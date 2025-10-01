// ExtendedOrdersWidget.kt
package com.particleformen.orderswidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.CoroutineWorker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

// -------------------------------------------------------------
// Separate Pref keys so they don't collide with the minimal widget
// -------------------------------------------------------------
object ExtPrefKeys {
    val UPDATED              = stringPreferencesKey("ext_updated")          // hh:mm local
    val LOADING              = intPreferencesKey("ext_loading")             // 0/1

    // Today (day-to-date)
    val LH_COUNT             = intPreferencesKey("ext_lh_count")
    val LH_REV_USD           = doublePreferencesKey("ext_lh_rev_usd")

    // Yesterday (same period)
    val YD_COUNT             = intPreferencesKey("ext_yd_count")
    val YD_REV_USD           = doublePreferencesKey("ext_yd_rev_usd")

    // Last year (same period)
    val LY_COUNT             = intPreferencesKey("ext_ly_count")
    val LY_REV_USD           = doublePreferencesKey("ext_ly_rev_usd")
}

// -------------------------------------------------------------
// Constants
// -------------------------------------------------------------
private const val TAG = "ExtOrdersWidget"

private const val SUMMARY_URL =
    "https://www.particleformen.com/wp-json/orderswidget/v1/summary"

private const val API_KEY =
    "pFM_9rJ8dJx7F3w6uQ1Zk5Vt2Nn8Bb4Hs0Ly3Cq7Wd9Xa2Pe6Rm1Tg5Uk9Mh3"

// 20 min like the minimal widget, but unique work name
private const val UNIQUE_WORK = "orders_widget_stats_refresh"

private val HTTP by lazy {
    OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
}

// -------------------------------------------------------------
// Tiny log helpers (to Logcat + file)
// (Assumes FileLogger exists in your project.)
// -------------------------------------------------------------
private fun logMsg(ctx: Context, where: String, msg: String) {
    Log.i(TAG, "$where: $msg")
    FileLogger.append(ctx, "$where: $msg")
}
private fun logErr(ctx: Context, where: String, t: Throwable) {
    Log.e(TAG, "$where: ${t.javaClass.simpleName}: ${t.message}", t)
    FileLogger.append(ctx, "$where ERROR: ${t.javaClass.simpleName}: ${t.message}")
}
private fun logErrMsg(ctx: Context, where: String, msg: String) {
    Log.e(TAG, "$where: $msg")
    FileLogger.append(ctx, "$where ERROR: $msg")
}
private fun ceh(ctx: Context) = CoroutineExceptionHandler { _, t -> logErr(ctx, "Coroutine Uncaught", t) }

// -------------------------------------------------------------
// DTO + fetcher
// -------------------------------------------------------------
private data class Bucket(val count: Int, val revenueUsd: Double)
private data class ExtSummary(
    val lastHour: Bucket,   // actually "today" in your API
    val yesterday: Bucket,
    val lastYear: Bucket,
    val updatedHhmm: String
)

private suspend fun fetchExtSummary(ctx: Context): Result<ExtSummary> =
    withContext(Dispatchers.IO) {
        try {
            val bust = System.currentTimeMillis()
            val sep = if (SUMMARY_URL.contains("?")) "&" else "?"
            val url = "$SUMMARY_URL${sep}key=$API_KEY&stats=1&t=$bust"

            val req = Request.Builder()
                .url(url)
                .get()
                .header("Cache-Control", "no-cache")
                .header("Accept", "application/json")
                .build()

            val resp = try {
                HTTP.newCall(req).execute()
            } catch (t: java.net.SocketTimeoutException) {
                logMsg(ctx, "fetch", "H2 timeout; retrying over HTTP/1.1")
                val http1 = HTTP.newBuilder()
                    .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                    .readTimeout(15, TimeUnit.SECONDS)
                    .callTimeout(25, TimeUnit.SECONDS)
                    .build()
                http1.newCall(req).execute()
            }

            if (!resp.isSuccessful) {
                logErrMsg(ctx, "fetch", "HTTP ${resp.code}")
                return@withContext Result.failure(Exception("http_${resp.code}"))
            }

            val body = resp.body?.string().orEmpty()
            val j = JSONObject(body)

            fun bucket(name: String): Bucket {
                val o = j.optJSONObject(name) ?: JSONObject()
                val count = o.optInt("count", -1)
                val rev = o.optDouble("revenue_usd", Double.NaN)
                return Bucket(count, if (rev.isNaN()) 0.0 else rev)
            }

            val lh = bucket("today")
            val yd = bucket("yesterday")
            val ly = bucket("last_year")

            if (lh.count < 0 || yd.count < 0 || ly.count < 0) {
                logErrMsg(ctx, "fetch", "bad_json: missing counts")
                return@withContext Result.failure(Exception("bad_json"))
            }

            val updatedIso = j.optString("updated_at_utc")
            val hhmm = isoUtcToLocalHhMm_ext(updatedIso)

            Result.success(ExtSummary(lh, yd, ly, hhmm))
        } catch (t: Throwable) {
            logErr(ctx, "fetch error", t)
            Result.failure(t)
        }
    }

// local (+03:00) hh:mm — mirror your minimal widget behavior
private fun isoUtcToLocalHhMm_ext(iso: String): String {
    val m = Regex("""T(\d{2}):(\d{2})""").find(iso) ?: return "-:-"
    val h = m.groupValues[1].toIntOrNull() ?: return "-:-"
    val min = m.groupValues[2].toIntOrNull() ?: return "-:-"
    val utcTotal = h * 60 + min
    val offsetMin = 180 // fixed +3h
    var localTotal = (utcTotal + offsetMin) % (24 * 60)
    if (localTotal < 0) localTotal += 24 * 60
    val hh = localTotal / 60
    val mm = localTotal % 60
    return String.format("%02d:%02d", hh, mm)
}

// -------------------------------------------------------------
// UI
// -------------------------------------------------------------
class ExtendedOrdersWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(300.dp, 90.dp),   // 4x1
            DpSize(300.dp, 140.dp),  // 4x2 (stretched vertically)
            DpSize(150.dp, 150.dp)   // 2x2 fallback
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val loading = (prefs[ExtPrefKeys.LOADING] ?: 0) == 1
            val updated = prefs[ExtPrefKeys.UPDATED] ?: ""

            val lhCount  = prefs[ExtPrefKeys.LH_COUNT] ?: -1
            val lhRev    = prefs[ExtPrefKeys.LH_REV_USD] ?: 0.0
            val ydCount  = prefs[ExtPrefKeys.YD_COUNT] ?: -1
            val ydRev    = prefs[ExtPrefKeys.YD_REV_USD] ?: 0.0
            val lyCount  = prefs[ExtPrefKeys.LY_COUNT] ?: -1
            val lyRev    = prefs[ExtPrefKeys.LY_REV_USD] ?: 0.0

            val nf = NumberFormat.getCurrencyInstance(Locale.US).apply {
                currency = java.util.Currency.getInstance("USD")
            }

            // --- Dynamic sizing based on actual widget height ---
            val size = LocalSize.current
            val isTall = size.height >= 130.dp // 4x2 ~ 140.dp; trip at ~130

            val capSize: TextUnit = if (isTall) 14.sp else 12.sp
            val numSize: TextUnit = if (isTall) 24.sp else 18.sp
            val updSize: TextUnit = if (isTall) 11.sp else 9.sp
            val iconSize = if (isTall) 14.dp else 12.dp
            val colSpacing = if (isTall) 8.dp else 6.dp
            val rowSpacing = if (isTall) 10.dp else 8.dp
            val cardPadding = if (isTall) 12.dp else 10.dp
            val corner = if (isTall) 6.dp else 5.dp

            // --- percent change helpers (vs Yesterday / Last year) ---
            fun pctDeltaText(today: Double, base: Double, label: String): Pair<String, ColorProvider> {
                if (base <= 0.0) {
                    return "—  $label" to ColorProvider(Color(0xFFDDDDDD), Color(0xFFDDDDDD))
                }
                val pct = ((today - base) / base) * 100.0
                val up = pct >= 0.0
                val arrow = if (up) "▲" else "▼"
                val pctAbs = kotlin.math.abs(pct).roundToInt()

                val lbl = when (label) {
                    "yesterday" -> "yd"
                    "last year" -> "ly"
                    else -> label
                }
                val txt = "$arrow${pctAbs}%  $lbl"
                val col = if (up)
                    ColorProvider(Color(0xFF1ABC9C), Color(0xFF1ABC9C)) // green
                else
                    ColorProvider(Color(0xFFE74C3C), Color(0xFFE74C3C)) // red

                return txt to col
            }

            val deltaVsYd = pctDeltaText(lhRev, ydRev, "yesterday")
            val deltaVsLy = pctDeltaText(lhRev, lyRev, "last year")
            val ydVsLyDelta  = pctDeltaText(ydRev,  lyRev, "last year")
            val timeText = if (updated.isBlank()) (if (loading) "…" else "--:--") else updated

            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(Color(0xBF14213D), Color(0xBF14213D)))
                    .cornerRadius(corner)
                    .padding(cardPadding)
                    .clickable(actionRunCallback<ExtFetchAction>()) // tap anywhere to refresh
            ) {
                Column(
                    modifier = GlanceModifier.fillMaxSize(),
                    verticalAlignment = Alignment.Top,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // --- STATS ROW ---
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        ExtStatColumn(
                            caption = "Today",
                            count = lhCount,
                            revenue = nf.format(lhRev),
                            numSize = numSize,
                            capSize = capSize,
                            modifier = GlanceModifier.defaultWeight(),
                            extra1 = deltaVsYd,
                            extra2 = deltaVsLy
                        )
                        Spacer(GlanceModifier.width(colSpacing))
                        ExtStatColumn(
                            caption = "Yesterday",
                            count = ydCount,
                            revenue = nf.format(ydRev),
                            numSize = numSize,
                            capSize = capSize,
                            modifier = GlanceModifier.defaultWeight(),
                            extra1 = ydVsLyDelta
                        )
                        Spacer(GlanceModifier.width(colSpacing))
                        ExtStatColumn(
                            caption = "Last year",
                            count = lyCount,
                            revenue = nf.format(lyRev),
                            numSize = numSize,
                            capSize = capSize,
                            modifier = GlanceModifier.defaultWeight(),
                            timeText = timeText,
                            loading = loading,
                            updSize = updSize,
                            iconSize = iconSize
                        )
                    }

                    Spacer(GlanceModifier.height(rowSpacing))
                }

                // Overlay logo (top-right). If you want absolute-right, wrap in Row/Alignment.End in a Box scope.
                Image(
                    provider = ImageProvider(R.drawable.p_logo),
                    contentDescription = "Logo",
                    modifier = GlanceModifier.size(if (isTall) 14.dp else 12.dp)
                )
            }
        }
    }
}

@Composable
private fun ExtStatColumn(
    caption: String,
    count: Int,
    revenue: String,
    numSize: TextUnit,
    capSize: TextUnit,
    modifier: GlanceModifier = GlanceModifier,
    extra1: Pair<String, ColorProvider>? = null,
    extra2: Pair<String, ColorProvider>? = null,
    timeText: String? = null,
    loading: Boolean = false,
    updSize: TextUnit = 9.sp,
    iconSize: androidx.compose.ui.unit.Dp = 12.dp
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = caption,
            style = TextStyle(
                color = ColorProvider(Color.White, Color.White),
                fontSize = capSize
            ),
            maxLines = 1
        )
        Text(
            text = if (count < 0) "-" else count.toString(),
            style = TextStyle(
                color = ColorProvider(Color.White, Color.White),
                fontSize = numSize,
                fontWeight = FontWeight.Bold
            ),
            maxLines = 1
        )
        Text(
            text = revenue,
            style = TextStyle(
                color = ColorProvider(Color.White, Color.White),
                fontSize = capSize
            ),
            maxLines = 1
        )

        // Optional extra lines (used for Today only): percent deltas
        if (extra1 != null && extra2 != null) {
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Text(
                    text = extra1.first,
                    style = TextStyle(color = extra1.second, fontSize = capSize, fontWeight = FontWeight.Medium),
                    maxLines = 1
                )
                Spacer(GlanceModifier.width(4.dp))
                Text(
                    text = "|",
                    style = TextStyle(
                        color = ColorProvider(Color.White, Color.White),
                        fontSize = capSize,
                        fontWeight = FontWeight.Medium
                    )
                )
                Spacer(GlanceModifier.width(4.dp))
                Text(
                    text = extra2.first,
                    style = TextStyle(color = extra2.second, fontSize = capSize, fontWeight = FontWeight.Medium),
                    maxLines = 1
                )
            }
        } else {
            (extra1 ?: extra2)?.let { (txt, col) ->
                Text(
                    text = txt,
                    style = TextStyle(color = col, fontSize = capSize, fontWeight = FontWeight.Medium),
                    maxLines = 1
                )
            }
        }

        // Optional footer: time + refresh under this column (only for the last column now)
        timeText?.let { t ->
            Spacer(GlanceModifier.height(4.dp))
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally
            ) {
                Text(
                    text = "Upd:",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF3DBCDC), Color(0xFF3DBCDC)), // teal
                        fontSize = updSize
                    ),
                    maxLines = 1
                )
                Spacer(GlanceModifier.width(4.dp))
                Text(
                    text = t,
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF3DBCDC), Color(0xFF3DBCDC)), // teal
                        fontSize = updSize
                    ),
                    maxLines = 1
                )
                Spacer(GlanceModifier.width(6.dp))
                if (loading) {
                    CircularProgressIndicator(
                        modifier = GlanceModifier.size(iconSize),
                        color = ColorProvider(Color.White, Color.White)
                    )
                } else {
                    Image(
                        provider = ImageProvider(R.drawable.update),
                        contentDescription = "Refresh",
                        modifier = GlanceModifier
                            .size(iconSize)
                            .clickable(actionRunCallback<ExtFetchAction>())
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// Action: manual refresh (tap)
// -------------------------------------------------------------
class ExtFetchAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { p ->
            p.toMutablePreferences().apply { this[ExtPrefKeys.LOADING] = 1 }
        }
        ExtendedOrdersWidget().update(context, glanceId)

        val r = fetchExtSummary(context)

        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { p ->
            val m = p.toMutablePreferences()
            if (r.isSuccess) {
                val dto = r.getOrNull()!!
                m[ExtPrefKeys.LH_COUNT]   = dto.lastHour.count
                m[ExtPrefKeys.LH_REV_USD] = dto.lastHour.revenueUsd
                m[ExtPrefKeys.YD_COUNT]   = dto.yesterday.count
                m[ExtPrefKeys.YD_REV_USD] = dto.yesterday.revenueUsd
                m[ExtPrefKeys.LY_COUNT]   = dto.lastYear.count
                m[ExtPrefKeys.LY_REV_USD] = dto.lastYear.revenueUsd
                m[ExtPrefKeys.UPDATED]    = dto.updatedHhmm

                val (enabled, threshold) = readAlertSettings(context)
                if (enabled && dto.lastHour.count < threshold) {
                    val now = System.currentTimeMillis()
                    val last = readLastAlertAt(context)
                    val cooldownMin = readCooldownMinutes(context).coerceAtLeast(0)
                    if (cooldownMin == 0 || now - last >= cooldownMin * 60 * 1000L) {
                        ensureAlertChannel(context)
                        notifyLowOrders(context, dto.lastHour.count, threshold)
                        writeLastAlertAt(context, now)
                    }
                }

                logMsg(context, "ext manual", "success lh=${dto.lastHour.count}/${dto.lastHour.revenueUsd}")
            } else {
                r.exceptionOrNull()?.let { logErr(context, "ext manual failure", it) }
                m[ExtPrefKeys.UPDATED] = ""
            }
            m[ExtPrefKeys.LOADING] = 0
            m
        }
        ExtendedOrdersWidget().update(context, glanceId)
    }
}

// -------------------------------------------------------------
// Receiver
// -------------------------------------------------------------
class ExtendedOrdersWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ExtendedOrdersWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        ensureStatsAutoRefreshScheduled(context)

        val pr = goAsync()
        CoroutineScope(Dispatchers.IO + ceh(context)).launch {
            val gm = GlanceAppWidgetManager(context)
            val ids = gm.getGlanceIds(ExtendedOrdersWidget::class.java)

            // eager first refresh for all instances
            ids.forEach { id ->
                updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { p ->
                    p.toMutablePreferences().apply { this[ExtPrefKeys.LOADING] = 1 }
                }
                ExtendedOrdersWidget().update(context, id)
            }
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<ExtendedStatsRefreshWorker>().build()
            )
            pr.finish()
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        ensureStatsAutoRefreshScheduled(context)

        val pr = goAsync()
        CoroutineScope(Dispatchers.IO + ceh(context)).launch {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<ExtendedStatsRefreshWorker>().build()
            )
            pr.finish()
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
    }
}

// -------------------------------------------------------------
// WorkManager scheduling + worker
// -------------------------------------------------------------
private fun ensureStatsAutoRefreshScheduled(ctx: Context) {
    val req = PeriodicWorkRequestBuilder<ExtendedStatsRefreshWorker>(20, TimeUnit.MINUTES)
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        ).build()

    WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
        UNIQUE_WORK,
        ExistingPeriodicWorkPolicy.KEEP,
        req
    )
}

class ExtendedStatsRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): ListenableWorker.Result {
        val gm = GlanceAppWidgetManager(applicationContext)
        val ids = gm.getGlanceIds(ExtendedOrdersWidget::class.java)

        return try {
            // Set loading for all
            ids.forEach { id ->
                updateAppWidgetState(applicationContext, PreferencesGlanceStateDefinition, id) { p ->
                    p.toMutablePreferences().apply { this[ExtPrefKeys.LOADING] = 1 }
                }
            }
            ids.forEach { ExtendedOrdersWidget().update(applicationContext, it) }

            val r = fetchExtSummary(applicationContext)

            ids.forEach { id ->
                updateAppWidgetState(applicationContext, PreferencesGlanceStateDefinition, id) { p ->
                    val m = p.toMutablePreferences()
                    if (r.isSuccess) {
                        val dto = r.getOrNull()!!
                        m[ExtPrefKeys.LH_COUNT]   = dto.lastHour.count
                        m[ExtPrefKeys.LH_REV_USD] = dto.lastHour.revenueUsd
                        m[ExtPrefKeys.YD_COUNT]   = dto.yesterday.count
                        m[ExtPrefKeys.YD_REV_USD] = dto.yesterday.revenueUsd
                        m[ExtPrefKeys.LY_COUNT]   = dto.lastYear.count
                        m[ExtPrefKeys.LY_REV_USD] = dto.lastYear.revenueUsd
                        m[ExtPrefKeys.UPDATED]    = dto.updatedHhmm

                        val (enabled, threshold) = readAlertSettings(applicationContext)
                        if (enabled && dto.lastHour.count < threshold) {
                            val now = System.currentTimeMillis()
                            val last = readLastAlertAt(applicationContext)
                            val cooldownMin = readCooldownMinutes(applicationContext).coerceAtLeast(0)
                            if (cooldownMin == 0 || now - last >= cooldownMin * 60 * 1000L) {
                                ensureAlertChannel(applicationContext)
                                notifyLowOrders(applicationContext, dto.lastHour.count, threshold)
                                writeLastAlertAt(applicationContext, now)
                            }
                        }

                        logMsg(applicationContext, "ext worker", "success")
                    } else {
                        r.exceptionOrNull()?.let { logErr(applicationContext, "ext worker failure", it) }
                        m[ExtPrefKeys.UPDATED] = ""
                    }
                    m[ExtPrefKeys.LOADING] = 0
                    m
                }
            }
            ids.forEach { ExtendedOrdersWidget().update(applicationContext, it) }

            ListenableWorker.Result.success()
        } catch (t: Throwable) {
            logErr(applicationContext, "ext worker error", t)
            ids.forEach { id ->
                updateAppWidgetState(applicationContext, PreferencesGlanceStateDefinition, id) { p ->
                    p.toMutablePreferences().apply { this[ExtPrefKeys.LOADING] = 0 }
                }
            }
            ids.forEach { ExtendedOrdersWidget().update(applicationContext, it) }
            ListenableWorker.Result.retry()
        }
    }
}
