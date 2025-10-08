// ExtendedOrdersWidget.kt
package com.particleformen.orderswidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
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
import androidx.glance.unit.ColorProvider
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
import androidx.glance.text.TextStyle
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import android.graphics.BitmapFactory
import android.net.Uri

// -------------------------------------------------------------
// Glance preferences used by the Extended widget
// -------------------------------------------------------------
object ExtPrefKeys {
    val UPDATED       = stringPreferencesKey("ext_updated")       // hh:mm local
    val LOADING       = intPreferencesKey("ext_loading")          // 0/1
    val LH_COUNT      = intPreferencesKey("ext_lh_count")
    val LH_REV_USD    = doublePreferencesKey("ext_lh_rev_usd")
    val YD_COUNT      = intPreferencesKey("ext_yd_count")
    val YD_REV_USD    = doublePreferencesKey("ext_yd_rev_usd")
    val LY_COUNT      = intPreferencesKey("ext_ly_count")
    val LY_REV_USD    = doublePreferencesKey("ext_ly_rev_usd")

    // Serialized (tiny) product lists per period; each value is JSON:
    // {"items":[{"title": "...","qty": 123,"uri": "content://..."}]}
    val TODAY_PRODUCTS_JSON = stringPreferencesKey("ext_today_products_json")
    val YDAY_PRODUCTS_JSON  = stringPreferencesKey("ext_yday_products_json")
    val LY_PRODUCTS_JSON    = stringPreferencesKey("ext_ly_products_json")
}

// -------------------------------------------------------------
// Constants & HTTP client
// -------------------------------------------------------------
private const val TAG = "ExtOrdersWidget"
private const val SUMMARY_URL = "https://www.particleformen.com/wp-json/orderswidget/v1/summary"
private const val API_KEY = "pFM_9rJ8dJx7F3w6uQ1Zk5Vt2Nn8Bb4Hs0Ly3Cq7Wd9Xa2Pe6Rm1Tg5Uk9Mh3"
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
// Logging helpers (to Logcat + file via FileLogger)
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
private fun ceh(ctx: Context) = CoroutineExceptionHandler { _, t ->
    logErr(ctx, "Coroutine Uncaught", t)
}

// -------------------------------------------------------------
// DTOs + network fetcher
// -------------------------------------------------------------
private data class Bucket(val count: Int, val revenueUsd: Double)
private data class TopProd(val title: String, val qty: Int, val thumbUri: String)
private data class ExtSummary(
    val lastHour: Bucket,     // actually "today" in API
    val yesterday: Bucket,
    val lastYear: Bucket,
    val updatedHhmm: String,
    val todayProducts: List<TopProd> = emptyList(),
    val ydayProducts:  List<TopProd> = emptyList(),
    val lyProducts:    List<TopProd> = emptyList()
)

/** Cache a remote thumb into filesDir/widget_thumbs and return a FileProvider content URI string. */
private fun cacheThumbAndGetUri(ctx: Context, url: String, key: String): String? {
    return try {
        val req = Request.Builder().url(url).get().build()
        HTTP.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bytes = resp.body?.bytes() ?: return null
            val dir = File(ctx.filesDir, "widget_thumbs").apply { mkdirs() }
            val f = File(dir, "$key.png")
            f.writeBytes(bytes)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                ctx,
                "${ctx.packageName}.fileprovider",
                f
            )
            uri.toString()
        }
    } catch (_: Throwable) { null }
}

/** Decode a FileProvider URI into a Bitmap-backed ImageProvider (works across launchers). */
private fun imageProviderFromUri(ctx: Context, uriStr: String): ImageProvider? {
    if (uriStr.isBlank()) return null
    return try {
        ctx.contentResolver.openInputStream(Uri.parse(uriStr)).use { ins ->
            val bmp = ins?.let { BitmapFactory.decodeStream(it) } ?: return null
            ImageProvider(bmp)
        }
    } catch (_: Throwable) { null }
}

/** Fetch summary; include &top_products=1 only when the toggle is ON. */
private suspend fun fetchExtSummary(ctx: Context): Result<ExtSummary> =
    withContext(Dispatchers.IO) {
        try {
            val wantProducts = runCatching { readShowTopProducts(ctx) }.getOrDefault(false)
            val bust = System.currentTimeMillis()
            val sep = if (SUMMARY_URL.contains("?")) "&" else "?"
            val url = buildString {
                append("$SUMMARY_URL${sep}key=$API_KEY&stats=1&t=$bust")
                if (wantProducts) append("&top_products=1")
            }
            logMsg(ctx, "fetch", "url=$url wantProducts=$wantProducts")

            // Build request; on HTTP/2 timeout, transparently retry over HTTP/1.1
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

            var todayProds: List<TopProd> = emptyList()
            var ydayProds:  List<TopProd> = emptyList()
            var lyProds:    List<TopProd> = emptyList()

            if (wantProducts) {
                fun parseProducts(arrName: String): List<TopProd> {
                    val arr = j.optJSONObject(arrName)?.optJSONArray("products") ?: return emptyList()
                    val out = mutableListOf<TopProd>()
                    val limit = minOf(3, arr.length()) // render-friendly cap
                    for (i in 0 until limit) {
                        val o = arr.optJSONObject(i) ?: continue
                        val sku = o.optString("sku")
                        val title = o.optString("title")
                        val qty = o.optInt("qty", 0)
                        val url = o.optString("thumb_url")
                        val fp = cacheThumbAndGetUri(ctx, url, "${arrName}_${sku.ifEmpty { "n$i" }}")
                        out += TopProd(title, qty, fp ?: "")
                    }
                    return out
                }
                todayProds = parseProducts("today")
                ydayProds  = parseProducts("yesterday")
                lyProds    = parseProducts("last_year")
                logMsg(ctx, "fetch", "prods today=${todayProds.size} yd=${ydayProds.size} ly=${lyProds.size}")
            }

            Result.success(
                ExtSummary(
                    lastHour = lh,
                    yesterday = yd,
                    lastYear = ly,
                    updatedHhmm = hhmm,
                    todayProducts = todayProds,
                    ydayProducts  = ydayProds,
                    lyProducts    = lyProds
                )
            )
        } catch (t: Throwable) {
            logErr(ctx, "fetch error", t)
            Result.failure(t)
        }
    }

// local (+03:00) hh:mm — mirror minimal widget behavior used elsewhere in app
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
            DpSize(300.dp, 90.dp),   // 4x1 compact
            DpSize(300.dp, 140.dp),  // 4x2 comfy
            DpSize(300.dp, 200.dp),  // roomy (if launcher allows taller)
            DpSize(150.dp, 150.dp)   // 2x2 fallback
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs   = currentState<Preferences>()
            val loading = (prefs[ExtPrefKeys.LOADING] ?: 0) == 1
            val updated = prefs[ExtPrefKeys.UPDATED] ?: ""

            val lhCount = prefs[ExtPrefKeys.LH_COUNT] ?: -1
            val lhRev   = prefs[ExtPrefKeys.LH_REV_USD] ?: 0.0
            val ydCount = prefs[ExtPrefKeys.YD_COUNT] ?: -1
            val ydRev   = prefs[ExtPrefKeys.YD_REV_USD] ?: 0.0
            val lyCount = prefs[ExtPrefKeys.LY_COUNT] ?: -1
            val lyRev   = prefs[ExtPrefKeys.LY_REV_USD] ?: 0.0

            // Deserialize product lists if present
            val todayList = parseProdJsonOrEmpty(prefs[ExtPrefKeys.TODAY_PRODUCTS_JSON])
            val ydayList  = parseProdJsonOrEmpty(prefs[ExtPrefKeys.YDAY_PRODUCTS_JSON])
            val lyList    = parseProdJsonOrEmpty(prefs[ExtPrefKeys.LY_PRODUCTS_JSON])

            val nf = NumberFormat.getCurrencyInstance(Locale.US).apply {
                currency = java.util.Currency.getInstance("USD")
            }

            // Dynamic sizing → text/icon/padding presets
            val size = LocalSize.current
            val tier = when {
                size.height >= 180.dp -> SizeTier.Roomy
                size.height >= 130.dp -> SizeTier.Comfy
                else                  -> SizeTier.Compact
            }
            val dims = tier.dimensions()

            // Percent deltas (vs yesterday / last year)
            fun pctDeltaText(today: Double, base: Double, label: String): Pair<String, ColorProvider> {
                if (base <= 0.0) return "—  $label" to ColorProvider(Color(0xFFDDDDDD), Color(0xFFDDDDDD))
                val pct = ((today - base) / base) * 100.0
                val up = pct >= 0.0
                val arrow = if (up) "▲" else "▼"
                val pctAbs = kotlin.math.abs(pct).roundToInt()
                val lbl = when (label) { "yesterday" -> "yd"; "last year" -> "ly"; else -> label }
                val col = if (up) ColorProvider(Color(0xFF1ABC9C), Color(0xFF1ABC9C))
                else      ColorProvider(Color(0xFFE74C3C), Color(0xFFE74C3C))
                return "$arrow$pctAbs%  $lbl" to col
            }

            val deltaVsYd   = pctDeltaText(lhRev, ydRev, "yesterday")
            val deltaVsLy   = pctDeltaText(lhRev, lyRev, "last year")
            val ydVsLyDelta = pctDeltaText(ydRev,  lyRev, "last year")
            val timeText    = if (updated.isBlank()) (if (loading) "…" else "--:--") else updated

            // Decide how much to show, to avoid vertical clipping:
            // Compact: no products; Comfy: 1 row and hide deltas; Roomy: 1 row and keep deltas on one line.
            val hasAnyProducts = todayList.isNotEmpty() || ydayList.isNotEmpty() || lyList.isNotEmpty()
            val (rowsPerCol, passExtras, splitExtras) = when (tier) {
                SizeTier.Compact -> Triple(0, false, false)
                SizeTier.Comfy   -> Triple(1, false, false) // hide deltas → make room for products
                SizeTier.Roomy   -> Triple(1, true,  false) // show one-line deltas + 1 product row
            }

            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(Color(0xBF14213D), Color(0xBF14213D)))
                    .cornerRadius(dims.corner)
                    .padding(dims.cardPadding)
                    .clickable(actionRunCallback<ExtFetchAction>()) // manual refresh
            ) {
                Column(
                    modifier = GlanceModifier.fillMaxSize(),
                    verticalAlignment = Alignment.Top,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                    // --- 3-column stats row ---
                    Row(modifier = GlanceModifier.fillMaxWidth()) {

                        // TODAY
                        Column(
                            modifier = GlanceModifier.defaultWeight(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            ExtStatColumn(
                                caption = "Today",
                                count = lhCount,
                                revenue = nf.format(lhRev),
                                numSize = dims.numSize,
                                capSize = dims.capSize,
                                extra1 = if (hasAnyProducts && !passExtras) null else deltaVsYd,
                                extra2 = if (hasAnyProducts && !passExtras) null else deltaVsLy,
                                splitExtras = splitExtras,
                                lineGap = dims.lineGap
                            )
                            TopProductsList(
                                items = todayList.take(rowsPerCol),
                                thumb = dims.iconSize + 4.dp,
                                textSize = dims.capSize,
                                lineGap = dims.lineGap
                            )
                        }

                        Spacer(GlanceModifier.width(dims.colSpacing))

                        // YESTERDAY
                        Column(
                            modifier = GlanceModifier.defaultWeight(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            ExtStatColumn(
                                caption = "Yesterday",
                                count = ydCount,
                                revenue = nf.format(ydRev),
                                numSize = dims.numSize,
                                capSize = dims.capSize,
                                extra1 = if (hasAnyProducts && !passExtras) null else ydVsLyDelta,
                                splitExtras = false,
                                lineGap = dims.lineGap
                            )
                            TopProductsList(
                                items = ydayList.take(rowsPerCol),
                                thumb = dims.iconSize + 4.dp,
                                textSize = dims.capSize,
                                lineGap = dims.lineGap
                            )
                        }

                        Spacer(GlanceModifier.width(dims.colSpacing))

                        // LAST YEAR
                        Column(
                            modifier = GlanceModifier.defaultWeight(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            ExtStatColumn(
                                caption = "Last year",
                                count = lyCount,
                                revenue = nf.format(lyRev),
                                numSize = dims.numSize,
                                capSize = dims.capSize,
                                timeText = timeText,         // keep time/loader here
                                loading = loading,
                                updSize = dims.updSize,
                                iconSize = dims.iconSize,
                                splitExtras = false,
                                lineGap = dims.lineGap
                            )
                            TopProductsList(
                                items = lyList.take(rowsPerCol),
                                thumb = dims.iconSize + 4.dp,
                                textSize = dims.capSize,
                                lineGap = dims.lineGap
                            )
                        }
                    }

                    Spacer(GlanceModifier.height(dims.rowSpacing))
                }

                // Tiny overlay logo
                Image(
                    provider = ImageProvider(R.drawable.p_logo),
                    contentDescription = "Logo",
                    modifier = GlanceModifier.size(dims.logoSize)
                )
            }
        }
    }
}

// Size tiers + layout metrics
private enum class SizeTier { Compact, Comfy, Roomy }
private data class SizeDims(
    val capSize: TextUnit,
    val numSize: TextUnit,
    val updSize: TextUnit,
    val iconSize: Dp,
    val logoSize: Dp,
    val colSpacing: Dp,
    val rowSpacing: Dp,
    val cardPadding: Dp,
    val corner: Dp,
    val lineGap: Dp
)
private fun SizeTier.dimensions(): SizeDims = when (this) {
    SizeTier.Compact -> SizeDims(
        capSize = 12.sp, numSize = 18.sp, updSize = 9.sp,
        iconSize = 12.dp, logoSize = 12.dp,
        colSpacing = 6.dp, rowSpacing = 8.dp,
        cardPadding = 10.dp, corner = 5.dp, lineGap = 2.dp
    )
    SizeTier.Comfy -> SizeDims(
        capSize = 14.sp, numSize = 24.sp, updSize = 11.sp,
        iconSize = 14.dp, logoSize = 14.dp,
        colSpacing = 8.dp, rowSpacing = 10.dp,
        cardPadding = 12.dp, corner = 6.dp, lineGap = 3.dp
    )
    SizeTier.Roomy -> SizeDims(
        capSize = 16.sp, numSize = 30.sp, updSize = 12.sp,
        iconSize = 16.dp, logoSize = 16.dp,
        colSpacing = 10.dp, rowSpacing = 12.dp,
        cardPadding = 14.dp, corner = 8.dp, lineGap = 4.dp
    )
}

/** Column block (caption, count, revenue, optional deltas, optional time/refresh). */
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
    iconSize: Dp = 12.dp,
    splitExtras: Boolean = false,
    lineGap: Dp = 2.dp
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = caption, style = TextStyle(color = ColorProvider(Color.White, Color.White), fontSize = capSize), maxLines = 1)
        Text(
            text = if (count < 0) "-" else count.toString(),
            style = TextStyle(color = ColorProvider(Color.White, Color.White), fontSize = numSize, fontWeight = FontWeight.Bold),
            maxLines = 1
        )
        Text(text = revenue, style = TextStyle(color = ColorProvider(Color.White, Color.White), fontSize = capSize), maxLines = 1)

        // Deltas (optional; hidden in tight tiers to make room for product rows)
        if (extra1 != null && extra2 != null && splitExtras) {
            Spacer(GlanceModifier.height(lineGap))
            Text(text = extra1.first, style = TextStyle(color = extra1.second, fontSize = capSize, fontWeight = FontWeight.Medium), maxLines = 1)
            Spacer(GlanceModifier.height(lineGap))
            Text(text = extra2.first, style = TextStyle(color = extra2.second, fontSize = capSize, fontWeight = FontWeight.Medium), maxLines = 1)
        } else if (extra1 != null && extra2 != null) {
            Spacer(GlanceModifier.height(lineGap))
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Text(text = extra1.first, style = TextStyle(color = extra1.second, fontSize = capSize, fontWeight = FontWeight.Medium), maxLines = 1)
                Spacer(GlanceModifier.width(4.dp))
                Text(text = "|", style = TextStyle(color = ColorProvider(Color.White, Color.White), fontSize = capSize, fontWeight = FontWeight.Medium), maxLines = 1)
                Spacer(GlanceModifier.width(4.dp))
                Text(text = extra2.first, style = TextStyle(color = extra2.second, fontSize = capSize, fontWeight = FontWeight.Medium), maxLines = 1)
            }
        } else {
            (extra1 ?: extra2)?.let { (txt, col) ->
                Spacer(GlanceModifier.height(lineGap))
                Text(text = txt, style = TextStyle(color = col, fontSize = capSize, fontWeight = FontWeight.Medium), maxLines = 1)
            }
        }

        // Footer (optional): "Upd: HH:MM" + spinner/refresh
        timeText?.let { t ->
            Spacer(GlanceModifier.height(lineGap + 2.dp))
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally
            ) {
                Text(text = "Upd:", style = TextStyle(color = ColorProvider(Color(0xFF3DBCDC), Color(0xFF3DBCDC)), fontSize = updSize), maxLines = 1)
                Spacer(GlanceModifier.width(4.dp))
                Text(text = t, style = TextStyle(color = ColorProvider(Color(0xFF3DBCDC), Color(0xFF3DBCDC)), fontSize = updSize), maxLines = 1)
                Spacer(GlanceModifier.width(6.dp))
                if (loading) {
                    CircularProgressIndicator(modifier = GlanceModifier.size(iconSize), color = ColorProvider(Color.White, Color.White))
                } else {
                    Image(
                        provider = ImageProvider(R.drawable.update),
                        contentDescription = "Refresh",
                        modifier = GlanceModifier.size(iconSize).clickable(actionRunCallback<ExtFetchAction>())
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// Manual refresh (tap anywhere on the widget)
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

                // Persist product lists (or clear if none)
                if (dto.todayProducts.isNotEmpty() || dto.ydayProducts.isNotEmpty() || dto.lyProducts.isNotEmpty()) {
                    fun toJson(list: List<TopProd>) = JSONObject().apply {
                        put("items", list.map { tp ->
                            JSONObject().apply {
                                put("title", tp.title); put("qty", tp.qty); put("uri", tp.thumbUri)
                            }
                        })
                    }.toString()
                    m[ExtPrefKeys.TODAY_PRODUCTS_JSON] = toJson(dto.todayProducts)
                    m[ExtPrefKeys.YDAY_PRODUCTS_JSON]  = toJson(dto.ydayProducts)
                    m[ExtPrefKeys.LY_PRODUCTS_JSON]    = toJson(dto.lyProducts)
                } else {
                    m.remove(ExtPrefKeys.TODAY_PRODUCTS_JSON)
                    m.remove(ExtPrefKeys.YDAY_PRODUCTS_JSON)
                    m.remove(ExtPrefKeys.LY_PRODUCTS_JSON)
                }

                // Low-orders alert logic
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

        val pr = try { goAsync() } catch (_: Throwable) { null } // guard against null PendingResult
        CoroutineScope(Dispatchers.IO + ceh(context)).launch {
            val gm = GlanceAppWidgetManager(context)
            val ids = gm.getGlanceIds(ExtendedOrdersWidget::class.java)

            // Eager first refresh: set loading and queue a one-time worker
            ids.forEach { id ->
                updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { p ->
                    p.toMutablePreferences().apply { this[ExtPrefKeys.LOADING] = 1 }
                }
                ExtendedOrdersWidget().update(context, id)
            }
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<ExtendedStatsRefreshWorker>().build()
            )
            pr?.finish()
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        ensureStatsAutoRefreshScheduled(context)

        val pr = try { goAsync() } catch (_: Throwable) { null } // guard against null PendingResult
        CoroutineScope(Dispatchers.IO + ceh(context)).launch {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<ExtendedStatsRefreshWorker>().build()
            )
            pr?.finish()
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
    }
}

// -------------------------------------------------------------
// Periodic auto-refresh via WorkManager
// -------------------------------------------------------------
private fun ensureStatsAutoRefreshScheduled(ctx: Context) {
    val req = PeriodicWorkRequestBuilder<ExtendedStatsRefreshWorker>(20, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()

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

                        // Persist (or clear) product lists
                        if (dto.todayProducts.isNotEmpty() || dto.ydayProducts.isNotEmpty() || dto.lyProducts.isNotEmpty()) {
                            fun toJson(list: List<TopProd>) = JSONObject().apply {
                                put("items", list.map { tp ->
                                    JSONObject().apply {
                                        put("title", tp.title); put("qty", tp.qty); put("uri", tp.thumbUri)
                                    }
                                })
                            }.toString()
                            m[ExtPrefKeys.TODAY_PRODUCTS_JSON] = toJson(dto.todayProducts)
                            m[ExtPrefKeys.YDAY_PRODUCTS_JSON]  = toJson(dto.ydayProducts)
                            m[ExtPrefKeys.LY_PRODUCTS_JSON]    = toJson(dto.lyProducts)
                        } else {
                            m.remove(ExtPrefKeys.TODAY_PRODUCTS_JSON)
                            m.remove(ExtPrefKeys.YDAY_PRODUCTS_JSON)
                            m.remove(ExtPrefKeys.LY_PRODUCTS_JSON)
                        }

                        // Notifications (low orders)
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

// -------------------------------------------------------------
// Product list parsing + rendering
// -------------------------------------------------------------
private data class ProdUi(val title: String, val qty: Int, val uri: String)

/** Parse tiny JSON blob persisted in Glance prefs back to UI structs. */
private fun parseProdJsonOrEmpty(json: String?): List<ProdUi> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONObject(json).optJSONArray("items") ?: return emptyList()
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(ProdUi(o.optString("title"), o.optInt("qty"), o.optString("uri")))
            }
        }
    } catch (_: Throwable) { emptyList() }
}

/** Renders up to N (caller-trimmed) products with tiny thumbs. */
@Composable
private fun TopProductsList(items: List<ProdUi>, thumb: Dp, textSize: TextUnit, lineGap: Dp) {
    if (items.isEmpty()) return
    val ctx = LocalContext.current
    Spacer(GlanceModifier.height(lineGap))
    items.forEach { p ->
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
            horizontalAlignment = Alignment.Horizontal.Start
        ) {
            imageProviderFromUri(ctx, p.uri)?.let { prov ->
                Image(provider = prov, contentDescription = p.title, modifier = GlanceModifier.size(thumb))
                Spacer(GlanceModifier.width(6.dp))
            }
            Text(
                text = "${p.qty} • ${p.title}",
                style = TextStyle(color = ColorProvider(Color.White, Color.White), fontSize = textSize),
                maxLines = 1
            )
        }
        Spacer(GlanceModifier.height(lineGap))
    }
}
