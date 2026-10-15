package com.bepinex.android.log

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.bepinex.android.BepInExPaths
import kotlinx.coroutines.*

class LogOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayOwner: OverlayLifecycleOwner? = null
    private var scope: CoroutineScope? = null
    private var currentPackageName: String? = null

    companion object {
        private const val TAG = "LogOverlayService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "bepinex_log_overlay"

        fun start(context: Context, packageName: String) {
            val intent = Intent(context, LogOverlayService::class.java).apply {
                putExtra("packageName", packageName)
            }
            // API 31+ bans background FGS starts: a startForegroundService() from
            // the background throws ForegroundServiceStartNotAllowedException and
            // the service never starts. All current callers start from the
            // foreground (settings toggle, bootstrap, game window), but fail
            // gracefully instead of crashing if that ever changes.
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to start overlay service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LogOverlayService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Reads BepInExPaths.getLogFile below; the service can be started
        // without MainActivity having run in this process.
        BepInExPaths.install(this)
        createNotificationChannel()
        // Target API 34+ requires the FGS type at startForeground time;
        // ServiceCompat routes the type param only where the platform supports it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentPackageName = intent?.getStringExtra("packageName") ?: currentPackageName
        if (overlayView == null) {
            createOverlay()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the launcher away should take the overlay with it instead of
        // leaving a START_STICKY service (and window) behind with no owner.
        stopSelf()
    }

    override fun onDestroy() {
        removeOverlay()
        scope?.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BepInEx Log Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when BepInEx floating log is active in-game"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // getLaunchIntentForPackage() returns null when the game was uninstalled
        // after selection; PendingIntent.getActivity() with a null Intent throws,
        // which would kill the service before the overlay ever appears.
        val launchIntent = currentPackageName?.let { packageManager.getLaunchIntentForPackage(it) }
            ?: packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, com.bepinex.android.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BepInEx Log")
            .setContentText("Floating log active -- tap to return to game")
            .setSmallIcon(com.bepinex.android.R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createOverlay() {
        // Overlay permission can be revoked while START_STICKY keeps restarting
        // us; addView() would then throw and crash-loop. Gate and bail out.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            android.util.Log.w(TAG, "Overlay permission revoked; stopping service")
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val owner = OverlayLifecycleOwner()
        overlayOwner = owner
        owner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setContent { overlayContent { removeOverlay(); stopSelf() } }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        overlayView = composeView
        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "addView failed; stopping service", e)
            overlayView = null
            stopSelf()
            return
        }

        owner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {
            }
        }
        overlayView = null
        overlayOwner?.let { owner ->
            owner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            owner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            owner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            owner.viewModelStore.clear()
        }
        overlayOwner = null
    }

    @Composable
    private fun overlayContent(onClose: () -> Unit) {
        var expanded by remember { mutableStateOf(false) }
        val logLines by BepInExLogReader.lines.collectAsStateWithLifecycle()
        val listState = rememberLazyListState()

        val pkg = currentPackageName
        LaunchedEffect(pkg) {
            if (pkg != null && scope == null) {
                val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                scope = newScope
                BepInExLogReader.startWatching(pkg, newScope)
            }
        }

        var posX by remember { mutableFloatStateOf(100f) }
        var posY by remember { mutableFloatStateOf(300f) }

        // Window Manager Position Updates
        fun updatePosition(dx: Float, dy: Float) {
            val params = overlayView?.layoutParams as? WindowManager.LayoutParams
            if (params != null) {
                posX += dx
                posY += dy
                params.x = posX.toInt()
                params.y = posY.toInt()
                windowManager?.updateViewLayout(overlayView, params)
            }
        }

        val dragModifier = Modifier.pointerInput(Unit) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                updatePosition(dragAmount.x, dragAmount.y)
            }
        }

        // Create a thread-safe snapshot of recent log lines
        val currentLines = remember(logLines) { logLines.takeLast(200) }

        LaunchedEffect(currentLines.size) {
            if (currentLines.isNotEmpty()) {
                listState.scrollToItem(currentLines.size - 1)
            }
        }

        if (expanded) {
            Card(
                modifier = Modifier
                    .width(340.dp)
                    .height(440.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xF51E1E2E)
                ),
                border = BorderStroke(1.dp, Color(0xFF313244)),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(dragModifier)
                            .background(Color(0xFF181825))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Terminal,
                            contentDescription = null,
                            tint = Color(0xFF89B4FA),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "BepInEx Log (Drag)",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { expanded = false },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("—", color = Color(0xFF89B4FA), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        TextButton(
                            onClick = onClose,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("✕", color = Color(0xFFF38BA8), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        items(items = currentLines) { line ->
                            val lineColor = when (line.level) {
                                BepInExLogReader.LogLevel.FATAL,
                                BepInExLogReader.LogLevel.ERROR -> Color(0xFFF38BA8)

                                BepInExLogReader.LogLevel.WARNING -> Color(0xFFFAB387)
                                BepInExLogReader.LogLevel.DEBUG -> Color(0xFF6C7086)
                                else -> Color(0xFFCDD6F4)
                            }
                            val tag = line.level.label.firstOrNull()?.toString() ?: "I"
                            Text(
                                text = "[$tag] ${if (line.source.isNotEmpty()) "${line.source} " else ""}${line.message}",
                                color = lineColor,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 4,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .then(dragModifier)
                    .clip(CircleShape)
                    .background(Color(0xEE1E1E2E))
                    .border(1.5.dp, Color(0xFF89B4FA), CircleShape)
                    .clickable { expanded = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Terminal,
                    contentDescription = "BepInEx Log",
                    tint = Color(0xFF89B4FA),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this).apply {
        performRestore(null)
    }
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }
}
