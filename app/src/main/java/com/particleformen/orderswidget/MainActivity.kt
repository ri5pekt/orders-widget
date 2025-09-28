package com.particleformen.orderswidget

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.particleformen.orderswidget.ui.theme.OrdersWidgetTheme

import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

// Same DataStore name the widget uses
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SettingsRoot(this) }
    }
}

/** Helper: are notifications effectively enabled? (permission + app-level toggle) */
private fun hasNotificationsPermission(ctx: android.content.Context): Boolean {
    val nmEnabled = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return nmEnabled
    }
    val granted = ContextCompat.checkSelfPermission(
        ctx, Manifest.permission.POST_NOTIFICATIONS
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    return granted && nmEnabled
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsRoot(activity: ComponentActivity) {
    val scope = rememberCoroutineScope()

    // Backing int/bool states
    val thresholdState     = remember { mutableStateOf(3) }
    val cooldownState      = remember { mutableStateOf(60) }
    val alertsEnabledState = remember { mutableStateOf(true) }

    // Text field states (so user can type freely)
    val thresholdText = remember { mutableStateOf("3") }
    val cooldownText  = remember { mutableStateOf("60") }

    // Notifications permission state + launcher
    val notifPermState = remember { mutableStateOf(hasNotificationsPermission(activity)) }
    val notifPermLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Re-check both runtime permission and app-level toggle
            notifPermState.value = hasNotificationsPermission(activity)
        }

    // Load saved values once
    LaunchedEffect(Unit) {
        val prefs = activity.applicationContext.appDataStore.data.first()
        val threshold = prefs[PrefKeys.ALERT_THRESHOLD] ?: 3
        val cooldown  = prefs[PrefKeys.COOLDOWN_MINUTES] ?: 60
        val enabled   = (prefs[PrefKeys.ALERTS_ENABLED] ?: 1) == 1

        thresholdState.value = threshold
        cooldownState.value  = cooldown
        alertsEnabledState.value = enabled

        thresholdText.value = threshold.toString()
        cooldownText.value  = cooldown.toString()

        // Also refresh notifications state on open
        notifPermState.value = hasNotificationsPermission(activity)
    }

    OrdersWidgetTheme {
        MaterialTheme {
            Scaffold(
                topBar = {
                    CenterAlignedTopAppBar(
                        title = { Text("Widget Settings") }
                    )
                },
                bottomBar = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Button(onClick = { activity.finish() }) {
                            Text("Save and Close")
                        }
                    }
                }
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Notifications permission row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Notifications")
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    // < Android 13: no runtime permission; users control in system settings.
                                    // Button remains disabled if already enabled (see 'enabled' prop below)
                                }
                            },
                            enabled = !notifPermState.value
                        ) {
                            Text(if (notifPermState.value) "Enabled" else "Enable")
                        }
                    }

                    // Enable alerts switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Enable alerts")
                        Switch(
                            checked = alertsEnabledState.value,
                            onCheckedChange = { checked ->
                                alertsEnabledState.value = checked
                                scope.launch {
                                    activity.applicationContext.appDataStore.edit { it[PrefKeys.ALERTS_ENABLED] = if (checked) 1 else 0 }
                                }
                            }
                        )
                    }

                    // Threshold field
                    Column {
                        Text("Low orders alert threshold:")
                        OutlinedTextField(
                            value = thresholdText.value,
                            onValueChange = { newValue ->
                                if (newValue.all { it.isDigit() } || newValue.isEmpty()) {
                                    thresholdText.value = newValue
                                    newValue.toIntOrNull()?.let { intValue ->
                                        thresholdState.value = intValue
                                        scope.launch {
                                            activity.applicationContext.appDataStore.edit { it[PrefKeys.ALERT_THRESHOLD] = intValue }
                                        }
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.width(60.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }

                    // Cooldown field
                    Column {
                        Text("Notification cooldown (minutes):")
                        OutlinedTextField(
                            value = cooldownText.value,
                            onValueChange = { new ->
                                if (new.all { it.isDigit() } || new.isEmpty()) {
                                    cooldownText.value = new
                                    new.toIntOrNull()?.let { mins ->
                                        val clamped = mins.coerceAtLeast(0) // allow 0
                                        cooldownState.value = clamped
                                        scope.launch {
                                            activity.applicationContext.appDataStore.edit { it[PrefKeys.COOLDOWN_MINUTES] = clamped }
                                        }
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.width(60.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }

                    // --- Logs ---
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Logs")
                        Button(
                            onClick = {
                                val f = File(activity.filesDir, "orders_widget_debug.txt")
                                if (!f.exists()) {
                                    Toast.makeText(activity, "No logs yet", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                val uri = FileProvider.getUriForFile(
                                    activity,
                                    "${activity.packageName}.fileprovider",
                                    f
                                )
                                // Try to view; if no viewer, fall back to share
                                val view = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "text/plain")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                try {
                                    activity.startActivity(view)
                                } catch (_: Exception) {
                                    val share = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    activity.startActivity(Intent.createChooser(share, "Share logs"))
                                }
                            }
                        ) { Text("Open logs") }
                    }

                }
            }
        }
    }
}
