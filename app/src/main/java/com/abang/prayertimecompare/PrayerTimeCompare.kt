package com.abang.prayertimecompare

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore

import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

import android.content.res.Configuration

import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.MediaMetadata
import android.content.pm.ServiceInfo

import android.view.Menu
import android.view.MenuItem
import android.view.LayoutInflater
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

import android.os.SystemClock
import kotlin.math.max


import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.roundToInt
import android.os.Handler

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder

import androidx.core.app.NotificationCompat

import android.util.Log
import android.widget.ImageView
import android.widget.TableRow
import androidx.core.net.toUri
import kotlin.math.sqrt

import androidx.core.widget.TextViewCompat
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.FrameLayout

import androidx.core.content.ContextCompat
import com.abang.prayertimecompare.databinding.ActivityMainBinding
import kotlin.apply
import kotlin.text.toInt



// -------------------------------
// Utilities
// -------------------------------
object TimeUtils {
    fun hmToMinutes(hm: String?): Int? {
        if (hm == null) return null
        val p = hm.trim().split(":", " ")
        if (p.size < 2) return null
        val hh = p[0].replace("\\D".toRegex(), "").toIntOrNull() ?: return null
        val mm = p[1].replace("\\D".toRegex(), "").toIntOrNull() ?: return null
        return hh * 60 + mm
    }

    fun minutesToHM(totalMin: Int): String {
        var m = totalMin
        m %= (24 * 60)
        if (m < 0) m += 24 * 60
        val hh = m / 60
        val mm = m % 60
        return String.format("%02d:%02d", hh, mm)
    }
}

data class PrayerSet(
    val fajr: String? = null,
    val dhuhr: String? = null,
    val asr: String? = null,
    val maghrib: String? = null,
    val isha: String? = null
)

object Prayers {
    val ORDER = listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")
}



// Add these at the top level with other object declarations
private enum class Method12Format {
    MONTH_KEYED, DATE_KEYED
}

private data class ParseResult<T>(val data: T, val errors: List<String> = emptyList())

object Colors {
    val BG = Color.BLACK
    val TITLE_BG = Color.DKGRAY
    val TITLE_TEXT = Color.WHITE
    val TAB_ACTIVE = Color.parseColor("#00BCD4")
    val TAB_INACTIVE = Color.GRAY
    val LOG_TEXT = Color.LTGRAY
    val GREEN = Color.parseColor("#00AA00")
    val ORANGE = Color.parseColor("#FFA500")
    val RED = Color.RED

    // For M12/M99 data source
    val BLUE = Color.parseColor("#2196F3")     // Cached data
    val PURPLE = Color.parseColor("#9C27B0")   // Internet data

    val WHITE = Color.WHITE
}

//------------------------------------------------------------------
// PARTIAL UPDATE GLOBALS
//------------------------------------------------------------------
private var nextPrayerTimer: Runnable? = null
private var dataReady = false

// Top-level PlaybackService for android 13+ so app shows up in the system media controls
// In PrayerTimeCompare.kt
class PlaybackService : Service() {

    private lateinit var mediaSession: MediaSession
    private val CHANNEL_ID = "playback_channel"

    override fun onCreate() {
        super.onCreate()

        mediaSession = MediaSession(this, "PrayerAppSession").apply { isActive = true }
        Log.d("PlaybackService", "MediaSession active? ${mediaSession.isActive}")

        val playbackState = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_STOP
            )
            .setState(PlaybackState.STATE_PLAYING, 0, 1.0f)
            .build()
        mediaSession.setPlaybackState(playbackState)

        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Prayer Audio")
            .build()
        mediaSession.setMetadata(metadata)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        val notificationBuilder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

        @Suppress("DEPRECATION")
        val notification = notificationBuilder
            .setContentTitle("Prayer Audio")
            .setContentText("Adhan is playing")
            .setSmallIcon(R.drawable.ic_speaker_on)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0)
            )
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_stop,
                    "Stop",
                    getActionIntent("ACTION_STOP")
                ).build()
            )
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(1, notification)
        }

    }

    private fun getActionIntent(action: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prayer = intent?.getStringExtra("prayer")
        if (intent?.action == "STOP_AZAN") {
            if (!prayer.isNullOrBlank()) {
                sendPlaybackBroadcast(ACTION_PLAYBACK_STOPPED, prayer)
            }
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!prayer.isNullOrBlank()) {
            // This service only broadcasts state; it does not play audio.
            // PrayerNotificationService handles playback.
            sendPlaybackBroadcast(ACTION_PLAYBACK_STARTED, prayer)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Add this missing function
    private fun sendPlaybackBroadcast(action: String, prayer: String) {
        val intent = Intent(action).apply {
            setPackage(packageName) // Make intent explicit
            putExtra("prayer", prayer)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSession.release()
    }
}

// Global map to track each prayer’s views
// Map now stores label, icon view, and icon container
private val prayerTabs = mutableMapOf<String, Triple<TextView, ImageView, LinearLayout>>()

private var isPlaying: Boolean = false

private lateinit var binding: ActivityMainBinding

private var gracePeriodTimer: Runnable? = null

private val prayerAlarmEnabled = mutableMapOf<String, Boolean>()

private var tabRowLayout: LinearLayout? = null

private const val ACTION_PLAYBACK_STARTED = "com.abang.prayertimecompare.PLAYBACK_STARTED"
private const val ACTION_PLAYBACK_STOPPED = "com.abang.prayertimecompare.PLAYBACK_STOPPED"

private var activePrayer: String? = null

// -------------------------------
// MainActivity
// -------------------------------
class MainActivity : Activity() {

    private val playbackStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val prayer = intent.getStringExtra("prayer") ?: return

            when (intent.action) {
                ACTION_PLAYBACK_STARTED -> {
                    runOnUiThread {
                        // Set state without showing a dialog
                        currentPlayingPrayer = prayer
                        setSpeakerIconColor(prayer, Colors.RED)
                        appendLine("Playback started for $prayer. Icon set to RED.")
                    }
                }
                ACTION_PLAYBACK_STOPPED -> {
                    runOnUiThread {
                        // Notification stop: Reset icon to GREEN (alarm remains enabled)
                        if (prayer == currentPlayingPrayer) {
                            setSpeakerIconColor(prayer, Colors.GREEN)
                            currentPlayingPrayer = null
                            appendLine("Playback stopped for $prayer via notification. Icon set to GREEN.")
                        }
                    }
                }
            }
        }
    }

    // Debug mode long-press handler 5-seconds
    private val debugHandler = Handler(Looper.getMainLooper())
    private val debugLongPressRunnable = Runnable {
        DEBUG_MODE = !DEBUG_MODE
        runOnUiThread {
            Toast.makeText(this, "Debug Mode ${if (DEBUG_MODE) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
        }
        if (DEBUG_MODE) {
            enterDebugMode()
        } else {
            exitDebugMode()
        }
    }
    // Status Bar Priority Management
    private var currentStatusBarPriority = 0
    private val STATUS_BAR_PRIORITIES = object {
        val NORMAL = 0          // Default/clear
        val PRAYER_COUNTDOWN = 1  // Lowest - redundant with big display
        val AUTO_SWITCH = 2      // "Auto-switched to..."
        val GRACE_PERIOD = 3     // "Switching to... in Xs" (HIGH)
        val MANUAL_OVERRIDE = 4  // "Returning to auto-mode..." (HIGHEST)
        val ERROR = 5           // Error messages
    }

    // 🔹 Companion object: shared log buffer + static logging
    companion object {
        const val MAX_LOG_ENTRIES = 200
        var DEBUG_MODE = true
        val allLogs = LinkedHashMap<String, String>()

        fun appendLogEntry(s: String) {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val logEntry = "[$timestamp] $s"

            if (allLogs.size >= MAX_LOG_ENTRIES) {
                allLogs.remove(allLogs.keys.firstOrNull())
            }
            allLogs[System.currentTimeMillis().toString()] = logEntry

            Log.d("PrayerApp", "Stored log: $logEntry")
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_settings -> {
                showGraceSettingsDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    private lateinit var graceTimerView: TextView

    private var currentGracePeriodMinutes = 1 // Start with 1 min for testing
    private var isBlinkingActive = false
    private var blinkState = false
    private val blinkHandler = Handler(Looper.getMainLooper())
    private val blinkTask = object : Runnable {
        override fun run() {
            if (isBlinkingActive) {
                blinkState = !blinkState
                updateBlinkingEffect()
                blinkHandler.postDelayed(this, 500) // Blink every 500ms
            }
        }
    }

    // Add these near your other timer-related fields:

    private var currentPlayingPrayer: String? = null
    private var returnCountdownHandler: Handler? = null
    private var returnCountdownTask: Runnable? = null
    private var gracePeriodMinutes = 1 // Configurable for Stage 2
    private var isAutoMode = true
    private var currentOverridePrayer: String? = null
    private var prayerHighlightHandler = Handler(Looper.getMainLooper())
    private val prayerHighlightTask = object : Runnable {
        override fun run() {
            prayerHighlightHandler.postDelayed(this, 30000) // Check every 30 seconds
        }
    }

    private lateinit var workingBase: File
    private lateinit var backupRoot: File
    private var year: Int = 0
    private var userOverrideExpiry: Long = 0L
    private var currentGracePrayer: String? = null
    private var graceEndTime: Long = 0L
    // Shared data
    private var method12Map: Map<String, PrayerSet> = TreeMap()
    private var method99Map: Map<String, PrayerSet> = TreeMap()
    private val deltaMaps: MutableMap<String, Map<String, Int>> = mutableMapOf() // prayer -> (date -> delta)
    private val coeffsMap: MutableMap<String, DoubleArray> = mutableMapOf() // prayer -> coeffs
    private val m85Predictions: MutableMap<String, Map<String, String>> = mutableMapOf() // prayer -> (date->time)

    private var hasErrors = false
    private val errorMessages = StringBuilder()
    // Add a debug mode flag (you could make this configurable)
    private var DEBUG_MODE = false
    private var debugTapCount = 0

    // Add these as class fields (at the top with other declarations)
    private val handler = Handler(Looper.getMainLooper())
    private val resetDebugCounterTask = Runnable {
        debugTapCount = 0
        if (!DEBUG_MODE) {
            runOnUiThread {
                binding.statusBar.text = ""
            }
        }
    }

    // Updated autoModeChecker - Uses single source of truth
    private val autoModeChecker = object : Runnable {
        override fun run() {
            if (isAutoMode) {
                // Check if a prayer time is happening RIGHT NOW
                val currentPrayer = getCurrentPrayerAtTime()

                if (currentPrayer != null && gracePeriodTimer == null) {
                    // New prayer time detected - start grace period
                    appendLine("Auto-detected: $currentPrayer time starting")
                    startOrResotreGracePeriod(currentPrayer)
                } else if (currentPrayer == null && gracePeriodTimer == null) {
                    // Not in grace period - check normal progression
                    val (nextPrayer, _) = getNextPrayerAndTime()
                    if (nextPrayer != activePrayer) {
                        activePrayer = nextPrayer
                        updateTabStyles()
                        activePrayer?.let { showPrayer(it) }
                        appendLine("Normal progression to: $nextPrayer")
                    }
                }
                // If gracePeriodTimer != null, we're already in grace period
            }
            handler.postDelayed(this, 30000)
        }
    }

    // Which prayer is next in auto-mode (nullable until computed)
    private var nextPrayer: String? = null

    // Manual mode flag (true when user clicks a tab; active for 20s)
    private var manualModeActive: Boolean = false

    // Epoch millis when manual mode should end (now + 20_000)
    private var manualModeEndTime: Long = 0L

    // -----------------------------------------------------
    // Data Maps
    // -----------------------------------------------------
    private val method85Map = mutableMapOf<String, PrayerSet>()


    // -----------------------------------------------------
    // Source tracking
    // -----------------------------------------------------
    private var sourceM12: String = "ERROR"   // CACHE / INTERNET / ERROR
    private var sourceM99: String = "ERROR"
    private val sourceAPI = mutableMapOf<Int, String>() // for method 00–12

    private var activePrayer: String? = null

    @SuppressLint("SetTextI18n")
    // --- Activity lifecycle entry point ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isRestoring = savedInstanceState != null

        // --- Edge-to-edge display setup --- (important for modern UI)
        WindowCompat.setDecorFitsSystemWindows(window, false)



        // --- Initialize ViewBinding ---
        binding = ActivityMainBinding.inflate(layoutInflater)
        graceTimerView = binding.graceTimerView // or findViewById(R.id.graceTimerView)
        setContentView(binding.root)

        // 🔹 Apply initial text size from XML
        val initialSizeSp = resources.getDimension(R.dimen.countdown_text_size) / resources.displayMetrics.scaledDensity
        binding.bigCountdownView.setTextSize(TypedValue.COMPLEX_UNIT_SP, initialSizeSp)

        testDimensLoading()

        // Apply system bar insets (top + bottom) as padding
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        // Initial
        //updateCountdownFrameHeight()

        // Register explicit broadcast receiver (no LocalBroadcastManager)
        // Use ContextCompat for backward compatibility
        ContextCompat.registerReceiver(
            this,
            playbackStateReceiver,
            IntentFilter().apply {
                addAction(ACTION_PLAYBACK_STARTED)
                addAction(ACTION_PLAYBACK_STOPPED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Get current year for directories, etc.
        year = Calendar.getInstance().get(Calendar.YEAR)

        // --- Get app version safely ---
        val appVersion: String = try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            "v${packageInfo.versionName}"
        } catch (e: Exception) {
            "v?"
        }

        // --- Initialize UI using binding ---
        binding.toolbar.title = "PrayerTimeCompare $appVersion"
        binding.rootLayout.setBackgroundColor(Colors.BG)

        // Settings icon click
        binding.settingsIcon.setOnClickListener {
            showGraceSettingsDialog()
        }

        // Initialize table and debug TextView references
        // Already available via binding: binding.contentTable, binding.debugTextView, binding.tableContainer

        // --- Continue with your existing initialization logic ---
        createNotificationChannel()        // System channel, no UI
        showLoadingPlaceholder()           // Already safe
        setupDirectories(year)
        renderInitialTable()
        ensureDefaultGraceSettings()

        // Load alarm enabled status from SharedPreferences
        val alarmPrefs = getSharedPreferences("prayer_alarms", MODE_PRIVATE)
        Prayers.ORDER.forEach { prayer ->
            // Default to 'true' (enabled) if no setting is found
            prayerAlarmEnabled[prayer] = alarmPrefs.getBoolean(prayer, true)
        }


        buildTabs()         // Pass binding.topTabs instead of old variable
        // Defer updateTabStyles() until onRestoreInstanceState or data ready
        setupCountdownArea()
        setupDebugToggle()

        // Log initial configuration
        appendLine("Initial grace config: ${getGracePeriodConfig()}")

        // --- Start background thread for shared data ---
        Thread {
            try {
                appendLine("START: loading shared data for $year")
                loadSharedDataAndComputeIfNeeded()
                appendLine("READY: data load complete.")

                if (!checkAndRequestAlarmPermissions()) {
                    appendLine("Alarm permissions not granted - notifications may be delayed")
                }

                scheduleNextPrayerNotification()
                startSynchronizedAutoChecker()

                // Compute next prayer only once here
                val (detectedPrayer, _) = getNextPrayerAndTime()

                runOnUiThread {
                    // Remove the if(isRestoring) check - ALWAYS render when data is ready
                    activePrayer = detectedPrayer
                    dataReady = true

                    // Always update UI when data is loaded
                    renderTableFromBuffer()
                    updateTabStyles()

                    appendLine("Active prayer: $activePrayer")
                    binding.statusBar.text = ""
                    currentStatusBarPriority = STATUS_BAR_PRIORITIES.NORMAL
                    startAdaptiveSynchronizedCountdown()
                }



            } catch (e: Exception) {
                appendLine("CRASH: ${e.message}")
                e.printStackTrace()
                runOnUiThread { showLoadingPlaceholder() }
            }
        }.start()
        debugRotationState()
    }



    // Modify onDestroy() to clean up blink handler
    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(playbackStateReceiver)
        } catch (_: Exception) { }

        cancelReturnCountdown()
        nextPrayerTimer?.let { handler.removeCallbacks(it) }; nextPrayerTimer = null
        gracePeriodTimer?.let { handler.removeCallbacks(it) }; gracePeriodTimer = null
        handler.removeCallbacks(autoModeChecker)
        isBlinkingActive = false
        blinkHandler.removeCallbacks(blinkTask)
    }

    private fun setSpeakerIconColor(prayer: String, color: Int) {
        val icon = prayerTabs[prayer]?.second ?: return
        icon.setColorFilter(color)
    }

    private fun resolvePrayer(): String? {
        return activePrayer   // may be null until restored or computed
    }

    private fun renderInitialTable() {
        resolvePrayer()?.let { prayer ->
            showPrayer(prayer)          // safe, non‑null
        } ?: showLoadingPlaceholder()   // fallback if null
    }

    private fun renderTableFromBuffer() {
    resolvePrayer()?.let { prayer ->
        showPrayer(prayer)          // safe render when prayer is known
    } ?: showLoadingPlaceholder()   // neutral fallback if still null
}



    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            "prayer_channel",
            "Prayer Notifications",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Azan playback alerts"

            // Keep notification sound controlled by MediaPlayer (optional)
            setSound(null, null)

            enableVibration(true)
            enableLights(true)
            lightColor = Colors.ORANGE
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }


    private fun startOrResotreGracePeriod(
        prayer: String,
        remainingSeconds: Int? = null,
        triggerPlayback: Boolean = true
    ) {
        // Determine duration
        val graceSeconds: Int = if (remainingSeconds != null) {
            remainingSeconds
        } else {
            val graceConfig = getGracePeriodConfig()
            val graceMinutes = graceConfig[prayer] ?: 1 // Default to 1 if missing
            graceMinutes * 60
        }

        appendLine("=== BEGIN GRACE PERIOD FOR $prayer ===")
        if (remainingSeconds == null) {
            appendLine("User preference: ${graceSeconds / 60} minutes (from settings)")
        } else {
            appendLine("Restored remaining: $graceSeconds seconds")
        }

        // Set global grace state
        currentGracePrayer = prayer
        graceEndTime = System.currentTimeMillis() + (graceSeconds * 1000)

        appendLine("=== GRACE PERIOD ACTIVE ===")
        appendLine("Prayer: $prayer, Duration: $graceSeconds seconds")
        appendLine("Will end at: ${SimpleDateFormat("HH:mm:ss").format(Date(graceEndTime))}")

        // Stop prayer countdown (but don't hide the view)
        nextPrayerTimer?.let { handler.removeCallbacks(it) }
        nextPrayerTimer = null

        // UI updates
        runOnUiThread {
            // 1. Replace prayer timer with "🕌 Prayer TIME"
            val orientation = resources.configuration.orientation
            binding.bigCountdownView.text = if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                "🕌\n$prayer TIME"
            } else {
                "🕌 $prayer TIME"
            }



            binding.bigCountdownView.setTextColor(Color.GREEN)
            binding.bigCountdownView.visibility = View.VISIBLE

            // 2. Show grace timer below table
            graceTimerView.visibility = View.VISIBLE
            updateGraceTimerDisplay(graceSeconds, prayer)

            // 3. Force UI to show current prayer tab
            activePrayer = prayer
            updateTabStyles()
            activePrayer?.let { showPrayer(it) }

            // 4. Update status bar
            binding.statusBar.text = "Next prayer: ${getActualNextPrayer(prayer)}"
        }

        // Optional playback/notification trigger
        if (triggerPlayback && remainingSeconds == null) {
            // Place any playback/notification logic here
            // e.g., playPrayerNotification(prayer)
        }

        var secondsRemaining = graceSeconds

        gracePeriodTimer = object : Runnable {
            override fun run() {
                if (secondsRemaining > 0) {
                    val nowWall = System.currentTimeMillis()
                    val ms = (nowWall % 1000).toInt()

                    updateGraceTimerDisplay(secondsRemaining, prayer)

                    // Update status bar
                    val actualNextPrayer = getActualNextPrayer(prayer)

                    secondsRemaining--

                    val millisUntilNextSecond = max(1, 1000 - ms)
                    val nextUptime = SystemClock.uptimeMillis() + millisUntilNextSecond
                    handler.postAtTime(this, nextUptime)

                } else {
                    endGracePeriod()
                }
            }
        }

        handler.post(gracePeriodTimer as Runnable)
    }

    // Also need to update updateGraceTimerDisplay() to ensure it shows "Now is Prayer"
    private fun updateGraceTimerDisplay(seconds: Int, prayer: String) {
        runOnUiThread {
            val minutes = seconds / 60
            val remainingSeconds = seconds % 60

            graceTimerView.text = if (seconds >= 60) {
                "${minutes}m ${remainingSeconds}s"
            } else {
                "${seconds}s"
            }

            // Get user's grace time for color coding
            val graceConfig = getGracePeriodConfig()
            val totalGraceSeconds = (graceConfig[prayer] ?: 1) * 60

            // Color coding based on percentage of grace period remaining
            val percentRemaining = seconds.toFloat() / totalGraceSeconds

            graceTimerView.setTextColor(
                when {
                    percentRemaining > 0.5 -> Color.YELLOW          // First half
                    percentRemaining > 0.1 -> Color.parseColor("#FFA500") // Orange
                    else -> Color.RED                               // Last 10%
                }
            )
        }
    }

    private fun getGracePeriodConfig(): Map<String, Int> {
        val prefs = getSharedPreferences("grace_settings", MODE_PRIVATE)
        return mapOf(
            "Fajr" to prefs.getInt("fajr_grace", 5),
            "Dhuhr" to prefs.getInt("dhuhr_grace", 10),
            "Asr" to prefs.getInt("asr_grace", 10),
            "Maghrib" to prefs.getInt("maghrib_grace", 3),
            "Isha" to prefs.getInt("isha_grace", 10)
        )
    }

    // New method to update blinking effect
    private fun updateBlinkingEffect() {
        runOnUiThread {
            // Find the current prayer time cell in the table and blink it
            // This is a simplified implementation - we need to find the specific TextView
            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val todayStr = dateFmt.format(Date())

            // We'll blink the status bar as a simple visual indicator for now
            // In next stage, we'll implement proper table cell blinking
            if (blinkState) {
                binding.statusBar.setBackgroundColor(Color.BLUE)
            } else {
                binding.statusBar.setBackgroundColor(Color.DKGRAY)
            }
        }
    }

    // Updated endGracePeriod() - Clears global state
    private fun endGracePeriod() {
        appendLine("=== GRACE PERIOD ENDED ===")

        // Clear global grace state
        val endedPrayer = currentGracePrayer
        currentGracePrayer = null
        graceEndTime = 0L

        gracePeriodTimer?.let { handler.removeCallbacks(it) }
        gracePeriodTimer = null

        runOnUiThread {
            // 1. HIDE BOTH special displays IMMEDIATELY
            // a) Clear the "🕌 Prayer TIME" from prayer timer position
            binding.bigCountdownView.text = ""
            binding.bigCountdownView.visibility = View.GONE

            // b) Hide grace timer below table
            graceTimerView.visibility = View.GONE
            graceTimerView.text = ""

            // 2. Get actual next prayer
            val (nextPrayer, _) = getNextPrayerAndTime()

            // 3. Switch to next prayer IMMEDIATELY
            activePrayer = nextPrayer
            updateTabStyles()
            activePrayer?.let { showPrayer(it) }

            // 4. Update status bar
            // Show brief confirmation
            updateStatusBarWithPriority(
                "Switched to $nextPrayer",
                STATUS_BAR_PRIORITIES.GRACE_PERIOD
            )
            // Clear after 2 seconds
            handler.postDelayed({
                clearStatusBarIfLowerPriority(STATUS_BAR_PRIORITIES.NORMAL)
            }, 2000)

            // 5. RESTART prayer timer IMMEDIATELY (NO DELAY)
            startSimpleCountdown()
        }

        appendLine("Grace period for $endedPrayer ended, switched to $activePrayer")
    }

    private fun debugLayoutStructure() {
        runOnUiThread {
            appendLine("=== LAYOUT STRUCTURE ===")
            appendLine("Root layout has ${binding.rootLayout.childCount} children:")

            for (i in 0 until binding.rootLayout.childCount) {
                val child = binding.rootLayout.getChildAt(i)
                val visibility = if (child.visibility == View.VISIBLE) "VISIBLE"
                else if (child.visibility == View.GONE) "GONE"
                else "INVISIBLE"

                appendLine("  [$i] ${child.javaClass.simpleName} - $visibility")

                if (child == binding.bigCountdownView) {
                    appendLine("     ^ THIS IS THE COUNTDOWN VIEW")
                    appendLine("     Text: ${(child as TextView).text}")
                    appendLine("     Height: ${child.height}")
                }
            }
        }
    }

    private fun debugRotationState() {
        appendLine("=== ROTATION DEBUG ===")
        appendLine("dataReady: $dataReady")
        appendLine("method99Map size: ${method99Map.size}")
        appendLine("method12Map size: ${method12Map.size}")
        appendLine("activePrayer: $activePrayer")
        appendLine("contentTable child count: ${binding.contentTable.childCount}")
    }
    // Add this method for testing
    private fun testCountdownNow() {
        appendLine("=== MANUAL COUNTDOWN TEST ===")

        // Test 1: Show countdown immediately
        showBigCountdown(45, "Fajr")
        debugLayoutStructure()

        // Test 2: Start grace period
        handler.postDelayed({
            appendLine("Starting grace period in 3 seconds...")
            startOrResotreGracePeriod("Dhuhr")
        }, 3000)
    }

    // -------------------------------
    // UI helpers
    // -------------------------------

    private fun applyCountdownDimens() {
        val sizePx = resources.getDimension(R.dimen.countdown_text_size)
        binding.bigCountdownView.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)

        val topMargin = resources.getDimensionPixelSize(R.dimen.countdown_margin_top)
        val bottomMargin = resources.getDimensionPixelSize(R.dimen.countdown_margin_bottom)
        (binding.bigCountdownView.layoutParams as ViewGroup.MarginLayoutParams).apply {
            this.topMargin = topMargin
            this.bottomMargin = bottomMargin
        }
        binding.bigCountdownView.requestLayout()
    }

    private fun maybeExpireManualMode() {
        if (manualModeActive && System.currentTimeMillis() >= manualModeEndTime) {
            manualModeActive = false
            updateTabStyles()
        }
    }
    private fun buildTabs() {
        binding.topTabs.removeAllViews()
        binding.topTabs.orientation = LinearLayout.VERTICAL

        val tabWeights = listOf(0.18f, 0.22f, 0.16f, 0.27f, 0.17f)

        val iconRowHeight = 24.dpToPx()
        val tabRowHeight = 36.dpToPx()

        // Row 1: Speaker icons (no background to keep it minimal)
        val iconRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 1f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                iconRowHeight
            )
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Row 2: Prayer tabs
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 1f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                tabRowHeight
            )
        }
        tabRowLayout = tabRow

        prayerTabs.clear()

        for ((index, prayer) in Prayers.ORDER.withIndex()) {
            val weight = tabWeights[index]

            // Icon container + icon
            val iconCell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
            }

            val prefs = getSharedPreferences("prayer_alarms", MODE_PRIVATE)
            val isEnabled = prefs.getBoolean(prayer, true)
            prayerAlarmEnabled[prayer] = isEnabled

            val iconView = ImageView(this)

            // Configure layout and appearance
            iconView.layoutParams = LinearLayout.LayoutParams(20.dpToPx(), 20.dpToPx())
            iconView.setImageResource(R.drawable.ic_speaker_on)
            iconView.setColorFilter(if (isEnabled) Colors.GREEN else Colors.WHITE)

            // Attach click listener
            iconView.setOnClickListener {
                val playing = currentPlayingPrayer
                if (playing == prayer) {
                    // CASE 1: Azan is currently playing for THIS prayer (icon is RED).
                    // Stop playback and disable the alarm (icon becomes WHITE).
                    val serviceIntent = Intent(this, PrayerNotificationService::class.java).apply {
                        action = "STOP_AZAN"
                    }
                    startService(serviceIntent) // This will stop the media player in the service.

                    // Manually update UI state immediately.
                    setSpeakerIconColor(prayer, Colors.WHITE)
                    currentPlayingPrayer = null
                    prayerAlarmEnabled[prayer] = false
                    val prefs = getSharedPreferences("prayer_alarms", MODE_PRIVATE)
                    prefs.edit().putBoolean(prayer, false).apply()
                    appendLine("Playback stopped for $prayer via icon click. Alarm set to OFF. Icon set to WHITE.")

                } else {
                    // CASE 2: Azan is NOT playing for this prayer. Toggle alarm ON/OFF.
                    val newState = !(prayerAlarmEnabled[prayer] ?: true)
                    prayerAlarmEnabled[prayer] = newState
                    val prefs = getSharedPreferences("prayer_alarms", MODE_PRIVATE)
                    prefs.edit().putBoolean(prayer, newState).apply()
                    setSpeakerIconColor(prayer, if (newState) Colors.GREEN else Colors.WHITE)
                    appendLine("Alarm for $prayer: ${if (newState) "ON" else "OFF"}")
                }
            }

            iconCell.addView(iconView)
            iconRow.addView(iconCell)

            // Tab cell + label
            val tabCell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
                setBackgroundColor(Colors.TAB_INACTIVE)
            }

            val label = TextView(this).apply {
                text = prayer
                gravity = Gravity.CENTER
                setPadding(6.dpToPx(), 4.dpToPx(), 6.dpToPx(), 4.dpToPx())
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                //Preserves auto sizing across devices
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this, 12, 18, 1, TypedValue.COMPLEX_UNIT_SP
                )
            }

            tabCell.setOnClickListener {
                val (trueCurrentPrayer, _) = getNextPrayerAndTime()
                val clickedPrayer = prayer

                appendLine("=== TAB CLICK ===")
                appendLine("Clicked: $clickedPrayer, True current: $trueCurrentPrayer")

                // DEBUG MODE HANDLING: Clicking any tab exits debug mode
                if (DEBUG_MODE) {
                    appendLine("DEBUG MODE EXIT: User clicked tab to exit debug mode")
                    exitDebugMode()
                    // Do not return; proceed to handle the tab click normally after exiting debug.
                }

                // CASE 1: Clicking current tab while ALREADY in auto-mode (no override active)
                if (clickedPrayer == activePrayer && isAutoMode) {
                    appendLine("Already in auto-mode on correct tab - no action")
                    return@setOnClickListener  // Do NOTHING
                }

                // CASE 2: Clicking the TRUE current prayer (explicitly returning to auto-mode)
                if (clickedPrayer == trueCurrentPrayer) {
                    appendLine("Returning to auto-mode for $trueCurrentPrayer")

                    // Cancel any override timer
                    cancelReturnCountdown()

                    // Return to auto-mode
                    isAutoMode = true
                    manualModeActive = false   // <-- reset manual mode
                    activePrayer = trueCurrentPrayer

                    activePrayer?.let { showPrayer(it) }

                    // Restart the main prayer countdown timer
                    startSimpleCountdown()

                    updateTabStyles()          // <-- recompute styles
                    return@setOnClickListener
                }

                // CASE 3: Clicking a DIFFERENT prayer (manual override)
                appendLine("Manual override to: $clickedPrayer")

                // This action implies we are no longer in auto mode.
                isAutoMode = false
                activePrayer = clickedPrayer

                // Start manual mode window (20 seconds)
                manualModeActive = true
                manualModeEndTime = System.currentTimeMillis() + 20_000

                showPrayer(clickedPrayer)
                appendLine("Manual selection: $clickedPrayer (auto-return in 20s)")

                // Start 20s countdown to return to auto
                startReturnCountdown()

                updateTabStyles()              // <-- recompute styles immediately
            }


            tabCell.addView(label)
            tabRow.addView(tabCell)

            // Store all pieces for unified styling
            prayerTabs[prayer] = Triple(label, iconView, tabCell)
        }

        binding.topTabs.addView(iconRow)
        binding.topTabs.addView(tabRow)

    }


    private fun startReturnCountdown() {
    // Cancel any existing countdown first
    cancelReturnCountdown()

    appendLine("Starting 20-second return countdown...")

    // Mark manual override
    isAutoMode = false
    currentOverridePrayer = activePrayer
    userOverrideExpiry = System.currentTimeMillis() + 20_000L

    // Also set manual mode flags
    manualModeActive = true
    manualModeEndTime = userOverrideExpiry

    // Ensure readable status bar styling
    binding.statusBar.setBackgroundColor(Color.DKGRAY)
    binding.statusBar.setTextColor(Color.WHITE)

    returnCountdownHandler = Handler(Looper.getMainLooper())
    returnCountdownTask = object : Runnable {
        override fun run() {
            // Stop if override was cleared elsewhere
            if (currentOverridePrayer == null || isAutoMode) {
                clearStatusBarIfLowerPriority(STATUS_BAR_PRIORITIES.NORMAL)
                return
            }

            val remaining = ((userOverrideExpiry - System.currentTimeMillis()) / 1000L).coerceAtLeast(0)

            if (remaining > 0) {
                // Visible countdown message
                updateStatusBarWithPriority(
                    "Returning to auto-mode in ${remaining}s",
                    STATUS_BAR_PRIORITIES.MANUAL_OVERRIDE
                )
                binding.statusBar.setTextColor(Color.WHITE)
                binding.statusBar.setBackgroundColor(Color.DKGRAY)

                // Continue countdown
                returnCountdownHandler?.postDelayed(this, 1000L)
            } else {
                appendLine("Return countdown finished; switching back to auto-mode.")

                // Reset flags
                isAutoMode = true
                manualModeActive = false
                manualModeEndTime = 0L
                currentOverridePrayer = null

                // Return to auto-mode prayer
                returnToCurrentPrayer()

                // Recompute tab styles immediately
                updateTabStyles()

                // Clear status bar
                clearStatusBarIfLowerPriority(STATUS_BAR_PRIORITIES.NORMAL)
            }
        }
    }

    // Start countdown immediately
    returnCountdownHandler?.post(returnCountdownTask!!)
}

    private fun cancelReturnCountdown() {
        appendLine("Cancelling return countdown...")

        // Stop the handler
        returnCountdownHandler?.removeCallbacksAndMessages(null)
        returnCountdownHandler = null
        returnCountdownTask = null

        // Clear display
        binding.statusBar.text = ""
    }

    private fun returnToCurrentPrayer() {
        appendLine("=== RETURNING TO AUTO-MODE ===")

        // Cancel the 20s override timer
        cancelReturnCountdown()

        val (trueCurrentPrayer, _) = getNextPrayerAndTime()

        appendLine("True current prayer: $trueCurrentPrayer")
        appendLine("Currently showing: $activePrayer")

        // Only switch if it's actually a different prayer
        if (trueCurrentPrayer != activePrayer) {
            appendLine("Switching to $trueCurrentPrayer")
            activePrayer = trueCurrentPrayer
            isAutoMode = true
            currentOverridePrayer = null
            userOverrideExpiry = 0
            updateTabStyles()
            activePrayer?.let { showPrayer(it) }
        } else {
            appendLine("Already on correct tab: $trueCurrentPrayer")
            isAutoMode = true
            currentOverridePrayer = null
            userOverrideExpiry = 0
        }

        // CRITICAL: Restart the prayer countdown timer!
        startSimpleCountdown()
        appendLine("Prayer countdown timer restarted")
    }

    // Helper to apply alpha to a color
    fun Int.withAlpha(alpha: Int): Int =
        Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))

    private fun updateTabStyles() {
        val now = System.currentTimeMillis()
        val manualStillActive = manualModeActive && now < manualModeEndTime

        val nextPrayerName = nextPrayer ?: getNextPrayerAndTime().first
        val nextHighlight = Color.parseColor("#15656e") // green
        val activeBg = Color.parseColor("#888888")      // light gray
        val inactiveBg = Color.parseColor("#666666")    // dark gray
        val white = Color.parseColor("#ffffff")

        // Optionally expire manual mode if past end time
        if (manualModeActive && !manualStillActive) {
            manualModeActive = false
        }

        prayerTabs.forEach { (prayer, triple) ->
            val (label, icon, tabCell) = triple

            when {
                manualStillActive && prayer == activePrayer -> {
                    tabCell.setBackgroundColor(activeBg)
                    label.setTextColor(white.withAlpha(0xFF))
                }
                manualStillActive && prayer == nextPrayerName -> {
                    tabCell.setBackgroundColor(nextHighlight)
                    label.setTextColor(white.withAlpha(0xAD))
                }
                manualStillActive -> {
                    tabCell.setBackgroundColor(inactiveBg)
                    label.setTextColor(white.withAlpha(0xAD))
                }
                !manualStillActive && prayer == activePrayer && prayer == nextPrayerName -> {
                    tabCell.setBackgroundColor(nextHighlight)
                    label.setTextColor(white.withAlpha(0xFF))
                }
                !manualStillActive && prayer != activePrayer -> {
                    tabCell.setBackgroundColor(inactiveBg)
                    label.setTextColor(white.withAlpha(0xAD))
                }
                else -> {
                    tabCell.setBackgroundColor(activeBg)
                    label.setTextColor(white.withAlpha(0xFF))
                }
            }

            // Icon coloring (unchanged)
            val isEnabled = prayerAlarmEnabled[prayer] == true
            val playingPrayer = currentPlayingPrayer
            if (playingPrayer != null && prayer == playingPrayer) {
                icon.setColorFilter(Colors.RED)
            } else {
                icon.setColorFilter(if (isEnabled) Colors.GREEN else Colors.WHITE)
            }
        }
    }


    // In showPrayer(), replace status bar updates:
    private fun showPrayer(prayer: String) {
        appendLine("DEBUG showPrayer called for: $prayer")
        appendLine("DEBUG DEBUG_MODE: $DEBUG_MODE")

        // Check if data maps are loaded
        if (method99Map.isEmpty() || method12Map.isEmpty()) {
            appendLine("DEBUG: Data not loaded yet, showing placeholder")
            showLoadingPlaceholder()
            return
        }

        runOnUiThread {
            appendLine("DEBUG In showPrayer UI thread")
            appendLine("DEBUG contentTable visibility: ${binding.contentTable.visibility}")
            appendLine("DEBUG contentTable width: ${binding.contentTable.width}, height: ${binding.contentTable.height}")

            if (DEBUG_MODE) {
                // Debug mode: show logs in debugTextView, hide table
                binding.debugTextView.visibility = View.VISIBLE
                binding.contentTable.visibility = View.GONE

                val allLogsText = allLogs.values.joinToString("\n")
                binding.debugTextView.text = allLogsText + "\n=== Displaying prayer: $prayer ==="
            } else {
                // Normal mode: show table, hide debug text
                binding.debugTextView.visibility = View.GONE
                binding.contentTable.visibility = View.VISIBLE

                val dates = buildWindow(3)
                buildPrayerTable(prayer, dates)

                // Force redraw
                binding.contentTable.post {
                    binding.contentTable.invalidate()
                    binding.contentScroll.fullScroll(View.FOCUS_UP)
                }
            }
        }
    }

    private fun addPrayerDataRow(
        rowName: String,
        prayer: String,
        dates: List<Date>,
        map: Map<String, PrayerSet>,
        dataSource: String,
        nextPrayer: String?,
        nextPrayerTime: String?,
        todayStr: String?
    ) {
        val row = TableRow(this)

        // Row name cell with appropriate color
        val rowColor = when (dataSource) {
            "CACHE" -> Colors.BLUE
            "INTERNET" -> Colors.PURPLE
            else -> Colors.RED
        }
        row.addView(createDataCell(rowName, rowColor))

        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getDefault()

        for (date in dates) {
            val key = fmt.format(date)
            val tv = getPrayerTimeFromSet(map[key], prayer) ?: "---"

            // Check for highlighting (M99 only)
            val shouldHighlight = (nextPrayer != null &&
                    nextPrayerTime != null &&
                    prayer == nextPrayer &&
                    tv == nextPrayerTime &&
                    isDateColumnForNextPrayer(key, dates, nextPrayer, nextPrayerTime))

            if (shouldHighlight && rowName == "M99") {
                row.addView(createHighlightedCell(tv))
            } else {
                row.addView(createDataCell(tv, rowColor))
            }
        }

        binding.contentTable.addView(row)
    }

    private fun addM85AccuracyRow(
        prayer: String,
        dates: List<Date>,
        nextPrayer: String?,
        nextPrayerTime: String?,
        todayStr: String?
    ) {
        val row = TableRow(this)
        row.addView(createDataCell("M85", Colors.WHITE))  // White label

        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getDefault()

        for (date in dates) {
            val key = fmt.format(date)
            val t85 = getPrayerTimeFromSet(method85Map[key], prayer)
            val t99 = getPrayerTimeFromSet(method99Map[key], prayer)

            val color = if (t85 == null) {
                Colors.RED
            } else if (t99 == null) {
                Colors.ORANGE
            } else {
                val diff = abs(minutesBetween(t85, t99))
                when {
                    diff <= 1 -> Colors.GREEN
                    diff <= 2 -> Colors.ORANGE
                    else -> Colors.RED
                }
            }

            row.addView(createDataCell(t85 ?: "---", color))
        }

        binding.contentTable.addView(row)
    }

    private fun addDiffRow(
        prayer: String,
        dates: List<Date>,
        nextPrayer: String?,
        nextPrayerTime: String?,
        todayStr: String?
    ) {
        val row = TableRow(this)
        row.addView(createDataCell("dt"))

        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        dateFmt.timeZone = TimeZone.getDefault()
        val preds = m85Predictions[prayer] ?: emptyMap()

        for (date in dates) {
            val dateStr = dateFmt.format(date)
            val m99 = getPrayerTimeFromSet(method99Map[dateStr], prayer) ?: "---"
            val m85 = preds[dateStr] ?: "---"
            val diff = computeDtString(m99, m85)
            val color = colorForDtString(diff)
            row.addView(createDataCell(diff, color))
        }

        binding.contentTable.addView(row)
    }


    private fun createHeaderCell(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Colors.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 8.dpToPx())
            layoutParams = TableRow.LayoutParams(
                0,
                TableRow.LayoutParams.WRAP_CONTENT,
                1.0f
            )
        }
    }

    private fun createDataCell(text: String, color: Int = Colors.WHITE): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(color)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(8.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())
            layoutParams = TableRow.LayoutParams(
                0,
                TableRow.LayoutParams.WRAP_CONTENT,
                1.0f
            )
        }
    }

    private fun createHighlightedCell(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1565C0"))  // Blue background
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)  // This creates MONOSPACE_BOLD
            gravity = Gravity.CENTER
            setPadding(8.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())
            layoutParams = TableRow.LayoutParams(
                0,
                TableRow.LayoutParams.WRAP_CONTENT,
                1.0f
            )
        }
    }

    private fun addSeparatorRow(numColumns: Int) {
        val separatorRow = TableRow(this)

        for (i in 0 until numColumns) {
            val separator = TextView(this).apply {
                text = "--------"
                setTextColor(Colors.LOG_TEXT)
                gravity = Gravity.CENTER
                setPadding(4.dpToPx(), 1.dpToPx(), 4.dpToPx(), 2.dpToPx())
                layoutParams = TableRow.LayoutParams(
                    0,
                    TableRow.LayoutParams.WRAP_CONTENT,
                    1.0f
                )
            }
            separatorRow.addView(separator)
        }

        binding.contentTable.addView(separatorRow)
    }

    private fun buildPrayerTable(prayer: String, dates: List<Date>) {
        appendLine("DEBUG buildPrayerTable called for: $prayer")
        appendLine("DEBUG dates count: ${dates.size}")
        appendLine("DEBUG method99Map size: ${method99Map.size}")
        appendLine("DEBUG UI Thread: ${Looper.myLooper() == Looper.getMainLooper()}")

        runOnUiThread {
            appendLine("DEBUG In runOnUiThread, contentTable child count: ${binding.contentTable.childCount}")
            // Clear existing table
            binding.contentTable.removeAllViews()
            appendLine("DEBUG Table cleared, new child count: ${binding.contentTable.childCount}")

            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val humanFmt = SimpleDateFormat("EEE dd", Locale.US)
            dateFmt.timeZone = TimeZone.getDefault()
            humanFmt.timeZone = TimeZone.getDefault()

            val (nextPrayer, nextPrayerTime) = getNextPrayerAndTime()
            val todayStr = dateFmt.format(Date())
            val numColumns = dates.size + 1

            // ========== HEADER ROW ==========
            val headerRow = TableRow(this)
            headerRow.addView(createHeaderCell("Mth"))

            for (date in dates) {
                val dateLabel = humanFmt.format(date)
                headerRow.addView(createHeaderCell(dateLabel))
            }

            binding.contentTable.addView(headerRow)

            // ========== SEPARATOR ROW ==========
            addSeparatorRow(numColumns)

            // ========== DATA ROWS ==========
            addPrayerDataRow("M12", prayer, dates, method12Map, sourceM12, nextPrayer, nextPrayerTime, todayStr)
            addM85AccuracyRow(prayer, dates, nextPrayer, nextPrayerTime, todayStr)
            addPrayerDataRow("M99", prayer, dates, method99Map, sourceM99, nextPrayer, nextPrayerTime, todayStr)
            addDiffRow(prayer, dates, nextPrayer, nextPrayerTime, todayStr)

            // ========== FINAL SEPARATOR ==========
            addSeparatorRow(numColumns)

            appendLine("DEBUG Table built, final child count: ${binding.contentTable.childCount}")

        }
    }

    private fun updateCountdownViewForOrientation() {
        val orientation = resources.configuration.orientation

        // Update layout params if needed
        val params = binding.bigCountdownView.layoutParams as? FrameLayout.LayoutParams
        params?.let {
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                it.width = FrameLayout.LayoutParams.MATCH_PARENT
                binding.bigCountdownView.maxLines = 1
            } else {
                it.width = FrameLayout.LayoutParams.WRAP_CONTENT
                binding.bigCountdownView.maxLines = 2
            }
            binding.bigCountdownView.layoutParams = it
        }

        // Request layout refresh
        binding.bigCountdownView.requestLayout()
    }




    // -------------------------------
    // Directories & backup
    // -------------------------------
    private fun setupDirectories(year: Int) {
        workingBase = File(getExternalFilesDir(null), "PrayerTimesData/$year")
        if (!workingBase.exists()) {
            val cr = workingBase.mkdirs()
            appendLine("Created working dir: ${workingBase.absolutePath} -> $cr")
        } else appendLine("Using working dir: ${workingBase.absolutePath}")

        backupRoot = if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "PrayerTimesData/$year")
        } else {
            File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "PrayerTimesBackup/$year")
        }
        if (!backupRoot.exists()) {
            val cr = backupRoot.mkdirs()
            appendLine("Created backup dir: ${backupRoot.absolutePath} -> $cr")
        } else appendLine("Using backup dir: ${backupRoot.absolutePath}")
    }

    private fun saveToBackup(content: String, fileName: String) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/PrayerTimesBackup/$year/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
            if (uri == null) {
                appendLine("ERROR: MediaStore returned null URI while saving $fileName")
                return
            }

            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray())
            }

            // Mark as complete (mandatory for Android 10+)
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)

            appendLine("Backed up via MediaStore: $fileName")

        } catch (e: Exception) {
            appendLine("MediaStore save failed for $fileName: ${e.message}")
        }
    }

    // -------------------------------
    // Shared data loading + compute deltas/predictions
    // -------------------------------
    private fun loadSharedDataAndComputeIfNeeded() {
        loadMethod12Data()
        loadMethod99Data()
        loadOrComputeDeltas()
        loadCoefficients(this)
        computePredictions()
        computeAnnualRMSE()
    }

    // 1.loadMethod12Data()
    //- Handles loading or fetching of method12.json.
    //- Populates method12Map.
    private fun loadMethod12Data() {
        val file12 = File(workingBase, "method12.json")
        if (file12.exists()) {
            method12Map = parseMethod12String(file12.readText())
            sourceM12 = "CACHE"
        } else {
            // FIRST try to restore from backup
            val restored = restoreFromBackup("method12.json")
            if (restored != null) {
                method12Map = parseMethod12String(restored)
                sourceM12 = "BACKUP"  // New source type!
                // Save restored data to working dir for next time
                saveText(file12, restored)
                appendLine("Restored method12.json from backup")
            } else {
                // Only fetch from internet if backup also fails
                appendLine("method12.json not found — fetching from Aladhan...")
                try {
                    val jsonStr = fetchMethod12ForYear(year)  // ← YOUR ACTUAL FUNCTION!
                    saveText(file12, jsonStr)
                    saveToBackup(jsonStr, "method12.json")
                    method12Map = parseMethod12String(jsonStr)
                    sourceM12 = "INTERNET"
                } catch (e: Exception) {
                    appendLine("Failed to fetch method12.json: ${e.message}")
                    method12Map = TreeMap()
                    sourceM12 = "ERROR"
                }
            }
        }
    }

    private fun restoreFromBackup(fileName: String): String? {
        return try {
            val uri = findBackupUri(fileName)
            if (uri != null) {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.bufferedReader().readText()
                }
            } else {
                null
            }
        } catch (e: Exception) {
            appendLine("Restore failed for $fileName: ${e.message}")
            null
        }
    }

    private fun findBackupUri(fileName: String): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(fileName)

        contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                return Uri.withAppendedPath(MediaStore.Files.getContentUri("external"), id.toString())
            }
        }
        return null
    }



    // 2. loadMethod99Data() - Handles loading or fetching of method99.json.
    //- Populates method99Map.
    private fun loadMethod99Data() {
        val file99 = File(workingBase, "method99.json")
        if (file99.exists()) {
            appendLine("Found method99.json, parsing...")
            method99Map = parseMethod99String(file99.readText(), year)
            sourceM99 = "CACHE"
        } else {
            // FIRST try to restore from backup
            val restored = restoreFromBackup("method99.json")
            if (restored != null) {
                method99Map = parseMethod99String(restored, year)
                sourceM99 = "BACKUP"
                saveText(file99, restored)
                appendLine("Restored method99.json from backup")
            } else {
                // Only fetch from website if backup also fails
                appendLine("method99.json not found — fetching from website...")
                try {
                    val html = fetchHtml("https://www.mosquee-proche.com/en/mosquee/m-fontenay-sous-bois/468/")
                    val js = extractCalendar(html) ?: throw Exception("Could not extract calendar JS")
                    saveText(file99, js)
                    saveToBackup(js, "method99.json")
                    method99Map = parseMethod99String(js, year)
                    sourceM99 = "INTERNET"
                } catch (e: Exception) {
                    appendLine("Failed to fetch method99.json: ${e.message}")
                    method99Map = TreeMap()
                    sourceM99 = "ERROR"
                }
            }
        }
        appendLine("method12 entries: ${method12Map.size}, method99 entries: ${method99Map.size}")
    }

    // 3. loadOrComputeDeltas() Handles loading or computing delta files for each prayer.
    //- Loads existing delta files for each prayer.
    //- Computes new deltas if files are missing.
    //- Populates deltaMaps.
    private fun loadOrComputeDeltas() {
        for (p in Prayers.ORDER) {
            val deltaFile = File(workingBase, "delta_${p.toLowerCase()}.json")
            if (deltaFile.exists()) {
                appendLine("Loading existing delta for $p")
                deltaMaps[p] = loadDeltaFile(deltaFile)
                appendLine("Loaded delta_${p.toLowerCase()}.json (${deltaMaps[p]?.size ?: 0} entries)")
            } else {
                appendLine("delta_${p.toLowerCase()}.json missing — attempting to compute from method99 & method12")
                val computed = computeDeltaForPrayer(p)
                if (computed.isNotEmpty()) {
                    val jo = JSONObject(computed)
                    saveText(deltaFile, jo.toString(2))
                    saveToBackup(jo.toString(2), "delta_${p.toLowerCase()}.json")
                    deltaMaps[p] = computed.mapValues { it.value }
                    appendLine("Computed and saved delta_${p.toLowerCase()}.json (${computed.size} entries)")
                } else {
                    appendLine("Could not compute delta for $p (insufficient data).")
                    deltaMaps[p] = emptyMap()
                }
            }
        }
    }

    // 4 loadCoefficients()
    //- Loads coefficients from method85_coefficients.json.
    //- Populates coeffsMap.
    private fun loadCoefficients(context: Context) {
        val fname = "method85_coefficients.json"

        // 1️⃣ Try working directory first (for potential updates)
        val primary = File(workingBase, fname)
        var raw: String? = null

        if (primary.exists()) {
            try {
                raw = primary.readText()
                appendLine("Loaded $fname from working directory")
            } catch (e: Exception) {
                appendLine("Failed reading working copy: ${e.message}")
            }
        }

        // 2️⃣ ALWAYS FALLBACK TO ASSETS (guaranteed to exist)
        if (raw == null) {
            raw = loadFromAssets(context, fname)
            appendLine("Loaded $fname from app assets (built-in)")

            // Optional: Cache in working directory for faster future access
            try {
                primary.writeText(raw!!)
                appendLine("Cached $fname in working directory")
            } catch (e: Exception) {
                // Not critical - we still have assets fallback
            }
        }

        // 3️⃣ PARSE COEFFICIENTS (always succeeds now)
        try {
            val jo = JSONObject(raw!!)
            for (p in Prayers.ORDER) {
                if (!jo.has(p)) continue

                val elem = jo.get(p)
                when (elem) {
                    is org.json.JSONArray -> {
                        val darr = DoubleArray(elem.length())
                        for (i in 0 until elem.length()) darr[i] = elem.optDouble(i)
                        coeffsMap[p] = darr
                    }
                    is JSONObject -> {
                        val arr = elem.optJSONArray("coefficients")
                        if (arr != null) {
                            val darr = DoubleArray(arr.length())
                            for (i in 0 until arr.length()) darr[i] = arr.optDouble(i)
                            coeffsMap[p] = darr
                        }
                    }
                }
            }
            appendLine("Loaded coefficients for: ${coeffsMap.keys}")

        } catch (e: Exception) {
            appendLine("Failed parsing $fname: ${e.message}")
        }
    }

    private fun loadFromAssets(context: Context, fileName: String): String {
        return context.assets.open(fileName).bufferedReader().use { it.readText() }
    }

    // 5. computePredictions()
    //- Computes predictions using coefficients.
    //- Populates m85Predictions.
    private fun computePredictions() {
        for (p in Prayers.ORDER) {
            val coeffs = coeffsMap[p]
            if (coeffs != null && coeffs.isNotEmpty()) {
                appendLine("Computing M85 predictions for $p using coefficients...")
                val out = TreeMap<String, String>()
                val prayerSetMap = TreeMap<String, PrayerSet>() // NEW: Build PrayerSet objects

                for ((date, pset) in method12Map) {
                    val base = getPrayerTimeFromSet(pset, p)
                    if (base != null) {
                        val doy = dayOfYearFromDate(date, year)
                        val pred = DeltaModelCommon.predictFromCoeffs(coeffs, base, doy)
                        if (pred != null) {
                            out[date] = pred
                            // NEW: Create PrayerSet objects for method85Map
                            val currentSet = method85Map[date] ?: PrayerSet()
                            val updatedSet = when (p) {
                                "Fajr" -> currentSet.copy(fajr = pred)
                                "Dhuhr" -> currentSet.copy(dhuhr = pred)
                                "Asr" -> currentSet.copy(asr = pred)
                                "Maghrib" -> currentSet.copy(maghrib = pred)
                                "Isha" -> currentSet.copy(isha = pred)
                                else -> currentSet
                            }
                            method85Map[date] = updatedSet
                        }
                    }
                }
                m85Predictions[p] = out
                appendLine("Computed M85 for $p (${out.size} entries)")
            } else {
                m85Predictions[p] = emptyMap()
                appendLine("WARNING: No coefficients found for $p")
            }
        }

        // Debug: Check if method85Map was populated
        appendLine("method85Map size after computation: ${method85Map.size}")
    }


    // -------------------------------
    // Compute delta per prayer (method99 - method12) -> Map<String, Int>
    // -------------------------------
    private fun computeDeltaForPrayer(prayer: String): Map<String, Int> {
        val out = TreeMap<String, Int>()
        if (method12Map.isEmpty() || method99Map.isEmpty()) {
            appendLine("Cannot compute delta for $prayer: missing method12 or method99 data")
            return out
        }
        for ((date, set12) in method12Map) {
            val t12 = getPrayerTimeFromSet(set12, prayer)
            val t99 = getPrayerTimeFromSet(method99Map[date], prayer)
            if (t12 != null && t99 != null) {
                val m12 = TimeUtils.hmToMinutes(t12)
                val m99 = TimeUtils.hmToMinutes(t99)
                if (m12 != null && m99 != null) {
                    out[date] = (m99 - m12)
                }
            }
        }
        return out
    }

    private fun loadDeltaFile(file: File): Map<String, Int> {
        val out = TreeMap<String, Int>()
        try {
            val jo = JSONObject(file.readText())
            val keys = jo.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = jo.optInt(k)
            }
        } catch (e: Exception) {
            appendLine("Failed to parse ${file.name}: ${e.message}")
        }
        return out
    }

    // -------------------------------
    // NETWORK helpers
    // -------------------------------
    private fun fetchHtml(urlStr: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.requestMethod = "GET"
        val code = conn.responseCode
        return if (code == HttpURLConnection.HTTP_OK) {
            val out = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            out
        } else {
            conn.disconnect()
            throw RuntimeException("HTTP $code")
        }
    }

    private fun extractCalendar(html: String): String? {
        val p = Pattern.compile("var\\s+calendar\\s*=\\s*(\\[[\\s\\S]*?\\]);")
        val m = p.matcher(html)
        return if (m.find()) m.group(1) else null
    }

    private fun fetchMethod12ForYear(year: Int): String {
        val root = StringBuilder().append("{")
        for (month in 1..12) {
            appendLine("Fetching month $month/$year from Aladhan...")
            val maxRetries = 3
            var currentAttempt = 0
            var arr: org.json.JSONArray? = null
            while (currentAttempt < maxRetries && arr == null) {
                currentAttempt++
                if (currentAttempt > 1) {
                    appendLine("  Retrying month $month (att $currentAttempt/$maxRetries)...")
                    Thread.sleep(1500)
                }
                arr = fetchAladhanCalendar(month, year, 12)
            }
            if (arr == null || arr.length() == 0) {
                throw Exception("Failed to fetch valid data for month $month after $maxRetries attempts.")
            }
            root.append("\"$month\":").append(arr.toString())
            if (month < 12) root.append(",")
        }
        root.append("}")
        return root.toString()
    }

    private fun fetchAladhanCalendar(month: Int, year: Int, method: Int): org.json.JSONArray? {
        try {
            val urlStr = "https://api.aladhan.com/v1/calendar?latitude=48.854&longitude=2.465&method=$method&month=$month&year=$year"
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 12000
            conn.readTimeout = 12000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val resp = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val jo = JSONObject(resp)
                if (jo.optInt("code") == 200) {
                    val dataArray = jo.optJSONArray("data")
                    if (dataArray != null && dataArray.length() > 0) {
                        return dataArray
                    } else {
                        appendLine("API returned OK but 'data' empty for month $month")
                        return null
                    }
                }
            }
            conn.disconnect()
            appendLine("fetchAladhan non-OK response: $code")
            return null
        } catch (e: Exception) {
            appendLine("fetchAladhan error: ${e.message}")
            return null
        }
    }

    // -------------------------------
    // Parsers
    // -------------------------------
    private fun parseMethod12String(content: String): Map<String, PrayerSet> {
        return try {
            val root = JSONObject(content)
            val format = detectMethod12Format(root)

            when (format) {
                Method12Format.MONTH_KEYED -> parseMonthKeyedFormat(root)
                Method12Format.DATE_KEYED -> parseDateKeyedFormat(root)
            }
        } catch (e: Exception) {
            appendLine("parseMethod12 error: ${e.message}")
            emptyMap()
        }
    }

    private fun JSONObject.hasNumericKeys(): Boolean {
        val keys = this.keys()
        while (keys.hasNext()) {
            if (keys.next().matches(Regex("\\d+"))) {
                return true
            }
        }
        return false
    }

    private fun detectMethod12Format(root: JSONObject): Method12Format {
        return if (root.hasNumericKeys()) {
            Method12Format.MONTH_KEYED
        } else {
            Method12Format.DATE_KEYED
        }
    }

    private fun parseMonthKeyedFormat(root: JSONObject): Map<String, PrayerSet> {
        val result = mutableMapOf<String, PrayerSet>()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }

        val keys = root.keys().asSequence().toList()
        keys.forEach { key ->
            if (key.matches(Regex("\\d+"))) {
                val month = key.toIntOrNull() ?: return@forEach
                val monthArray = root.optJSONArray(key) ?: return@forEach
                parseMonthData(monthArray, month, dateFormat, year) { date, prayerSet ->
                    result[date] = prayerSet
                }
            }
        }

        return result
    }

    private fun parseMonthData(
        monthArray: org.json.JSONArray,
        month: Int,
        dateFormat: SimpleDateFormat,
        year: Int,
        onPrayerParsed: (String, PrayerSet) -> Unit
    ) {
        for (i in 0 until monthArray.length()) {
            try {
                val dayObj = monthArray.getJSONObject(i)
                val dateStr = parseMethod12Date(dayObj, month, i + 1, dateFormat, year)
                val timings = dayObj.optJSONObject("timings") ?: continue

                val prayerSet = parsePrayerTimesFromTimings(timings)
                onPrayerParsed(dateStr, prayerSet)
            } catch (e: Exception) {
                appendLine("Failed to parse day ${i + 1} in month $month: ${e.message}")
            }
        }
    }

    private fun parseMethod12Date(
        dayObj: JSONObject,
        month: Int,
        dayOfMonth: Int,
        dateFormat: SimpleDateFormat,
        year: Int
    ): String {
        return try {
            val ds = dayObj.getJSONObject("date").getJSONObject("gregorian").getString("date")
            normalizeDateString(ds)
        } catch (e: Exception) {
            // Fallback: construct date from components
            val cal = Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month - 1)
                set(Calendar.DAY_OF_MONTH, dayOfMonth)
            }
            dateFormat.format(cal.time)
        }
    }

    private fun parseDateKeyedFormat(root: JSONObject): Map<String, PrayerSet> {
        val result = mutableMapOf<String, PrayerSet>()

        root.keys().forEach { dateKey ->
            try {
                val prayerObj = root.optJSONObject(dateKey) ?: return@forEach
                val prayerSet = parsePrayerTimesFromObject(prayerObj)
                result[dateKey] = prayerSet
            } catch (e: Exception) {
                appendLine("Failed to parse date $dateKey: ${e.message}")
            }
        }

        return result
    }

    private fun parsePrayerTimesFromTimings(timings: JSONObject): PrayerSet {
        return PrayerSet(
            fajr = cleanTime(timings.optString("Fajr", null)),
            dhuhr = cleanTime(timings.optString("Dhuhr", null)),
            asr = cleanTime(timings.optString("Asr", null)),
            maghrib = cleanTime(timings.optString("Maghrib", null)),
            isha = cleanTime(timings.optString("Isha", null))
        )
    }

    private fun parsePrayerTimesFromObject(obj: JSONObject): PrayerSet {
        return PrayerSet(
            fajr = cleanTime(obj.optString("Fajr", null)),
            dhuhr = cleanTime(obj.optString("Dhuhr", null)),
            asr = cleanTime(obj.optString("Asr", null)),
            maghrib = cleanTime(obj.optString("Maghrib", null)),
            isha = cleanTime(obj.optString("Isha", null))
        )
    }

    private fun normalizeDateString(dateStr: String): String {
        val parts = dateStr.split("-")
        return if (parts.size == 3) {
            val dd = parts[0].padStart(2, '0')
            val mm = parts[1].padStart(2, '0')
            val yy = parts[2]
            "$yy-$mm-$dd"
        } else {
            dateStr
        }
    }
    //============== End function needed to Refactor parseMethod12String =====
    private fun parseMethod99String(raw: String, year: Int): Map<String, PrayerSet> {
        return try {
            val months = extractMonthsFromCalendar(raw)
            if (months.isEmpty()) {
                appendLine("No months found in method99 data")
                return emptyMap()
            }

            months.flatMapIndexed { monthIndex, monthText ->
                parseMonth99Data(monthText, monthIndex, year)
            }.toMap()
        } catch (e: Exception) {
            appendLine("parseMethod99 error: ${e.message}")
            emptyMap()
        }
    }

    private fun extractMonthsFromCalendar(raw: String): List<String> {
        return "\\{[\\s\\S]*?\\}".toRegex().findAll(raw).map { it.value }.toList()
    }

    private fun parseMonth99Data(monthText: String, monthIndex: Int, year: Int): List<Pair<String, PrayerSet>> {
        val days = extractDaysFromMonth(monthText)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }

        return days.mapNotNull { (dayStr, timeArray) ->
            try {
                val day = dayStr.toIntOrNull() ?: return@mapNotNull null
                val date = constructDate(year, monthIndex, day, dateFormat)
                val prayerSet = parsePrayerTimesFromTimeArray(timeArray)
                date to prayerSet
            } catch (e: Exception) {
                appendLine("Failed to parse day $dayStr in month $monthIndex: ${e.message}")
                null
            }
        }
    }

    private fun extractDaysFromMonth(monthText: String): List<Pair<String, String>> {
        val dayRegex = "\"(\\d+)\"\\s*:\\s*\\[(.*?)\\]".toRegex(RegexOption.DOT_MATCHES_ALL)
        return dayRegex.findAll(monthText).map { match ->
            val dayStr = match.groupValues[1]
            val timeArray = match.groupValues[2]
            dayStr to timeArray
        }.toList()
    }

    private fun constructDate(year: Int, monthIndex: Int, day: Int, dateFormat: SimpleDateFormat): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, monthIndex)
            set(Calendar.DAY_OF_MONTH, day)
        }
        return dateFormat.format(cal.time)
    }

    private fun parsePrayerTimesFromTimeArray(timeArray: String): PrayerSet {
        val timeRegex = "([01]\\d|2[0-3]):[0-5]\\d".toRegex()
        val times = timeRegex.findAll(timeArray).map { it.value }.toList()

        // Expecting: [Fajr, Sunrise, Dhuhr, Asr, Maghrib, Isha]
        return PrayerSet(
            fajr = times.getOrNull(0),
            dhuhr = times.getOrNull(2),  // Skip sunrise at index 1
            asr = times.getOrNull(3),
            maghrib = times.getOrNull(4),
            isha = times.getOrNull(5)
        )
    }
    //========== End Functions needed to Refator parseMethod99String ====
    private fun cleanTime(s: String?): String? {
        if (s == null) return null
        val m = Regex("([01]?\\d|2[0-3]):[0-5]\\d").find(s)
        return m?.value
    }

    // -------------------------------
    // Display
    // -------------------------------

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Log for debugging
        appendLine("Orientation changed to: ${if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) "Landscape" else "Portrait"}")

        // 🔹 CRITICAL FIX: Use SP units, not PX
        // Get the text size value in SP from resources
        val sizeSp = resources.getDimension(R.dimen.countdown_text_size) / resources.displayMetrics.scaledDensity

        // Set text size using SP units
        binding.bigCountdownView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)

        // Get margins
        val topMargin = resources.getDimensionPixelSize(R.dimen.countdown_margin_top)
        val bottomMargin = resources.getDimensionPixelSize(R.dimen.countdown_margin_bottom)

        // Apply margins
        (binding.bigCountdownView.layoutParams as ViewGroup.MarginLayoutParams).apply {
            this.topMargin = topMargin
            this.bottomMargin = bottomMargin
        }

        // Force layout update
        binding.bigCountdownView.requestLayout()

        // Also update the text to reflect orientation change
        updateCountdownForOrientation()
    }

    private fun updateCountdownForOrientation() {
        val orientation = resources.configuration.orientation

        if (gracePeriodTimer != null && currentGracePrayer != null) {
            // We're in grace period - show grace period text
            binding.bigCountdownView.text = if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                "🕌\n${currentGracePrayer} TIME"
            } else {
                "🕌 ${currentGracePrayer} TIME"
            }
        } else {
            // Normal countdown mode - get current countdown state
            val (nextPrayer, nextTimeStr) = getNextPrayerAndTime()
            if (nextTimeStr != null) {
                // Calculate and format
                val secondsRemaining = calculateSecondsRemaining(nextTimeStr) ?: 0
                updateCountdownWithSeconds(nextPrayer, secondsRemaining)
            }
        }
    }

    private fun isDateColumnForNextPrayer(
        dateKey: String,
        dates: List<Date>,
        nextPrayer: String,
        nextPrayerTime: String?
    ): Boolean {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        // Find which date actually contains the NEXT prayer (not just any matching time)
        val nextPrayerDate = findActualNextPrayerDate(nextPrayer, nextPrayerTime, dates)

        return dateKey == dateFmt.format(nextPrayerDate)
    }

    private fun findActualNextPrayerDate(nextPrayer: String, nextPrayerTime: String?, dates: List<Date>): Date {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val now = Calendar.getInstance()

        for (date in dates) {
            val dateStr = dateFmt.format(date)
            val prayerSet = method99Map[dateStr]
            val prayerTime = getPrayerTimeFromSet(prayerSet, nextPrayer)

            // This is the actual next prayer if its time is after now
            if (prayerTime == nextPrayerTime) {
                val prayerCal = Calendar.getInstance().apply {
                    time = date
                    val (hours, minutes) = prayerTime!!.split(":").map { it.toInt() }
                    set(Calendar.HOUR_OF_DAY, hours)
                    set(Calendar.MINUTE, minutes)
                }

                if (prayerCal.after(now)) {
                    return date  // Found the actual next prayer date!
                }
            }
        }

        // Fallback to first date if not found
        return dates.first()
    }


    private fun minutesBetween(time1: String, time2: String): Int {
        val (h1, m1) = time1.split(":").map { it.toInt() }
        val (h2, m2) = time2.split(":").map { it.toInt() }

        return (h1 * 60 + m1) - (h2 * 60 + m2)
    }


    // ----------------------------------------------------------------------
    // REFINED computeDtString()
    // ----------------------------------------------------------------------
    private fun computeDtString(v99: String, v85: String): String {
        if (v99 == "---" || v85 == "---") return "---"

        val t99 = TimeUtils.hmToMinutes(v99) ?: return "ERR"
        val t85 = TimeUtils.hmToMinutes(v85) ?: return "ERR"

        val diffSeconds = (t99 - t85) * 60
        val sign = if (diffSeconds >= 0) "+" else ""

        return if (abs(diffSeconds) < 120) {
            // Example: +23s, -45s
            "${sign}${diffSeconds}s"
        } else {
            // Example: +2min, -3min
            "${sign}${diffSeconds / 60}min"
        }
    }

    // ----------------------------------------------------------------------
    // REFINED colorForDtString()
    // ----------------------------------------------------------------------
    private fun colorForDtString(s: String): Int {
        if (s == "ERR" || s == "---") return Colors.RED

        // Normalize absolute difference into seconds
        val absSeconds: Int = when {
            s.endsWith("s") -> {
                abs(s.removeSuffix("s")
                    .replace("+", "")
                    .replace("-", "")
                    .toIntOrNull() ?: 0)
            }
            s.endsWith("min") -> {
                abs(s.removeSuffix("min")
                    .replace("+", "")
                    .replace("-", "")
                    .toIntOrNull() ?: 0) * 60
            }
            else -> 0
        }

        return when {
            absSeconds <= 60 -> Colors.GREEN
            absSeconds <= 120 -> Colors.ORANGE
            else -> Colors.RED
        }
    }

    private fun showLoadingPlaceholder() {
        binding.contentTable.removeAllViews()

        val headerRow = TableRow(this)
        headerRow.addView(createHeaderCell("⏳ Loading prayer times..."))
        binding.contentTable.addView(headerRow)

        val row = TableRow(this)
        row.addView(createDataCell("Please wait, computing schedules...", Colors.GREEN))
        binding.contentTable.addView(row)
    }

    private fun getPrayerTimeFromSet(set: PrayerSet?, prayer: String): String? {
        if (set == null) return null
        return when (prayer) {
            "Fajr" -> set.fajr
            "Dhuhr" -> set.dhuhr
            "Asr" -> set.asr
            "Maghrib" -> set.maghrib
            "Isha" -> set.isha
            else -> null
        }
    }

    // -------------------------------
    // Prediction model (common)
    // -------------------------------
    object DeltaModelCommon {
        fun predictFromCoeffs(coeffs: DoubleArray, method12HM: String, dayOfYear: Int): String? {
            val base = TimeUtils.hmToMinutes(method12HM) ?: return null
            val t = 2.0 * Math.PI * dayOfYear / 365.0
            var delta = if (coeffs.isNotEmpty()) coeffs[0] else 0.0
            var idx = 1
            for (k in 1..5) {
                val cosk = Math.cos(k * t)
                val sink = Math.sin(k * t)
                if (idx < coeffs.size) delta += coeffs[idx] * cosk
                if (idx + 1 < coeffs.size) delta += coeffs[idx + 1] * sink
                idx += 2
            }
            return TimeUtils.minutesToHM((base + delta).roundToInt())
        }
    }


    // -------------------------------
    // Misc & IO
    // -------------------------------
    private fun saveText(file: File, text: String) {
        try {
            file.writeText(text)
            appendLine("Saved: ${file.name}")
        } catch (e: Exception) {
            appendLine("Failed writing ${file.name}: ${e.message}")
        }
    }
    // Instance method for UI updates
    private fun appendLine(s: String) {
        runOnUiThread {
            appendLogEntry(s) // always store
            if (DEBUG_MODE) {
                val allLogsText = allLogs.values.joinToString("\n")
                binding.debugTextView.text = allLogsText
                binding.tableContainer.post { binding.contentScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (dataReady) {
            // Re-bind table/logs from buffer
            renderTableFromBuffer()
        } else {
            // Show placeholder until background thread finishes
            showLoadingPlaceholder()
        }

        //updateCountdownFrameHeight()

        // Always refresh logs if debug mode is active
        if (DEBUG_MODE) {
            val allLogsText = allLogs.values.joinToString("\n")
            binding.debugTextView.text = allLogsText
            binding.tableContainer.post { binding.contentScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }


    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        // Save data readiness
        outState.putBoolean("dataReady", dataReady)
        outState.putString("activePrayer", activePrayer)

        // Save current table state
        outState.putString("currentPrayer", activePrayer)
        outState.putBoolean("isAutoMode", isAutoMode)
        outState.putLong("manualModeEndTime", manualModeEndTime)

        // Save grace period state
        outState.putLong("graceEndTime", graceEndTime)
        outState.putString("currentGracePrayer", currentGracePrayer)

        // Save playback state
        outState.putString("currentPlayingPrayer", currentPlayingPrayer)
    }



    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)

        activePrayer = savedInstanceState.getString("activePrayer")
        dataReady = savedInstanceState.getBoolean("dataReady", false)
        manualModeActive = savedInstanceState.getBoolean("manualModeActive", false)
        manualModeEndTime = savedInstanceState.getLong("manualModeEndTime", 0L)

        // Recompute nextPrayer fresh
        val (nextPrayerName, _) = getNextPrayerAndTime()
        nextPrayer = nextPrayerName

        // 🔑 Ensure maps are reloaded here if needed
        //reloadPrayerMapsIfEmpty()

        if (dataReady && activePrayer != null) {
            showPrayer(activePrayer!!)   // rebuild table
            updateTabStyles()            // recolor tabs
        } else {
            showLoadingPlaceholder()
        }
        debugRotationState()
        //applyCountdownDimens()
    }

    private fun dayOfYearFromDate(s: String, year: Int): Int {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        val d = fmt.parse(s) ?: return 1
        val cal = Calendar.getInstance()
        cal.time = d
        return cal.get(Calendar.DAY_OF_YEAR)
    }

    private fun buildWindow(days: Int): List<Date> {
        val now = Calendar.getInstance()
        val list = mutableListOf<Date>()
        for (i in 0 until days) {
            val c = now.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, i)
            list.add(c.time)
        }
        return list
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    // Enhanced debug toggle with visual feedback
    @SuppressLint("ClickableViewAccessibility")
    private fun setupDebugToggle() {
        binding.toolbar.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // Start a 5-second timer when the user presses down
                    debugHandler.postDelayed(debugLongPressRunnable, 5000)
                    true // Consume the event
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    // Cancel the timer if the user lifts their finger before 5 seconds
                    debugHandler.removeCallbacks(debugLongPressRunnable)
                    true // Consume the event
                }
                else -> false
            }
        }
    }
    private fun enterDebugMode() {
        DEBUG_MODE = true
        debugTapCount = 0
        handler.removeCallbacks(resetDebugCounterTask)
        handler.removeCallbacksAndMessages(null)

        // Collect all log entries (newest last for natural reading order)
        val logText = allLogs.values.joinToString("\n").ifEmpty { "No logs yet" }

        runOnUiThread {
            // Show logs in the scrollable debugTextView instead of bigCountdownView
            binding.bigCountdownView.visibility = View.GONE

            binding.tableContainer.visibility = View.VISIBLE
            binding.debugTextView.visibility = View.VISIBLE
            binding.debugTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            binding.debugTextView.text = logText

            // Auto-scroll to bottom to show the most recent entries
            binding.tableContainer.post {
                binding.contentScroll.fullScroll(View.FOCUS_DOWN)
            }

            graceTimerView.visibility = View.GONE
            graceTimerView.text = ""

            binding.statusBar.setBackgroundColor(Color.RED)
            binding.statusBar.setTextColor(Color.WHITE)
            val rmse = annualRMSE[activePrayer] ?: 0.0
            binding.statusBar.text = "M85 RMSE: ${"%.2f".format(rmse)}min - Click any tab to exit"
        }

        appendLine("Debug mode: Click any prayer tab to exit")
    }

    private fun exitDebugMode() {
        DEBUG_MODE = false
        binding.bigCountdownView.text = ""
        binding.bigCountdownView.visibility = View.GONE
        graceTimerView.text = ""
        graceTimerView.visibility = View.GONE
        binding.statusBar.setBackgroundColor(Color.DKGRAY)
        binding.statusBar.setTextColor(Color.WHITE)
        binding.statusBar.text = ""
        activePrayer?.let { showPrayer(it) }
        startSimpleCountdown()
    }

    private fun testDimensLoading() {
        val orientation = resources.configuration.orientation
        val textSize = resources.getDimension(R.dimen.countdown_text_size)
        val marginTop = resources.getDimension(R.dimen.countdown_margin_top)

        appendLine("=== DIMENS TEST ===")
        appendLine("Orientation: ${if (orientation == Configuration.ORIENTATION_LANDSCAPE) "Landscape" else "Portrait"}")
        appendLine("Text size from XML: ${textSize}px")
        appendLine("Margin top from XML: ${marginTop}px")
        appendLine("Actual text size: ${binding.bigCountdownView.textSize}px")
    }

    //==============Stage 1 : 2. Prayer Time Calculation Functions ============
    // Updated getNextPrayerAndTime() - Respects grace period
    private fun getNextPrayerAndTime(): Pair<String, String?> {
        // First: check if we're in a grace period
        val currentPrayer = getCurrentPrayerAtTime()
        if (currentPrayer != null) {
            // During grace period: "next" prayer is the CURRENT one
            val todayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val todayStr = todayFmt.format(Date())
            val todayPrayers = method99Map[todayStr]
            val currentTime = getPrayerTimeFromSet(todayPrayers, currentPrayer)

            appendLine("DEBUG: In grace - getNextPrayerAndTime() returns current: $currentPrayer")
            return Pair(currentPrayer, currentTime)
        }

        // Not in grace: use original logic to find actual next prayer
        val now = Calendar.getInstance()
        val todayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val todayStr = todayFmt.format(now.time)

        val currentTimeMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        // Get today's prayer times
        val todayPrayers = method99Map[todayStr] ?: return Pair("Fajr", null)

        // Build list of (prayer, minute) for today
        val prayerTimes = listOf(
            "Fajr" to TimeUtils.hmToMinutes(getPrayerTimeFromSet(todayPrayers, "Fajr")),
            "Dhuhr" to TimeUtils.hmToMinutes(getPrayerTimeFromSet(todayPrayers, "Dhuhr")),
            "Asr" to TimeUtils.hmToMinutes(getPrayerTimeFromSet(todayPrayers, "Asr")),
            "Maghrib" to TimeUtils.hmToMinutes(getPrayerTimeFromSet(todayPrayers, "Maghrib")),
            "Isha" to TimeUtils.hmToMinutes(getPrayerTimeFromSet(todayPrayers, "Isha"))
        )

        // Find next prayer TODAY
        for ((prayer, timeMinutes) in prayerTimes) {
            if (timeMinutes != null && timeMinutes > currentTimeMinutes) {
                return Pair(prayer, TimeUtils.minutesToHM(timeMinutes))
            }
        }

        // If no prayer found today, return TOMORROW'S Fajr
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val tomorrowStr = todayFmt.format(tomorrow.time)
        val tomorrowPrayers = method99Map[tomorrowStr]
        val tomorrowFajr = getPrayerTimeFromSet(tomorrowPrayers, "Fajr")

        appendLine("DEBUG: No prayer today - returning tomorrow's Fajr")
        return Pair("Fajr", tomorrowFajr)
    }

//==============Stage 1 : 3.Add Highlighting Update Function ============
//------------------------------------------------------------------
// PARTIAL UPDATE VERSION OF updatePrayerHighlighting()
//------------------------------------------------------------------

    // -----------------------------
// Helpers used by the functions
// -----------------------------
    private fun todayDateString(offsetDays: Int = 0): String {
        val cal = Calendar.getInstance()
        if (offsetDays != 0) cal.add(Calendar.DAY_OF_YEAR, offsetDays)
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        return fmt.format(cal.time)
    }

    private fun hmToMinutesSafe(hm: String?): Int? {
        if (hm == null) return null
        return try {
            val parts = hm.trim().split(":")
            if (parts.size < 2) return null
            val hh = parts[0].toIntOrNull() ?: return null
            val mm = parts[1].toIntOrNull() ?: return null
            hh * 60 + mm
        } catch (e: Exception) {
            null
        }
    }

    // Try to get prayer time for given date string, using method99 -> method12 -> method85 fallbacks
    private fun getBestPrayerTime(prayer: String, dateStr: String): String? {
        // method99 preferred
        method99Map[dateStr]?.let { ps ->
            getPrayerTimeFromSet(ps, prayer)?.let { return it }
        }
        // method12 next
        method12Map[dateStr]?.let { ps ->
            getPrayerTimeFromSet(ps, prayer)?.let { return it }
        }
        // method85 last
        method85Map[dateStr]?.let { ps ->
            getPrayerTimeFromSet(ps, prayer)?.let { return it }
        }
        return null
    }

// -----------------------------
// getCurrentPrayerName()
// -----------------------------
    /**
     * Returns the name of the current prayer (e.g. "Fajr", "Dhuhr", ...)
     * based on available prayer times (method99 preferred, then method12, then method85).
     * Logic:
     *  - Finds today's prayer times and converts to minutes-since-midnight.
     *  - The "current" prayer is the latest prayer whose time <= now.
     *  - If now is before the first prayer (Fajr) of today, the current prayer is yesterday's Isha (if available).
     *  - If no reliable data found, returns null.
     */

    // Enhanced getCurrentPrayerAtTime() - Single Source of Truth
    private fun getCurrentPrayerAtTime(): String? {
        val now = Calendar.getInstance()
        val currentTime = now.timeInMillis

        // First check: Are we in an active grace period?
        if (currentGracePrayer != null && currentTime < graceEndTime) {
            val remaining = (graceEndTime - currentTime) / 1000
            appendLine("DEBUG: In active grace period for $currentGracePrayer ($remaining seconds left)")
            return currentGracePrayer
        }

        // If grace period expired, clear it
        if (currentGracePrayer != null && currentTime >= graceEndTime) {
            appendLine("DEBUG: Grace period expired for $currentGracePrayer")
            currentGracePrayer = null
            graceEndTime = 0L
        }

        // Check if any prayer time is happening NOW (start of prayer time)
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val todayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val todayStr = todayFmt.format(now.time)

        val todayPrayers = method99Map[todayStr] ?: return null

        // Check each prayer: is it exactly at its scheduled time (±30 seconds)?
        for (prayer in Prayers.ORDER) {
            val prayerTime = getPrayerTimeFromSet(todayPrayers, prayer)
            if (prayerTime != null) {
                val prayerMinutes = TimeUtils.hmToMinutes(prayerTime) ?: continue

                // Check if current time is within 30 seconds of prayer time
                val timeDiff = abs(currentMinutes - prayerMinutes)
                val isAtPrayerTime = timeDiff == 0  // Exact minute match

                if (isAtPrayerTime) {
                    appendLine("DEBUG: $prayer time detected exactly at minute $currentMinutes")
                    return prayer
                }
            }
        }

        // Check yesterday's Isha for early morning
        if (currentMinutes < 6 * 60) {
            val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            val yesterdayStr = todayFmt.format(yesterday.time)
            val yesterdayPrayers = method99Map[yesterdayStr]

            if (yesterdayPrayers != null) {
                val ishaTime = getPrayerTimeFromSet(yesterdayPrayers, "Isha")
                if (ishaTime != null) {
                    val ishaMinutes = TimeUtils.hmToMinutes(ishaTime) ?: return null
                    val adjustedCurrent = currentMinutes + (24 * 60)
                    val timeDiff = abs(adjustedCurrent - ishaMinutes)
                    val isAtPrayerTime = timeDiff == 0

                    if (isAtPrayerTime) {
                        appendLine("DEBUG: Yesterday's Isha time detected")
                        return "Isha"
                    }
                }
            }
        }

        return null
    }

// -----------------------------
// getNextPrayerName(current)
// -----------------------------
    /**
     * Returns the next prayer name after `current`, computed relative to "now".
     * If `current` is null, tries to find the next upcoming prayer for today (time > now).
     * Wraps to next day's Fajr if no further prayer today.
     */

    //============== Add RMSE Calculation Function ============
    private val annualRMSE = mutableMapOf<String, Double>() // prayer -> RMSE in minutes

    private fun computeAnnualRMSE() {
        for (prayer in Prayers.ORDER) {
            annualRMSE[prayer] = calculatePrayerRMSE(prayer)
        }
    }

    private fun calculatePrayerRMSE(prayer: String): Double {
        var sumSquaredErrors = 0.0
        var count = 0

        for ((date, m99Set) in method99Map) {
            val m99Time = getPrayerTimeFromSet(m99Set, prayer)
            val m85Time = m85Predictions[prayer]?.get(date)

            if (m99Time != null && m85Time != null) {
                val errorMinutes = minutesBetween(m85Time, m99Time).toDouble()
                sumSquaredErrors += errorMinutes * errorMinutes
                count++
            }
        }

        return if (count > 0) sqrt(sumSquaredErrors / count) else 0.0
    }

    // ====== function to replace your 30-second timer for better clock synchronization ======


    private fun updatePrayerTimeDisplay() {
        val (nextPrayer, nextTimeStr) = getNextPrayerAndTime()

        if (nextTimeStr != null) {
            val now = Calendar.getInstance()
            val currentTotalSeconds = now.get(Calendar.HOUR_OF_DAY) * 3600 +
                    now.get(Calendar.MINUTE) * 60 +
                    now.get(Calendar.SECOND)

            val prayerMinutes = TimeUtils.hmToMinutes(nextTimeStr) ?: 0
            val prayerTotalSeconds = prayerMinutes * 60

            var secondsRemaining = prayerTotalSeconds - currentTotalSeconds
            if (secondsRemaining < 0) secondsRemaining += 24 * 3600

            updateCountdownWithSeconds(nextPrayer, secondsRemaining)
        }
    }

    private var lastDateStr: String? = null

    private fun startAdaptiveSynchronizedCountdown() {
        // Cancel any existing timer
        nextPrayerTimer?.let { handler.removeCallbacks(it) }

        nextPrayerTimer = object : Runnable {
            override fun run() {
                val now = Calendar.getInstance()

                // 1. DETECT DATE CHANGE AT MIDNIGHT
                val hour = now.get(Calendar.HOUR_OF_DAY)
                val minute = now.get(Calendar.MINUTE)

                if (hour == 0 && minute == 0) {
                    val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    val todayStr = dateFmt.format(now.time)

                    if (todayStr != lastDateStr) {
                        lastDateStr = todayStr
                        appendLine("DATE CHANGE DETECTED: $todayStr")

                        val dates = buildWindow(3)

                        if (method99Map.isNotEmpty() && method12Map.isNotEmpty()) {
                            runOnUiThread {
                                buildPrayerTable("Fajr", dates)
                                appendLine("Midnight refresh: Fajr table rebuilt, rows=${binding.contentTable.childCount}")
                            }
                        } else {
                            appendLine("Midnight refresh skipped: data not ready")
                        }
                    }
                }

                // 2. Always update countdown display
                updatePrayerTimeDisplay()

                // 3. CALCULATE NEXT UPDATE TIME
                val currentMillisecond = now.get(Calendar.MILLISECOND)
                val currentSecond = now.get(Calendar.SECOND)

                val (_, nextTimeStr) = getNextPrayerAndTime()
                val secondsRemaining = calculateSecondsRemaining(nextTimeStr)
                val isUrgent = secondsRemaining != null && secondsRemaining < 600  // <10 min

                val delay = if (isUrgent) {
                    (1000 - currentMillisecond).toLong() // second‑aligned
                } else {
                    (60 - currentSecond) * 1000L         // minute‑aligned
                }

                handler.postDelayed(this, delay)
            }
        }

        // Start immediately
        handler.post(nextPrayerTimer!!)
        updatePrayerTimeDisplay() // force initial draw
    }

    private fun calculateSecondsRemaining(nextTimeStr: String?): Int? {
        if (nextTimeStr == null) return null

        val now = Calendar.getInstance()
        val currentTotalSeconds = now.get(Calendar.HOUR_OF_DAY) * 3600 +
                now.get(Calendar.MINUTE) * 60 +
                now.get(Calendar.SECOND)

        val prayerMinutes = TimeUtils.hmToMinutes(nextTimeStr) ?: return null
        val prayerTotalSeconds = prayerMinutes * 60

        var secondsRemaining = prayerTotalSeconds - currentTotalSeconds
        if (secondsRemaining < 0) secondsRemaining += 24 * 3600

        return secondsRemaining
    }
    // 2. Grace Timer Synchronization: done inline in startGracePeriod() in this block: "gracePeriodTimer = object : Runnable {"
    // 3. Auto-checker Synchronization
    private fun startSynchronizedAutoChecker() {
        val checker = object : Runnable {
            override fun run() {
                try {
                    // Run your auto checker logic
                    autoModeChecker.run()

                    // Recalculate delay to next :00 or :30 boundary
                    val now = Calendar.getInstance()
                    val currentSecond = now.get(Calendar.SECOND)
                    val currentMillisecond = now.get(Calendar.MILLISECOND)

                    // How many seconds until the next boundary?
                    val secondsUntilBoundary = if (currentSecond < 30) {
                        30 - currentSecond
                    } else {
                        60 - currentSecond
                    }

                    // Adjust for milliseconds to hit exact boundary
                    val millisUntilBoundary = secondsUntilBoundary * 1000L - currentMillisecond

                    // Schedule next run exactly at boundary
                    handler.postDelayed(this, millisUntilBoundary)
                } catch (e: Exception) {
                    // In case of error, retry after 30s
                    handler.postDelayed(this, 30000)
                }
            }
        }

        // Kick off immediately with alignment
        checker.run()
    }

    // ==================== SIMPLE COUNTDOWN ====================

    private fun startSimpleCountdown() {
        // Cancel any existing timer
        nextPrayerTimer?.let { handler.removeCallbacks(it) }

        nextPrayerTimer = object : Runnable {
            override fun run() {
                try {
                    // Don't run if we're in grace period
                    if (gracePeriodTimer != null) {
                        handler.postDelayed(this, 1000)
                        return
                    }

                    val (nextPrayer, nextTimeStr) = getNextPrayerAndTime()

                    if (nextTimeStr != null) {
                        // Calculate seconds remaining
                        val now = Calendar.getInstance()
                        val currentHour = now.get(Calendar.HOUR_OF_DAY)
                        val currentMinute = now.get(Calendar.MINUTE)
                        val currentSecond = now.get(Calendar.SECOND)
                        val currentTotalSeconds = currentHour * 3600 + currentMinute * 60 + currentSecond

                        val prayerMinutes = TimeUtils.hmToMinutes(nextTimeStr) ?: 0
                        val prayerTotalSeconds = prayerMinutes * 60

                        var secondsRemaining = prayerTotalSeconds - currentTotalSeconds
                        if (secondsRemaining < 0) secondsRemaining += 24 * 3600

                        // Update the countdown display
                        updateCountdownWithSeconds(nextPrayer, secondsRemaining)

                        // Determine update frequency based on time remaining
                        val updateInterval = if (secondsRemaining / 60 < 10) {
                            1000L  // Update every second when <10 minutes
                        } else {
                            60000L // Update every minute when ≥10 minutes
                        }

                        handler.postDelayed(this, updateInterval)

                    } else {
                        hideBigCountdown()
                        handler.postDelayed(this, 60000)
                    }

                } catch (e: Exception) {
                    appendLine("Countdown error: ${e.message}")
                    handler.postDelayed(this, 30000)
                }
            }
        }

        // Start immediately
        handler.post(nextPrayerTimer!!)
    }

// ==================== BASIC COUNTDOWN VIEW ====================

    private fun setupCountdownArea() {
        // Configure bigCountdownView
        binding.bigCountdownView.apply {
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.YELLOW)
            visibility = View.VISIBLE
            setPadding(0, 2.dpToPx(), 0, 2.dpToPx())

        }

        // Configure graceTimerView
        binding.graceTimerView.apply {
            textSize = 48f
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.RED)
            setBackgroundColor(Color.parseColor("#30000000"))
            visibility = View.GONE
            setPadding(0, 10.dpToPx(), 0, 10.dpToPx())
        }
    }

    private fun showBigCountdown(seconds: Int, prayer: String) {
        runOnUiThread {

            val minutes = seconds / 60
            val remainingSeconds = seconds % 60

            // Format test display with seconds when appropriate
            val displayText = if (minutes < 10 && seconds >= 60) {
                "${minutes}m ${remainingSeconds}s\nto Test"
            } else if (seconds >= 60) {
                "${minutes}m\nto Test"
            } else {
                "${seconds}s\nto Test"
            }

            binding.bigCountdownView.text = displayText
            binding.bigCountdownView.setTextColor(getGracePeriodColor(seconds, prayer))
            binding.bigCountdownView.visibility = View.VISIBLE
        }
    }

    private fun hideBigCountdown() {
        runOnUiThread {
            binding.bigCountdownView.text = ""
        }
    }

    private fun getGracePeriodColor(seconds: Int, prayer: String): Int {
        val minutes = seconds / 60
        return when {
            minutes > 30 -> Color.YELLOW
            minutes > 10 -> Color.parseColor("#FFA500") // Orange
            minutes > 1 -> Color.RED
            else -> Color.RED  // Last minute - urgent
        }
    }

    // Modify updateCountdownWithSeconds() to handle the 0 seconds case
    private fun updateCountdownWithSeconds(prayer: String, totalSecondsRemaining: Int) {
        runOnUiThread {
            // Don't update if we're in grace period
            if (gracePeriodTimer != null) {
                return@runOnUiThread
            }

            val minutes = totalSecondsRemaining / 60
            val seconds = totalSecondsRemaining % 60

            val (timeLine, prayerLine) = when {
                totalSecondsRemaining <= 0 -> {
                    "🕌 $prayer" to "TIME!!!"
                }
                minutes >= 60 -> {
                    val hours = minutes / 60
                    val remainingMins = minutes % 60
                    val hourText = if (remainingMins > 0) "${hours}h ${remainingMins}m" else "${hours}h"
                    hourText to "to $prayer"
                }
                minutes >= 10 -> {
                    "${minutes}m" to "to $prayer"
                }
                else -> {
                    "${minutes}m ${seconds}s" to "to $prayer"
                }
            }

            // Let XML handle the formatting - just set the text
            val orientation = resources.configuration.orientation

            // Set text based on orientation
            binding.bigCountdownView.text = if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                "$timeLine\n$prayerLine"
            } else {
                "$timeLine $prayerLine"
            }

            // 🔹 Color coding
            val color = when {
                minutes > 60 -> Color.GREEN
                minutes > 30 -> Color.YELLOW
                minutes > 10 -> Color.parseColor("#FFA500")
                else -> Color.RED
            }
            binding.bigCountdownView.setTextColor(color)
            binding.bigCountdownView.visibility = View.VISIBLE
        }
    }

    private fun updateCountdownText(prayer: String, orientation: Int) {
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            binding.bigCountdownView.isSingleLine = false
            binding.bigCountdownView.text = "🕌\n$prayer TIME"
        } else {
            binding.bigCountdownView.isSingleLine = true
            binding.bigCountdownView.text = "🕌 $prayer TIME"
        }

        // enforce alignment every time
        binding.bigCountdownView.textAlignment = View.TEXT_ALIGNMENT_CENTER
        binding.bigCountdownView.gravity = Gravity.CENTER
    }

    // StatusBar Priority Management helpers
    private fun updateStatusBarWithPriority(message: String, priority: Int) {
        runOnUiThread {
            if (priority >= currentStatusBarPriority) {
                binding.statusBar.text = message
                currentStatusBarPriority = priority
            }
        }
    }

    private fun clearStatusBarIfLowerPriority(priority: Int) {
        runOnUiThread {
            if (priority >= currentStatusBarPriority) {
                binding.statusBar.text = ""
                currentStatusBarPriority = priority
            }
        }
    }

    private fun getActualNextPrayer(currentPrayer: String): String {
        // IGNORE grace period - get true next prayer in sequence
        val order = Prayers.ORDER
        val currentIndex = order.indexOf(currentPrayer)

        return if (currentIndex >= 0 && currentIndex < order.size - 1) {
            order[currentIndex + 1]
        } else {
            order.first()  // Wrap to Fajr
        }
    }

    //================ Implement Grace Settings Dialog ======
    private fun showGraceSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_grace_settings, null)

        // Get current values
        val prefs = getSharedPreferences("grace_settings", MODE_PRIVATE)

        // Setup spinners
        val prayerSpinners = mapOf(
            R.id.spinner_fajr to "fajr_grace",
            R.id.spinner_dhuhr to "dhuhr_grace",
            R.id.spinner_asr to "asr_grace",
            R.id.spinner_maghrib to "maghrib_grace",
            R.id.spinner_isha to "isha_grace"
        )

        val minuteOptions = arrayOf("1", "3", "5", "10", "15", "20", "30")

        prayerSpinners.forEach { (spinnerId, prefKey) ->
            val spinner = dialogView.findViewById<Spinner>(spinnerId)
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, minuteOptions)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter

            // Set current selection
            val currentValue = prefs.getInt(prefKey, getDefaultGrace(prefKey)).toString()
            val position = minuteOptions.indexOf(currentValue)
            if (position >= 0) spinner.setSelection(position)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Grace Period Settings (minutes)")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                saveGraceSettings(dialogView, prayerSpinners)
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Default") { _, _ ->
                resetGraceToDefault()
            }
            .create()

        dialog.show()
    }

    private fun getDefaultGrace(prefKey: String): Int {
        return when (prefKey) {
            "fajr_grace" -> 5
            "dhuhr_grace" -> 10
            "asr_grace" -> 10
            "maghrib_grace" -> 3
            "isha_grace" -> 10
            else -> 5
        }
    }


    //=== save load functions =====
    private fun saveGraceSettings(dialogView: View, prayerSpinners: Map<Int, String>) {
        val prefs = getSharedPreferences("grace_settings", MODE_PRIVATE)
        val editor = prefs.edit()

        prayerSpinners.forEach { (spinnerId, prefKey) ->
            val spinner = dialogView.findViewById<Spinner>(spinnerId)
            val selectedValue = spinner.selectedItem.toString().toIntOrNull() ?: getDefaultGrace(prefKey)
            editor.putInt(prefKey, selectedValue)

            appendLine("Saved $prefKey = $selectedValue minutes")
        }

        editor.apply()

        // Update current config
        // No need to call anything - next getGracePeriodConfig() will get fresh values
        appendLine("Settings saved - new config: ${getGracePeriodConfig()}")

        // Show confirmation
        Toast.makeText(this, "Grace periods saved", Toast.LENGTH_SHORT).show()
    }

    private fun resetGraceToDefault() {
        val prefs = getSharedPreferences("grace_settings", MODE_PRIVATE)
        val editor = prefs.edit()

        editor.putInt("fajr_grace", 5)
        editor.putInt("dhuhr_grace", 10)
        editor.putInt("asr_grace", 10)
        editor.putInt("maghrib_grace", 3)
        editor.putInt("isha_grace", 10)

        editor.apply()
        // Config is now self-updating
        appendLine("Reset to defaults: ${getGracePeriodConfig()}")

        Toast.makeText(this, "Reset to default values", Toast.LENGTH_SHORT).show()
        appendLine("Grace periods reset to defaults")
    }

    private fun ensureDefaultGraceSettings() {
        val prefs = getSharedPreferences("grace_settings", MODE_PRIVATE)

        // Define defaults once
        val defaults = mapOf(
            "fajr_grace" to 5,
            "dhuhr_grace" to 10,
            "asr_grace" to 10,
            "maghrib_grace" to 3,
            "isha_grace" to 10
        )

        // Check if any default is missing
        val missingDefaults = defaults.any { (key, _) -> !prefs.contains(key) }

        if (missingDefaults) {
            prefs.edit().apply {
                defaults.forEach { (key, value) ->
                    putInt(key, value)
                }
            }.apply()
            appendLine("Initialized default grace settings: $defaults")
        }
    }

// ============================================
// NOTIFICATION SYSTEM (Self-contained section)
// ============================================

    // --- 1. Nested BroadcastReceiver ---
    // --- 1. Nested BroadcastReceiver ---
    class PrayerAlarmReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val prayerName = intent.getStringExtra("PRAYER_NAME")
            if (prayerName.isNullOrBlank()) {
                Log.e("PrayerAlarmReceiver", "Missing PRAYER_NAME extra; cannot start service.")
                return
            }

            // --- CRITICAL CHECK ---
            // Read the saved preference for this specific prayer.
            val prefs = context.getSharedPreferences("prayer_alarms", Context.MODE_PRIVATE)
            val isAlarmEnabled = prefs.getBoolean(prayerName, true)
            if (!isAlarmEnabled) {
                Log.d("PrayerAlarmReceiver", "Alarm muted for $prayerName. Skipping playback.")
                return
            }

            val serviceIntent = Intent(context, PrayerNotificationService::class.java).apply {
                putExtra("prayer", prayerName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }



    // --- 2. Nested Foreground Service ---
    // 🔹 Nested Foreground Service
    // In PrayerTimeCompare.kt
    class PrayerNotificationService : Service() {
        private var mediaPlayer: MediaPlayer? = null

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            if (intent?.action == "STOP_AZAN") {
                val prayer = intent.getStringExtra("prayer")
                try {
                    mediaPlayer?.stop()
                } catch (_: IllegalStateException) {
                    MainActivity.appendLogEntry("MediaPlayer.stop() called in wrong state")
                }
                mediaPlayer?.release()
                mediaPlayer = null

                if (!prayer.isNullOrBlank()) {
                    sendPlaybackBroadcast(ACTION_PLAYBACK_STOPPED, prayer)
                }
                @Suppress("DEPRECATION")
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }

            val prayer = intent?.getStringExtra("prayer")
            if (prayer.isNullOrBlank()) {
                Log.e("PrayerNotificationService", "Missing prayer extra; aborting playback.")
                stopSelf()
                return START_NOT_STICKY
            }

            startForeground(1001, createNotification(prayer))
            playAzan(prayer)
            sendPlaybackBroadcast(ACTION_PLAYBACK_STARTED, prayer) // Added call
            return START_STICKY
        }

        private fun playAzan(prayer: String) {
            try {
                val audioRes = if (prayer == "Fajr") R.raw.azan_fajr else R.raw.azan_standard
                mediaPlayer = MediaPlayer.create(this, audioRes)

                if (mediaPlayer == null) {
                    Log.e("PrayerNotificationService", "MediaPlayer.create returned null for $prayer")
                    sendPlaybackBroadcast(ACTION_PLAYBACK_STOPPED, prayer)
                    stopSelf()
                    return
                }

                mediaPlayer?.apply {
                    setOnCompletionListener {
                        sendPlaybackBroadcast(ACTION_PLAYBACK_STOPPED, prayer)
                        MainActivity.appendLogEntry("Azan finished for $prayer")
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                        stopSelf()
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e("PrayerNotificationService", "MediaPlayer error: what=$what, extra=$extra")
                        MainActivity.appendLogEntry("Azan error: what=$what extra=$extra")
                        sendPlaybackBroadcast(ACTION_PLAYBACK_STOPPED, prayer)
                        stopSelf()
                        true
                    }
                    start()
                    MainActivity.appendLogEntry("Azan started for $prayer")
                }
            } catch (e: Exception) {
                Log.e("PrayerNotificationService", "Error playing azan for $prayer", e)
                MainActivity.appendLogEntry("Error playing azan: ${e.message}")
                sendPlaybackBroadcast(ACTION_PLAYBACK_STOPPED, prayer)
                stopSelf()
            }
        }

        // This function was removed in your code, re-add it.
        private fun broadcastPlaybackState(action: String, prayer: String) {
            val intent = Intent(action).apply {
                setPackage(packageName)
                putExtra("prayer", prayer)
            }
            sendBroadcast(intent)
        }

        private fun createNotification(prayer: String): Notification {
            val stopIntent = Intent(this, MainActivity.PrayerNotificationService::class.java).apply {
                action = "STOP_AZAN"
                putExtra("prayer", prayer)
            }
            val stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Heads-up reliability: fullScreenIntent (alarm-style peek)
            val fsIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("from_azan", true)
                putExtra("prayer", prayer)
            }
            val fsPendingIntent = PendingIntent.getActivity(
                this,
                2,
                fsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            return NotificationCompat.Builder(this, "prayer_channel")
                .setContentTitle("Azan: $prayer")
                .setContentText("Playing now")
                .setSmallIcon(R.drawable.ic_speaker_on)
                .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)

                // Foreground persistence during playback
                .setOngoing(true)
                .setAutoCancel(false)

                // Heads-up knobs (pre-O uses priority; O+ uses channel importance)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)

                // Robust: forces heads-up style presentation on many OEMs
                .setFullScreenIntent(fsPendingIntent, true)

                // Optional: reduce “silent collapse” behavior
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
        }

        override fun onBind(intent: Intent?): IBinder? = null

        // Add this missing function
        private fun sendPlaybackBroadcast(action: String, prayer: String) {
            val intent = Intent(action).apply {
                setPackage(packageName) // Make intent explicit to fix lint warning
                putExtra("prayer", prayer)
            }
            sendBroadcast(intent)
        }
    }

    // 3. Simple scheduling functions (add to existing MainActivity)
    private fun scheduleNextPrayerNotification() {
        val (nextPrayer, nextTime) = getNextPrayerAndTime()
        if (nextTime == null) {
            appendLine("Cannot schedule notification: no prayer time")
            return
        }

        val alarmTime = calculateAlarmTime(nextTime)

        val intent = Intent(this, PrayerAlarmReceiver::class.java).apply {
            putExtra("PRAYER_NAME", nextPrayer)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            nextPrayer.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

        try {
            // Check permission first (Android 12+)
            val canSchedule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.canScheduleExactAlarms()
            } else {
                true // No restriction before Android 12
            }

            if (canSchedule) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    alarmTime,
                    pendingIntent
                )
                appendLine("Scheduled $nextPrayer notification for $nextTime")
            } else {
                appendLine("Cannot schedule exact alarm - permission needed")
                showAlarmPermissionWarning()
                scheduleInexactAlarmAsFallback(alarmManager, alarmTime, pendingIntent)
            }

        } catch (securityException: SecurityException) {
            appendLine("SecurityException: ${securityException.message}")
            showAlarmPermissionWarning()

            // Fallback to inexact alarm
            scheduleInexactAlarmAsFallback(alarmManager, alarmTime, pendingIntent)

        } catch (e: Exception) {
            appendLine("Error scheduling alarm: ${e.message}")
        }
    }

    private fun scheduleInexactAlarmAsFallback(
        alarmManager: AlarmManager,
        alarmTime: Long,
        pendingIntent: PendingIntent
    ) {
        try {
            // Fallback to set() which is less precise but doesn't require permission
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                alarmTime,
                pendingIntent
            )
            appendLine("Scheduled inexact alarm as fallback")

        } catch (e: Exception) {
            appendLine("Failed to schedule inexact alarm: ${e.message}")
        }
    }

    private fun showAlarmPermissionWarning() {
        runOnUiThread {
            // Show in status bar
            binding.statusBar.text = "⚠️ Enable exact alarm permission for precise notifications"
            binding.statusBar.setTextColor(Color.YELLOW)

            // Make it clickable to open settings
            binding.statusBar.setOnClickListener {
                openAlarmPermissionSettings()
            }

            // Clear after 8 seconds
            handler.postDelayed({
                binding.statusBar.text = ""
                binding.statusBar.setTextColor(Colors.TITLE_TEXT)
                binding.statusBar.setOnClickListener(null)
            }, 8000)
        }
    }

    private fun openAlarmPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Exception) {
                // Fallback to app settings
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = "package:$packageName".toUri()
                startActivity(intent)
            }
        }
    }

    //Permission Check Helper
    private fun checkAndRequestAlarmPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val hasPermission = alarmManager.canScheduleExactAlarms()

            if (!hasPermission) {
                appendLine("Exact alarm permission not granted")

                // Show educational dialog (optional)
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Exact Alarm Permission Needed")
                        .setMessage("For accurate prayer time notifications, please grant the 'Schedule exact alarms' permission.")
                        .setPositiveButton("Open Settings") { _, _ ->
                            openAlarmPermissionSettings()
                        }
                        .setNegativeButton("Later", null)
                        .show()
                }
            }

            hasPermission
        } else {
            true // No permission needed before Android 12
        }
    }

    private fun calculateAlarmTime(prayerTimeStr: String): Long {
        val calendar = Calendar.getInstance()
        val (hours, minutes) = prayerTimeStr.split(":").map { it.toInt() }

        calendar.set(Calendar.HOUR_OF_DAY, hours)
        calendar.set(Calendar.MINUTE, minutes)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        return calendar.timeInMillis
    }




}