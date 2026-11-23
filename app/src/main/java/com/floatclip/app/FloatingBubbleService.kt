package com.floatclip.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.floatclip.app.data.ClipboardSheetEntity
import com.floatclip.app.data.FloatClipDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

class FloatingBubbleService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val quickCopyMutex = Mutex()
    private val clipboardSaveMutex = Mutex()

    private lateinit var database: FloatClipDatabase
    private lateinit var windowManager: WindowManager
    private lateinit var windowParams: WindowManager.LayoutParams
    private var rootView: LinearLayout? = null
    private var actionPanel: LinearLayout? = null
    private var bubbleView: TextView? = null
    private var actionButtons: List<TextView> = emptyList()

    private val preferences by lazy {
        getSharedPreferences("floatclip_bubble", Context.MODE_PRIVATE)
    }

    private var settingsReceiverRegistered = false
    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_APPLY_SETTINGS) applyBubbleAppearance()
        }
    }

    override fun onCreate() {
        super.onCreate()
        database = FloatClipDatabase.get(this)
        val filter = IntentFilter(ACTION_APPLY_SETTINGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(settingsReceiver, filter)
        }
        settingsReceiverRegistered = true
        startAsForeground()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            ClipboardTools.toast(this, "Allow Display over other apps to use the bubble.")
            stopSelf()
            return
        }
        addBubbleView()
        observeBalance()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (rootView == null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this))) {
            addBubbleView()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "FloatClip floating bubble",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps the user-started FloatClip bubble available."
                }
            )
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("FloatClip is active")
            .setContentText("Floating clipboard controls are ready.")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun addBubbleView() {
        if (rootView != null) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val initialBubbleSize = bubbleSizeDp()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding(0, dp(4), 0, 0)
            background = null
            elevation = 0f
        }
        actionPanel = panel
        actionButtons = createActionButtons()
        actionButtons.forEach(panel::addView)

        val bubble = TextView(this).apply {
            text = "৳0"
            textSize = (initialBubbleSize * 0.24f).coerceIn(10f, 17f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = bubbleBackground()
            elevation = dp(8).toFloat()
            contentDescription = "FloatClip floating menu"
        }

        root.addView(
            bubble,
            LinearLayout.LayoutParams(dp(initialBubbleSize), dp(initialBubbleSize)).apply {
                topMargin = dp(6)
            }
        )
        val panelScroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // Keep the expanded action list scrollable instead of letting A–E
                // buttons extend beyond the bottom of the screen.
                val availableHeight = (
                    resources.displayMetrics.heightPixels -
                        windowParams.y -
                        dp(initialBubbleSize) -
                        dp(24)
                    ).coerceAtLeast(dp(120))
                val limitedHeightSpec = MeasureSpec.makeMeasureSpec(
                    availableHeight,
                    MeasureSpec.AT_MOST
                )
                super.onMeasure(widthMeasureSpec, limitedHeightSpec)
            }
        }.apply {
            isFillViewport = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                panel,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        root.addView(
            panelScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        windowParams = WindowManager.LayoutParams(
            dp(initialBubbleSize + ROOT_HORIZONTAL_PADDING_DP),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(180)
        }

        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var startTouchX = 0f
            private var startTouchY = 0f
            private var moved = false

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = windowParams.x
                        startY = windowParams.y
                        startTouchX = event.rawX
                        startTouchY = event.rawY
                        moved = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - startTouchX
                        val dy = event.rawY - startTouchY
                        if (kotlin.math.abs(dx) > dp(5) || kotlin.math.abs(dy) > dp(5)) {
                            moved = true
                        }
                        if (moved) {
                            windowParams.x = startX + dx.toInt()
                            windowParams.y = startY + dy.toInt()
                            try {
                                windowManager.updateViewLayout(root, windowParams)
                            } catch (_: IllegalArgumentException) {
                                // The overlay may be removed while the user is dragging it.
                            }
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            val opening = panel.visibility != View.VISIBLE
                            panel.visibility = if (opening) View.VISIBLE else View.GONE
                            if (opening) updateClipboardButtonLabels()

                            // WRAP_CONTENT can keep the old narrow overlay width after a
                            // GONE panel becomes visible on some Android launchers.
                            // Set the overlay width explicitly so every action button is visible.
                            windowParams.width = dp(bubbleSizeDp() + ROOT_HORIZONTAL_PADDING_DP)
                            panel.requestLayout()
                            root.requestLayout()
                            try {
                                windowManager.updateViewLayout(root, windowParams)
                            } catch (_: IllegalArgumentException) {
                                // Ignore a late tap after service shutdown.
                            }
                        }
                        return true
                    }
                }
                return false
            }
        })

        rootView = root
        actionPanel = panel
        bubbleView = bubble
        try {
            windowManager.addView(root, windowParams)
        } catch (exception: Exception) {
            rootView = null
            actionPanel = null
            bubbleView = null
            ClipboardTools.toast(this, "Could not show bubble: " + (exception.message ?: "overlay error"))
            stopSelf()
        }
    }


    private fun bubbleSizeDp(): Int =
        preferences.getInt(KEY_BUBBLE_SIZE_DP, DEFAULT_BUBBLE_SIZE_DP).coerceIn(40, 80)

    private fun bubbleOpacityPercent(): Int =
        preferences.getInt(KEY_BUBBLE_OPACITY_PERCENT, DEFAULT_BUBBLE_OPACITY_PERCENT).coerceIn(25, 100)

    private fun glassButtonBackground(): GradientDrawable {
        val alpha = (bubbleOpacityPercent() * 255 / 100).coerceIn(0, 255)
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(alpha, 92, 96, 102))
            setStroke(dp(2), Color.argb(230, 255, 255, 255))
        }
    }

    private fun bubbleBackground(): GradientDrawable = glassButtonBackground()

    private fun applyBubbleAppearance() {
        val size = bubbleSizeDp()
        val bubble = bubbleView
        if (bubble != null) {
            bubble.textSize = balanceBubbleTextSize(size, bubble.text.length)
            bubble.background = glassButtonBackground()
            val bubbleLayout = bubble.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(dp(size), dp(size))
            bubbleLayout.width = dp(size)
            bubbleLayout.height = dp(size)
            bubbleLayout.topMargin = dp(6)
            bubble.layoutParams = bubbleLayout
        }

        actionPanel?.let { panel ->
            panel.removeAllViews()
            actionButtons = createActionButtons()
            actionButtons.forEach(panel::addView)
            updateClipboardButtonLabels()
        }
        actionButtons.forEach { button ->
            button.textSize = (size * when {
                button.text == "2FA" -> 0.22f
                button.text.length > 2 -> 0.19f
                else -> 0.27f
            }).coerceIn(10f, 20f)
            button.background = glassButtonBackground()
            val params = button.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(dp(size), dp(size))
            params.width = dp(size)
            params.height = dp(size)
            params.bottomMargin = dp(8)
            button.layoutParams = params
        }

        windowParams.width = dp(size + ROOT_HORIZONTAL_PADDING_DP)
        rootView?.requestLayout()
        try {
            rootView?.let { windowManager.updateViewLayout(it, windowParams) }
        } catch (_: IllegalArgumentException) {
            // The overlay may be closing while settings are being applied.
        }
    }

    private fun createActionButtons(): List<TextView> {
        val buttons = mutableListOf<TextView>()
        if (preferences.getBoolean(KEY_SHOW_TXT1_BUTTON, true)) {
            buttons.add(actionButton("TXT1", "#EC4899") { onQuickTextButtonTap(1) })
        }
        if (preferences.getBoolean(KEY_SHOW_TXT2_BUTTON, true)) {
            buttons.add(actionButton("TXT2", "#EC4899") { onQuickTextButtonTap(2) })
        }
        if (preferences.getBoolean(KEY_SHOW_TXT3_BUTTON, true)) {
            buttons.add(actionButton("TXT3", "#EC4899") { onQuickTextButtonTap(3) })
        }
        buttons.add(actionButton("2FA", "#22D3EE") { generateAndCopyTotp() })
        if (preferences.getBoolean(KEY_SHOW_BUTTON_A, true)) {
            buttons.add(actionButton("A1", "#EC4899") { saveClipboardTo("A") })
        }
        if (preferences.getBoolean(KEY_SHOW_BUTTON_B, true)) {
            buttons.add(actionButton("B1", "#EC4899") { saveClipboardTo("B") })
        }
        if (preferences.getBoolean(KEY_SHOW_BUTTON_C, true)) {
            buttons.add(actionButton("C1", "#EC4899") { saveClipboardTo("C") })
        }
        if (preferences.getBoolean(KEY_SHOW_BUTTON_D, true)) {
            buttons.add(actionButton("D1", "#EC4899") { saveClipboardTo("D") })
        }
        if (preferences.getBoolean(KEY_SHOW_BUTTON_E, true)) {
            buttons.add(actionButton("E1", "#EC4899") { saveClipboardTo("E") })
        }
        buttons.add(actionButton("💔", "#F87171") { stopSelf() })
        return buttons
    }

    private fun actionButton(label: String, color: String, action: () -> Unit): TextView {
        val size = bubbleSizeDp()
        return TextView(this).apply {
            text = label
            textSize = (size * when {
                label == "2FA" -> 0.22f
                label.length > 2 -> 0.19f
                else -> 0.27f
            }).coerceIn(10f, 20f)
            setTextColor(Color.parseColor(color))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            background = glassButtonBackground()
            isClickable = true
            isFocusable = true
            elevation = dp(5).toFloat()
            contentDescription = when {
                label == "TXT1" -> "Copy next Quick Text from TXT1"
                label == "TXT2" -> "Copy next Quick Text from TXT2"
                label == "2FA" -> "Generate and copy 2FA code"
                label.startsWith("A") -> "Save clipboard text to Column A"
                label.startsWith("B") -> "Save clipboard text to Column B"
                label.startsWith("C") -> "Save clipboard text to Column C"
                label.startsWith("D") -> "Save clipboard text to Column D"
                label.startsWith("E") -> "Save clipboard text to Column E"
                else -> "Close FloatClip bubble"
            }
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply {
                bottomMargin = dp(8)
            }
        }
    }

    private val lastQuickTextTapAt = mutableMapOf<Int, Long>()

    private fun onQuickTextButtonTap(queue: Int) {
        val now = SystemClock.elapsedRealtime()
        val previousTap = lastQuickTextTapAt[queue]
        val replayPrevious = previousTap != null && now - previousTap <= 1_000L
        lastQuickTextTapAt[queue] = now
        copyQuickText(queue, replayPrevious)
    }

    private fun quickTextPreferenceKey(queue: Int): String = when (queue) {
        1 -> KEY_LAST_QUICK_TEXT_ID
        2 -> KEY_LAST_QUICK_TEXT_ID_2
        else -> KEY_LAST_QUICK_TEXT_ID_3
    }

    private fun copyQuickText(queue: Int, replayPrevious: Boolean) {
        serviceScope.launch {
            quickCopyMutex.withLock {
                val dao = database.dao()
                val items = dao.getQuickTextsOnce(queue).filter { it.content.isNotBlank() }
                if (items.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        ClipboardTools.toast(this@FloatingBubbleService, "TXT$queue is empty. Add text in FloatClip first.")
                    }
                    return@withLock
                }
                val key = quickTextPreferenceKey(queue)
                val lastId = preferences.getLong(key, -1L)
                val lastIndex = items.indexOfFirst { it.id == lastId }
                val target = if (replayPrevious && lastIndex >= 0) {
                    // Double tap moves one position backward in this queue.
                    // The queue wraps from the first item to the last item.
                    items[(lastIndex - 1 + items.size) % items.size]
                } else {
                    items[(lastIndex + 1) % items.size]
                }
                withContext(Dispatchers.Main) {
                    ClipboardTools.copyText(this@FloatingBubbleService, target.title, target.content)
                    preferences.edit().putLong(key, target.id).apply()
                    ClipboardTools.toast(
                        this@FloatingBubbleService,
                        if (replayPrevious) "Previous: " + target.title else "TXT" + queue + " copied: " + target.title
                    )
                }
                dao.setQuickTextLastCopied(queue, target.id)
            }
        }
    }

    private fun updateClipboardButtonLabels() {
        serviceScope.launch {
            val rows = database.dao().getClipboardSheetOnce()
            withContext(Dispatchers.Main) {
                actionButtons.forEach { button ->
                    val slot = when (button.contentDescription?.toString()) {
                        "Save clipboard text to Column A" -> "A"
                        "Save clipboard text to Column B" -> "B"
                        "Save clipboard text to Column C" -> "C"
                        "Save clipboard text to Column D" -> "D"
                        "Save clipboard text to Column E" -> "E"
                        else -> null
                    } ?: return@forEach
                    val firstEmpty = rows.indexOfFirst { row ->
                        when (slot) {
                            "A" -> row.colA.isBlank()
                            "B" -> row.colB.isBlank()
                            "C" -> row.colC.isBlank()
                            "D" -> row.colD.isBlank()
                            else -> row.colE.isBlank()
                        }
                    }
                    val nextLabel = slot + (if (firstEmpty >= 0) firstEmpty + 1 else rows.size + 1)
                    button.text = nextLabel
                    button.textSize = (bubbleSizeDp() * if (nextLabel.length > 2) 0.19f else 0.27f).coerceIn(10f, 20f)
                }
            }
        }
    }

    /**
     * Android 10+ restricts clipboard reads while the app has no input focus.
     * The bubble is normally FLAG_NOT_FOCUSABLE so it does not steal focus from
     * the app underneath. Briefly make the overlay focusable for a user-initiated
     * clipboard read, then restore the original flags immediately.
     */
    private suspend fun readClipboardFromBubble(): String? = withContext(Dispatchers.Main.immediate) {
        val root = rootView ?: return@withContext ClipboardTools.readText(this@FloatingBubbleService)
        if (!::windowParams.isInitialized) {
            return@withContext ClipboardTools.readText(this@FloatingBubbleService)
        }

        val originalFlags = windowParams.flags
        val originalFocusableInTouchMode = root.isFocusableInTouchMode
        var flagsChanged = false
        try {
            windowParams.flags =
                (originalFlags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            windowManager.updateViewLayout(root, windowParams)
            flagsChanged = true

            root.isFocusableInTouchMode = true
            root.requestFocus()
            // Let WindowManager transfer input focus before reading the clipboard.
            delay(120)
            ClipboardTools.readText(this@FloatingBubbleService)
        } catch (_: Exception) {
            // Fall back to the normal clipboard read if focus could not be changed.
            ClipboardTools.readText(this@FloatingBubbleService)
        } finally {
            if (flagsChanged) {
                try {
                    windowParams.flags = originalFlags
                    windowManager.updateViewLayout(root, windowParams)
                } catch (_: Exception) {
                    // The overlay may have been removed while the action was running.
                }
            }
            root.isFocusableInTouchMode = originalFocusableInTouchMode
            if (!originalFocusableInTouchMode) root.clearFocus()
        }
    }

    private fun saveClipboardTo(slot: String) {
        serviceScope.launch(Dispatchers.Main.immediate) {
            val content = readClipboardFromBubble()
            if (content == null) {
                ClipboardTools.toast(
                    this@FloatingBubbleService,
                    "Clipboard text is unavailable. Copy text first, then tap $slot again."
                )
                return@launch
            }

            try {
                val activeColumns = activeRewardColumns()
                val rewardAmountPaisa = preferences.getLong(KEY_REWARD_PER_ROW_PAISA, 0L).coerceAtLeast(0L)
                val reward = withContext(Dispatchers.IO) {
                    clipboardSaveMutex.withLock {
                        val dao = database.dao()
                        val rows = dao.getClipboardSheetOnce()
                        // Each column fills its own first empty cell, keeping A–E aligned by row.
                        val target = rows.firstOrNull { row ->
                            when (slot) {
                                "A" -> row.colA.isBlank()
                                "B" -> row.colB.isBlank()
                                "C" -> row.colC.isBlank()
                                "D" -> row.colD.isBlank()
                                else -> row.colE.isBlank()
                            }
                        }
                        val savedRowId = if (target != null) {
                            val updated = when (slot) {
                                "A" -> target.copy(colA = content)
                                "B" -> target.copy(colB = content)
                                "C" -> target.copy(colC = content)
                                "D" -> target.copy(colD = content)
                                else -> target.copy(colE = content)
                            }
                            dao.updateClipboardSheet(updated)
                            target.id
                        } else {
                            val position = (dao.maxClipboardSheetPosition() ?: -1) + 1
                            val row = when (slot) {
                                "A" -> ClipboardSheetEntity(position = position, colA = content)
                                "B" -> ClipboardSheetEntity(position = position, colB = content)
                                "C" -> ClipboardSheetEntity(position = position, colC = content)
                                "D" -> ClipboardSheetEntity(position = position, colD = content)
                                else -> ClipboardSheetEntity(position = position, colE = content)
                            }
                            dao.insertClipboardSheet(row)
                        }
                        dao.rewardClipboardRowIfComplete(savedRowId, activeColumns, rewardAmountPaisa)
                    }
                }
                updateClipboardButtonLabels()
                ClipboardTools.toast(
                    this@FloatingBubbleService,
                    if (reward > 0L) {
                        "Row completed! +${formatTaka(reward)} added to balance."
                    } else {
                        "Saved to Clipboard Sheet, column $slot."
                    }
                )
            } catch (exception: Exception) {
                ClipboardTools.toast(
                    this@FloatingBubbleService,
                    "Could not save to column $slot: ${exception.message ?: "database error"}"
                )
            }
        }
    }

    private fun activeRewardColumns(): List<String> = buildList {
        if (preferences.getBoolean(KEY_SHOW_BUTTON_A, true)) add("A")
        if (preferences.getBoolean(KEY_SHOW_BUTTON_B, true)) add("B")
        if (preferences.getBoolean(KEY_SHOW_BUTTON_C, true)) add("C")
        if (preferences.getBoolean(KEY_SHOW_BUTTON_D, true)) add("D")
        if (preferences.getBoolean(KEY_SHOW_BUTTON_E, true)) add("E")
    }

    private fun observeBalance() {
        serviceScope.launch {
            database.dao().observeTotalBalancePaisa().collect { amountPaisa ->
                withContext(Dispatchers.Main.immediate) {
                    val bubble = bubbleView ?: return@withContext
                    val label = formatBubbleBalance(amountPaisa)
                    bubble.text = label
                    bubble.textSize = balanceBubbleTextSize(bubbleSizeDp(), label.length)
                    bubble.contentDescription = "FloatClip balance ${formatTaka(amountPaisa)}"
                }
            }
        }
    }

    private fun balanceBubbleTextSize(size: Int, labelLength: Int): Float =
        (size * when {
            labelLength >= 8 -> 0.145f
            labelLength >= 6 -> 0.17f
            labelLength >= 4 -> 0.20f
            else -> 0.24f
        }).coerceIn(9f, 17f)

    private fun formatBubbleBalance(amountPaisa: Long): String {
        val amount = amountPaisa / 100.0
        val value = when {
            amount >= 1_000_000 -> String.format(Locale.US, "%.1fM", amount / 1_000_000.0)
            amount >= 10_000 -> String.format(Locale.US, "%.0fK", amount / 1_000.0)
            amount >= 1_000 -> String.format(Locale.US, "%.1fK", amount / 1_000.0)
            amountPaisa % 100L == 0L -> (amountPaisa / 100L).toString()
            else -> String.format(Locale.US, "%.2f", amount)
        }
        return "৳" + value
    }

    private fun formatTaka(amountPaisa: Long): String =
        "৳" + String.format(Locale.US, "%,.2f", amountPaisa / 100.0)

    private fun generateAndCopyTotp() {
        serviceScope.launch(Dispatchers.Main.immediate) {
            val secret = readClipboardFromBubble()
            if (secret == null) {
                ClipboardTools.toast(
                    this@FloatingBubbleService,
                    "2FA key is unavailable. Copy the Base32 secret key, then tap 2FA again."
                )
                return@launch
            }

            try {
                val code = withContext(Dispatchers.Default) { TotpGenerator.generate(secret) }
                ClipboardTools.copyText(this@FloatingBubbleService, "FloatClip 2FA code", code)
                ClipboardTools.toast(this@FloatingBubbleService, "2FA code copied: $code")
            } catch (exception: Exception) {
                ClipboardTools.toast(
                    this@FloatingBubbleService,
                    exception.message ?: "Could not generate a 2FA code."
                )
            }
        }
    }

    private fun roundedBackground(fill: Int, radius: Int, stroke: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        rootView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
                // The view can already be detached by the system.
            }
        }
        rootView = null
        actionPanel = null
        bubbleView = null
        actionButtons = emptyList()
        if (settingsReceiverRegistered) {
            try {
                unregisterReceiver(settingsReceiver)
            } catch (_: IllegalArgumentException) {
                // Receiver may already be unregistered during shutdown.
            }
            settingsReceiverRegistered = false
        }
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "floatclip_bubble"
        private const val NOTIFICATION_ID = 4102
        private const val KEY_LAST_QUICK_TEXT_ID = "last_quick_text_id"
        private const val KEY_LAST_QUICK_TEXT_ID_2 = "last_quick_text_id_2"
        private const val KEY_LAST_QUICK_TEXT_ID_3 = "last_quick_text_id_3"
        const val ACTION_APPLY_SETTINGS = "com.floatclip.app.ACTION_APPLY_BUBBLE_SETTINGS"
        private const val KEY_BUBBLE_SIZE_DP = "bubble_size_dp"
        private const val KEY_BUBBLE_OPACITY_PERCENT = "bubble_opacity_percent"
        private const val KEY_SHOW_BUTTON_A = "show_bubble_button_a"
        private const val KEY_SHOW_BUTTON_B = "show_bubble_button_b"
        private const val KEY_SHOW_BUTTON_C = "show_bubble_button_c"
        private const val KEY_SHOW_BUTTON_D = "show_bubble_button_d"
        private const val KEY_SHOW_BUTTON_E = "show_bubble_button_e"
        private const val KEY_SHOW_TXT1_BUTTON = "show_bubble_txt1"
        private const val KEY_SHOW_TXT2_BUTTON = "show_bubble_txt2"
        private const val KEY_SHOW_TXT3_BUTTON = "show_bubble_txt3"
        private const val KEY_REWARD_PER_ROW_PAISA = "reward_per_completed_row_paisa"
        private const val DEFAULT_BUBBLE_SIZE_DP = 58
        private const val DEFAULT_BUBBLE_OPACITY_PERCENT = 75
        private const val ROOT_HORIZONTAL_PADDING_DP = 8

    }
}
