package com.go300.gotodo

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CalendarContract
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

// 1. 할 일 데이터 구조
data class TodoItem(
    val id: Long,
    val title: String,
    val isStarred: Boolean,
    val dueDate: String, // "" 또는 "yyyy-MM-dd"
    val isDone: Boolean,
    val createdAt: Long
)

// 2. 캘린더 일정 데이터 구조
data class CalendarEventItem(
    val title: String,
    val timeText: String,
    val startMillis: Long,
    val isAllDay: Boolean
)

// 3. 할 일 저장소 및 캘린더 연동 매니저
object TodoRepository {
    private const val PREF_NAME = "gotodo_prefs"
    private const val KEY_TODOS = "todos_json"
    private const val KEY_SERVICE_ENABLED = "service_enabled"
    private const val KEY_POPUP_ON_SCREEN_ON = "popup_on_screen_on"
    private const val KEY_SHOW_CALENDAR = "show_calendar"
    private const val KEY_SHOW_COMPLETED = "show_completed"
    private const val KEY_HIDE_WHEN_EMPTY = "hide_when_empty"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var isServiceEnabled: Boolean
        get() = true
        set(_) {}

    fun getServiceEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SERVICE_ENABLED, true)

    fun setServiceEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()

    fun getPopupOnScreenOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_POPUP_ON_SCREEN_ON, true)

    fun setPopupOnScreenOn(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_POPUP_ON_SCREEN_ON, value).apply()

    fun getShowCalendar(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_CALENDAR, true)

    fun setShowCalendar(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_SHOW_CALENDAR, value).apply()

    fun getShowCompleted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_COMPLETED, false)

    fun setShowCompleted(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_SHOW_COMPLETED, value).apply()

    fun getHideWhenEmpty(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HIDE_WHEN_EMPTY, true)

    fun setHideWhenEmpty(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_HIDE_WHEN_EMPTY, value).apply()

    // 저장된 전체 할 일 불러오기 및 우선순위 자동 정렬
    fun loadTodos(context: Context): List<TodoItem> {
        val raw = prefs(context).getString(KEY_TODOS, "[]") ?: "[]"
        val list = mutableListOf<TodoItem>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    TodoItem(
                        id = obj.getLong("id"),
                        title = obj.getString("title"),
                        isStarred = obj.optBoolean("isStarred", false),
                        dueDate = obj.optString("dueDate", ""),
                        isDone = obj.optBoolean("isDone", false),
                        createdAt = obj.optLong("createdAt", 0L)
                    )
                )
            }
        } catch (_: Exception) {}

        // 정렬: 1. 미완료 우선 -> 2. 중요(⭐) 우선 -> 3. 기한 임박 순 -> 4. 등록 순
        return list.sortedWith(
            compareBy<TodoItem> { it.isDone }
                .thenByDescending { it.isStarred }
                .thenBy { if (it.dueDate.isEmpty()) "9999-99-99" else it.dueDate }
                .thenBy { it.createdAt }
        )
    }

    private fun saveTodos(context: Context, items: List<TodoItem>) {
        val arr = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("isStarred", item.isStarred)
                put("dueDate", item.dueDate)
                put("isDone", item.isDone)
                put("createdAt", item.createdAt)
            }
            arr.put(obj)
        }
        prefs(context).edit().putString(KEY_TODOS, arr.toString()).apply()
        TodoLockService.refreshNotificationIfRunning(context)
    }

    fun addTodo(context: Context, title: String, isStarred: Boolean, dueDate: String) {
        val current = loadTodos(context).toMutableList()
        val now = System.currentTimeMillis()
        current.add(
            TodoItem(
                id = now,
                title = title.trim(),
                isStarred = isStarred,
                dueDate = dueDate,
                isDone = false,
                createdAt = now
            )
        )
        saveTodos(context, current)
    }

    fun toggleDone(context: Context, id: Long) {
        val updated = loadTodos(context).map {
            if (it.id == id) it.copy(isDone = !it.isDone) else it
        }
        saveTodos(context, updated)
    }

    fun markDone(context: Context, id: Long) {
        val updated = loadTodos(context).map {
            if (it.id == id) it.copy(isDone = true) else it
        }
        saveTodos(context, updated)
    }

    fun toggleStar(context: Context, id: Long) {
        val updated = loadTodos(context).map {
            if (it.id == id) it.copy(isStarred = !it.isStarred) else it
        }
        saveTodos(context, updated)
    }

    fun deleteTodo(context: Context, id: Long) {
        val updated = loadTodos(context).filterNot { it.id == id }
        saveTodos(context, updated)
    }

    fun clearCompleted(context: Context) {
        val updated = loadTodos(context).filterNot { it.isDone }
        saveTodos(context, updated)
    }

    // 기한(D-Day) 꼬리표 계산
    fun getDueBadgeText(dueDate: String): Pair<String, String> {
        if (dueDate.isBlank()) return Pair("", "#90A4AE")
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
            val todayCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val dueTime = sdf.parse(dueDate)?.time ?: return Pair(dueDate, "#90A4AE")
            val diffDays = ((dueTime - todayCal.timeInMillis) / (24 * 60 * 60 * 1000L)).toInt()

            when {
                diffDays < 0 -> Pair("⚠️ 기한 지남 (${abs(diffDays)}일 경과 · $dueDate)", "#FF5252")
                diffDays == 0 -> Pair("🔥 오늘까지 ($dueDate)", "#FF9800")
                diffDays == 1 -> Pair("⏰ 내일까지 ($dueDate)", "#29B6F6")
                else -> Pair("📅 D-$diffDays ($dueDate)", "#81C784")
            }
        } catch (_: Exception) {
            Pair(dueDate, "#90A4AE")
        }
    }

    // 휴대폰 기본 캘린더에서 '오늘의 일정(반복/종일 포함)' 읽어오기
    fun loadTodayCalendarEvents(context: Context): List<CalendarEventItem> {
        if (!getShowCalendar(context)) return emptyList()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        val results = mutableListOf<CalendarEventItem>()
        try {
            val todayStartCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val todayEndCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }

            val todayStartMs = todayStartCal.timeInMillis
            val todayEndMs = todayEndCal.timeInMillis
            val todayDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(todayStartCal.time)
            val utcSdf = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val timeFormat = SimpleDateFormat("HH:mm", Locale.KOREA)

            // 종일 일정(UTC 기준) 누락 방지를 위해 전후 하루 여유를 두고 Instances 조회
            val queryStart = todayStartMs - 86_400_000L
            val queryEnd = todayEndMs + 86_400_000L

            val projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY
            )

            val cursor = CalendarContract.Instances.query(
                context.contentResolver,
                projection,
                queryStart,
                queryEnd
            )

            cursor?.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(0)?.ifBlank { "(제목 없음)" } ?: "(제목 없음)"
                    val begin = c.getLong(1)
                    val end = c.getLong(2)
                    val allDay = c.getInt(3) == 1

                    if (allDay) {
                        val beginUtcDate = utcSdf.format(Date(begin))
                        if (beginUtcDate == todayDateStr) {
                            results.add(
                                CalendarEventItem(
                                    title = title,
                                    timeText = "종일",
                                    startMillis = begin,
                                    isAllDay = true
                                )
                            )
                        }
                    } else {
                        if (begin <= todayEndMs && end >= todayStartMs) {
                            val startStr = timeFormat.format(Date(begin))
                            val endStr = timeFormat.format(Date(end))
                            results.add(
                                CalendarEventItem(
                                    title = title,
                                    timeText = "$startStr~$endStr",
                                    startMillis = begin,
                                    isAllDay = false
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return results
            .distinctBy { "${it.title}_${it.timeText}" }
            .sortedWith(compareByDescending<CalendarEventItem> { it.isAllDay }.thenBy { it.startMillis })
    }
}

// 4. 화면 2(잠금화면) 알림 카드 유지 및 화면 켜짐(노크온/전원버튼) 감지 서비스
class TodoLockService : Service() {

    companion object {
        const val CHANNEL_ID = "gotodo_lockscreen_channel"
        const val NOTIF_ID = 2001
        const val ACTION_REFRESH = "com.go300.gotodo.ACTION_REFRESH"

        fun startOrUpdate(context: Context) {
            if (!TodoRepository.getServiceEnabled(context)) {
                stop(context)
                return
            }
            try {
                val intent = Intent(context, TodoLockService::class.java).apply {
                    action = ACTION_REFRESH
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {}
        }

        fun refreshNotificationIfRunning(context: Context) {
            if (TodoRepository.getServiceEnabled(context)) {
                startOrUpdate(context)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, TodoLockService::class.java))
            } catch (_: Exception) {}
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context == null) return
            if (intent?.action == Intent.ACTION_SCREEN_ON) {
                // 화면이 켜질 때 알림창 날짜/일정도 함께 최신화
                updateNotification()

                if (!TodoRepository.getPopupOnScreenOn(context)) return

                // 통화 중이거나 벨이 울릴 때는 보드를 띄우지 않음
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (am != null && (am.mode == AudioManager.MODE_RINGTONE ||
                            am.mode == AudioManager.MODE_IN_CALL ||
                            am.mode == AudioManager.MODE_IN_COMMUNICATION)
                ) {
                    return
                }

                val pendingTodos = TodoRepository.loadTodos(context).filter { !it.isDone }
                val todayEvents = TodoRepository.loadTodayCalendarEvents(context)
                if (TodoRepository.getHideWhenEmpty(context) &&
                    pendingTodos.isEmpty() &&
                    todayEvents.isEmpty()
                ) {
                    return
                }

                // 잠금화면 위로 OLED 다크 보드 즉시 실행
                try {
                    val boardIntent = Intent(context, LockBoardActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                        )
                    }
                    context.startActivity(boardIntent)
                } catch (_: Exception) {}
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildLockScreenNotification())
        return START_STICKY
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIF_ID, buildLockScreenNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "고할일 잠금화면 할 일 보드",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "잠금화면(화면 2)에 미완료 할 일과 오늘 일정을 표시합니다."
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildLockScreenNotification(): Notification {
        val pendingTodos = TodoRepository.loadTodos(this).filter { !it.isDone }
        val todayEvents = TodoRepository.loadTodayCalendarEvents(this)

        // 알림 카드를 터치하면 잠금 해제 없이 전체 다크 보드가 열림
        val boardIntent = Intent(this, LockBoardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val boardPending = PendingIntent.getActivity(
            this, 10, boardIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPending = PendingIntent.getActivity(
            this, 11, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when {
            pendingTodos.isEmpty() && todayEvents.isEmpty() -> "✅ 남은 할 일이 없습니다"
            todayEvents.isNotEmpty() -> "📋 미완료 할 일 ${pendingTodos.size}건 · 📅 오늘 일정 ${todayEvents.size}건"
            else -> "📋 미완료 할 일 ${pendingTodos.size}건"
        }

        val summaryLine = when {
            pendingTodos.isNotEmpty() -> {
                val first = pendingTodos.first()
                val star = if (first.isStarred) "⭐ " else "☐ "
                "$star${first.title}" + if (pendingTodos.size > 1) " 외 ${pendingTodos.size - 1}건" else ""
            }
            todayEvents.isNotEmpty() -> "📅 [${todayEvents.first().timeText}] ${todayEvents.first().title}"
            else -> "새로운 할 일을 등록하려면 터치하세요."
        }

        val inboxStyle = NotificationCompat.InboxStyle().setBigContentTitle(title)
        for (ev in todayEvents.take(2)) {
            inboxStyle.addLine("📅 [${ev.timeText}] ${ev.title}")
        }
        for (todo in pendingTodos.take(5)) {
            val prefix = if (todo.isStarred) "⭐ " else "☐ "
            val dueTag = if (todo.dueDate.isNotEmpty()) " (${todo.dueDate})" else ""
            inboxStyle.addLine("$prefix${todo.title}$dueTag")
        }
        if (pendingTodos.size > 5) {
            inboxStyle.setSummaryText("+ 외 ${pendingTodos.size - 5}건 더보기 (터치하여 전체 보드 열기)")
        } else {
            inboxStyle.setSummaryText("고할일 · 터치하면 잠금화면 보드가 열립니다")
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(title)
            .setContentText(summaryLine)
            .setStyle(inboxStyle)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(boardPending)
            .addAction(android.R.drawable.ic_menu_view, "전체 보드 보기", boardPending)
            .addAction(android.R.drawable.ic_menu_edit, "할 일 추가/관리", mainPending)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

// 5. 휴대폰 재부팅 시 자동 시작 리시버
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context != null && intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            if (TodoRepository.getServiceEnabled(context)) {
                TodoLockService.startOrUpdate(context)
            }
        }
    }
}

// 6. 화면 2 전용: 잠금화면 위에 뜨는 OLED 다크 보드 액티비티
class LockBoardActivity : AppCompatActivity() {

    private lateinit var clockText: TextView
    private lateinit var dateText: TextView
    private lateinit var calendarContainer: LinearLayout
    private lateinit var todoListContainer: LinearLayout
    private lateinit var countBadgeText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 10_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 잠금 해제 없이 화면 2(잠금화면) 위에 즉시 표시되도록 설정
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        window.statusBarColor = Color.parseColor("#080B11")
        window.navigationBarColor = Color.parseColor("#080B11")

        buildDarkBoardUi()
    }

    override fun onResume() {
        super.onResume()
        updateClock()
        handler.post(clockRunnable)
        renderBoardContent()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockRunnable)
    }

    private fun makeDarkCard(bgColor: String, strokeColor: String): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 26f
            setColor(Color.parseColor(bgColor))
            setStroke(2, Color.parseColor(strokeColor))
        }
    }

    private fun buildDarkBoardUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#080B11")) // OLED 순수 다크 배경
            setPadding(40, 48, 40, 36)
        }

        // 상단 시계 및 날짜 영역
        clockText = TextView(this).apply {
            text = "00:00"
            textSize = 54f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        dateText = TextView(this).apply {
            text = ""
            textSize = 15f
            setTextColor(Color.parseColor("#90A4AE"))
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 28)
        }

        // 중앙 스크롤 가능 영역 (할 일이 아무리 많아도 끝까지 스크롤 가능)
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4, 0, 24)
        }

        calendarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeDarkCard("#101926", "#1E3A5F")
            setPadding(32, 28, 32, 28)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 24 }
            layoutParams = params
        }

        val todoHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 16)
        }

        val todoHeaderTitle = TextView(this).apply {
            text = "📋 해야 할 일 목록"
            textSize = 16.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E2E8F0"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        countBadgeText = TextView(this).apply {
            text = "0건"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#4DB6AC"))
        }

        todoHeaderRow.addView(todoHeaderTitle)
        todoHeaderRow.addView(countBadgeText)

        todoListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        scrollContent.addView(calendarContainer)
        scrollContent.addView(todoHeaderRow)
        scrollContent.addView(todoListContainer)
        scrollView.addView(scrollContent)

        // 하단 닫기 버튼 영역
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 0)
        }

        val closeBtn = Button(this).apply {
            text = "잠금화면으로 닫기"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E2E8F0"))
            background = makeDarkCard("#1A2332", "#334155")
            setPadding(24, 32, 24, 32)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                finish()
            }
        }

        bottomRow.addView(closeBtn)

        val footer = TextView(this).apply {
            text = "고할일  |  제작: 고삼백 (Go300)"
            textSize = 11.5f
            setTextColor(Color.parseColor("#475569"))
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }

        root.addView(clockText)
        root.addView(dateText)
        root.addView(scrollView)
        root.addView(bottomRow)
        root.addView(footer)

        setContentView(root)
    }

    private fun updateClock() {
        val now = Date()
        clockText.text = SimpleDateFormat("HH:mm", Locale.KOREA).format(now)
        dateText.text = SimpleDateFormat("yyyy년 M월 d일 EEEE", Locale.KOREA).format(now)
    }

    private fun renderBoardContent() {
        // 1) 오늘의 캘린더 일정 표시 (일정이 없으면 자동 숨김)
        val events = TodoRepository.loadTodayCalendarEvents(this)
        calendarContainer.removeAllViews()
        if (events.isEmpty()) {
            calendarContainer.visibility = View.GONE
        } else {
            calendarContainer.visibility = View.VISIBLE
            val calTitle = TextView(this).apply {
                text = "📅 오늘의 캘린더 일정 (${events.size}건)"
                textSize = 14.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#64B5F6"))
                setPadding(0, 0, 0, 14)
            }
            calendarContainer.addView(calTitle)

            for (ev in events) {
                val evText = TextView(this).apply {
                    text = "• [${ev.timeText}]  ${ev.title}"
                    textSize = 14.5f
                    setTextColor(Color.parseColor("#E3F2FD"))
                    setPadding(4, 6, 4, 6)
                }
                calendarContainer.addView(evText)
            }
        }

        // 2) 할 일 목록 표시
        val showCompleted = TodoRepository.getShowCompleted(this)
        val allTodos = TodoRepository.loadTodos(this)
        val visibleTodos = if (showCompleted) allTodos else allTodos.filter { !it.isDone }
        val pendingCount = allTodos.count { !it.isDone }

        countBadgeText.text = "미완료 ${pendingCount}건"
        todoListContainer.removeAllViews()

        if (visibleTodos.isEmpty()) {
            val emptyBox = TextView(this).apply {
                text = "🎉 현재 처리하지 않은 할 일이 없습니다."
                textSize = 15f
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.CENTER
                background = makeDarkCard("#111722", "#1E293B")
                setPadding(32, 48, 32, 48)
            }
            todoListContainer.addView(emptyBox)
            return
        }

        for (item in visibleTodos) {
            val strokeColor = when {
                item.isDone -> "#1E293B"
                item.isStarred -> "#F59E0B"
                else -> "#26334D"
            }
            val bgColor = if (item.isDone) "#0D111A" else "#131B2E"

            val itemCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = makeDarkCard(bgColor, strokeColor)
                setPadding(28, 26, 28, 26)
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 16 }
                layoutParams = params
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val starPrefix = if (item.isStarred) "⭐ " else ""
            val titleView = TextView(this).apply {
                text = "$starPrefix${item.title}"
                textSize = 16f
                setTypeface(null, if (item.isStarred && !item.isDone) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(
                    if (item.isDone) Color.parseColor("#64748B") else Color.parseColor("#F8FAFC")
                )
                if (item.isDone) {
                    paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                }
            }
            textCol.addView(titleView)

            if (item.dueDate.isNotBlank()) {
                val (badgeStr, badgeColor) = TodoRepository.getDueBadgeText(item.dueDate)
                val dueView = TextView(this).apply {
                    text = badgeStr
                    textSize = 12.5f
                    setTextColor(
                        if (item.isDone) Color.parseColor("#475569") else Color.parseColor(badgeColor)
                    )
                    setPadding(0, 6, 0, 0)
                }
                textCol.addView(dueView)
            }

            // 실수 방지: 잠금화면 보드에서 완료 버튼을 누르면 확인 창이 한 번 뜸
            val actionBtn = Button(this).apply {
                text = if (item.isDone) "↩ 복원" else "☐ 완료"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (item.isDone) Color.parseColor("#94A3B8") else Color.parseColor("#4DB6AC"))
                background = makeDarkCard("#1E293B", "#334155")
                setPadding(22, 14, 22, 14)
                setOnClickListener {
                    if (!item.isDone) {
                        confirmCompleteOnLockScreen(item)
                    } else {
                        TodoRepository.toggleDone(this@LockBoardActivity, item.id)
                        renderBoardContent()
                    }
                }
            }

            itemCard.addView(textCol)
            itemCard.addView(actionBtn)
            todoListContainer.addView(itemCard)
        }
    }

    private fun confirmCompleteOnLockScreen(item: TodoItem) {
        AlertDialog.Builder(this)
            .setTitle("할 일 완료 확인")
            .setMessage("'${item.title}' 항목을 완료 처리할까요?")
            .setPositiveButton("완료 처리") { _, _ ->
                TodoRepository.markDone(this, item.id)
                renderBoardContent()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}

// 7. 화면 1 전용: 할 일 추가 및 잠금화면 보드 설정 메인 액티비티
class MainActivity : AppCompatActivity() {

    private lateinit var todoInput: EditText
    private lateinit var starCheckBox: CheckBox
    private lateinit var selectedDueText: TextView
    private var currentSelectedDueDate: String = ""

    private lateinit var overlayPermissionBanner: LinearLayout
    private lateinit var calendarSectionLayout: LinearLayout
    private lateinit var todoListLayout: LinearLayout
    private lateinit var summaryCountText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.apply {
            title = "고할일 - 잠금화면 할 일 관리"
            subtitle = "제작: 고삼백 (Go300)"
            elevation = 0f
        }

        buildMainUi()
        checkAndRequestPermissions()

        if (TodoRepository.getServiceEnabled(this)) {
            TodoLockService.startOrUpdate(this)
        }
    }

    override fun onResume() {
        super.onResume()
        updateOverlayPermissionBanner()
        refreshMainLists()
        TodoLockService.refreshNotificationIfRunning(this)
    }

    private fun makeLightCard(bgColor: String = "#FFFFFF", strokeColor: String = "#E0E6ED"): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 26f
            setColor(Color.parseColor(bgColor))
            setStroke(3, Color.parseColor(strokeColor))
        }
    }

    private fun makeRoundedBtn(bgColor: String): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f
            setColor(Color.parseColor(bgColor))
        }
    }

    private fun buildMainUi() {
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F2F5F9"))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 32, 36, 48)
        }

        // [안내 배너] 잠금화면 자동 팝업을 위한 '다른 앱 위에 표시' 권한 안내
        overlayPermissionBanner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeLightCard("#FFF8E1", "#FFB300")
            setPadding(32, 28, 32, 28)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 28 }
            layoutParams = params
        }

        val bannerTitle = TextView(this).apply {
            text = "💡 화면을 켤 때 할 일 보드가 바로 뜨게 하려면?"
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E65100"))
        }

        val bannerDesc = TextView(this).apply {
            text = "노크온이나 전원 버튼을 누르는 순간 잠금화면 위에 다크 보드를 바로 띄우려면 최초 1회 '다른 앱 위에 표시' 허용이 필요합니다."
            textSize = 13f
            setTextColor(Color.parseColor("#5D4037"))
            setPadding(0, 8, 0, 16)
        }

        val grantOverlayBtn = Button(this).apply {
            text = "권한 설정하러 가기 (1회)"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = makeRoundedBtn("#F57C00")
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
            }
        }

        overlayPermissionBanner.addView(bannerTitle)
        overlayPermissionBanner.addView(bannerDesc)
        overlayPermissionBanner.addView(grantOverlayBtn)

        // [카드 1] 새 할 일 빠른 등록 카드
        val inputCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeLightCard("#FFFFFF")
            setPadding(36, 32, 36, 32)
        }

        val inputTitle = TextView(this).apply {
            text = "✏️ 새 할 일 등록"
            textSize = 16.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0D3B4C"))
            setPadding(0, 0, 0, 16)
        }

        todoInput = EditText(this).apply {
            hint = "해야 할 일을 입력하세요"
            textSize = 16f
            setPadding(28, 28, 28, 28)
            background = makeLightCard("#F8FAFC", "#CFD8DC")
        }

        val optionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 12)
        }

        starCheckBox = CheckBox(this).apply {
            text = "⭐ 중요 할 일 (최상단 고정)"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#D97706"))
        }

        optionRow.addView(starCheckBox)

        selectedDueText = TextView(this).apply {
            text = "기한 설정: 기한 없음"
            textSize = 13.5f
            setTextColor(Color.parseColor("#455A64"))
            setPadding(4, 4, 4, 10)
        }

        val dueBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 20)
        }

        fun makeQuickDueButton(label: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                textSize = 12.5f
                setTextColor(Color.parseColor("#0D3B4C"))
                background = makeLightCard("#EEF2F6", "#CFD8DC")
                setPadding(12, 10, 12, 10)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = 10
                }
                setOnClickListener { onClick() }
            }
        }

        val noDueBtn = makeQuickDueButton("기한 없음") {
            currentSelectedDueDate = ""
            selectedDueText.text = "기한 설정: 기한 없음"
        }

        val todayDueBtn = makeQuickDueButton("오늘까지") {
            currentSelectedDueDate = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
            selectedDueText.text = "기한 설정: 오늘까지 ($currentSelectedDueDate)"
        }

        val tomorrowDueBtn = makeQuickDueButton("내일까지") {
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
            currentSelectedDueDate = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(cal.time)
            selectedDueText.text = "기한 설정: 내일까지 ($currentSelectedDueDate)"
        }

        val pickDateBtn = makeQuickDueButton("📅 날짜선택") {
            val cal = Calendar.getInstance()
            DatePickerDialog(
                this,
                { _, y, m, d ->
                    currentSelectedDueDate = String.format(Locale.KOREA, "%04d-%02d-%02d", y, m + 1, d)
                    selectedDueText.text = "기한 설정: $currentSelectedDueDate"
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        dueBtnRow.addView(noDueBtn)
        dueBtnRow.addView(todayDueBtn)
        dueBtnRow.addView(tomorrowDueBtn)
        dueBtnRow.addView(pickDateBtn)

        val addBtn = Button(this).apply {
            text = "+ 할 일 목록에 추가"
            textSize = 16.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = makeRoundedBtn("#0D3B4C")
            setPadding(24, 30, 24, 30)
            setOnClickListener {
                val text = todoInput.text.toString().trim()
                if (text.isEmpty()) {
                    Toast.makeText(this@MainActivity, "할 일 내용을 입력해주세요.", Toast.LENGTH_SHORT).show()
                } else {
                    TodoRepository.addTodo(
                        this@MainActivity,
                        text,
                        starCheckBox.isChecked,
                        currentSelectedDueDate
                    )
                    todoInput.setText("")
                    starCheckBox.isChecked = false
                    currentSelectedDueDate = ""
                    selectedDueText.text = "기한 설정: 기한 없음"
                    refreshMainLists()
                }
            }
        }

        inputCard.addView(inputTitle)
        inputCard.addView(todoInput)
        inputCard.addView(optionRow)
        inputCard.addView(selectedDueText)
        inputCard.addView(dueBtnRow)
        inputCard.addView(addBtn)

        // [카드 2] 현재 할 일 및 오늘의 캘린더 일정 관리 카드
        val listCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeLightCard("#FFFFFF")
            setPadding(36, 32, 36, 32)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 28 }
            layoutParams = params
        }

        val listHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val listHeaderTitle = TextView(this).apply {
            text = "📋 등록된 할 일 & 오늘 일정"
            textSize = 16.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0D3B4C"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        summaryCountText = TextView(this).apply {
            text = "미완료 0건"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00796B"))
        }

        listHeaderRow.addView(listHeaderTitle)
        listHeaderRow.addView(summaryCountText)

        calendarSectionLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        todoListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val clearCompletedBtn = Button(this).apply {
            text = "🧹 완료된 할 일 일괄 삭제"
            textSize = 13.5f
            setTextColor(Color.parseColor("#455A64"))
            background = makeLightCard("#F1F5F9", "#CFD8DC")
            setPadding(20, 18, 20, 18)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
            layoutParams = params
            setOnClickListener {
                TodoRepository.clearCompleted(this@MainActivity)
                refreshMainLists()
            }
        }

        listCard.addView(listHeaderRow)
        listCard.addView(calendarSectionLayout)
        listCard.addView(todoListLayout)
        listCard.addView(clearCompletedBtn)

        // [카드 3] 화면 2(잠금화면) 표시 방식 및 옵션 설정 카드
        val settingsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeLightCard("#FFFFFF")
            setPadding(36, 32, 36, 32)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 28 }
            layoutParams = params
        }

        val settingsTitle = TextView(this).apply {
            text = "⚙️ 잠금화면(화면 2) 표시 설정"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0D3B4C"))
            setPadding(0, 0, 0, 16)
        }

        val previewBoardBtn = Button(this).apply {
            text = "🔍 잠금화면 다크 보드 미리보기 (즉시 테스트)"
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = makeRoundedBtn("#1E293B")
            setPadding(24, 24, 24, 24)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 20 }
            layoutParams = params
            setOnClickListener {
                startActivity(Intent(this@MainActivity, LockBoardActivity::class.java))
            }
        }

        val serviceSwitch = Switch(this).apply {
            text = "🔒 잠금화면 할 일 서비스 켜기"
            textSize = 14.5f
            isChecked = TodoRepository.getServiceEnabled(this@MainActivity)
            setPadding(0, 14, 0, 14)
            setOnCheckedChangeListener { _, isChecked ->
                TodoRepository.setServiceEnabled(this@MainActivity, isChecked)
                if (isChecked) TodoLockService.startOrUpdate(this@MainActivity)
                else TodoLockService.stop(this@MainActivity)
            }
        }

        val popupSwitch = Switch(this).apply {
            text = "📱 화면 켜질 때 전체 다크 보드 바로 띄우기"
            textSize = 14.5f
            isChecked = TodoRepository.getPopupOnScreenOn(this@MainActivity)
            setPadding(0, 14, 0, 14)
            setOnCheckedChangeListener { _, isChecked ->
                TodoRepository.setPopupOnScreenOn(this@MainActivity, isChecked)
            }
        }

        val calendarSwitch = Switch(this).apply {
            text = "📅 휴대폰 기본 캘린더 '오늘 일정' 함께 표시"
            textSize = 14.5f
            isChecked = TodoRepository.getShowCalendar(this@MainActivity)
            setPadding(0, 14, 0, 14)
            setOnCheckedChangeListener { _, isChecked ->
                TodoRepository.setShowCalendar(this@MainActivity, isChecked)
                refreshMainLists()
                TodoLockService.refreshNotificationIfRunning(this@MainActivity)
            }
        }

        val showCompletedSwitch = Switch(this).apply {
            text = "✅ 완료된 할 일도 취소선 그어서 표시"
            textSize = 14.5f
            isChecked = TodoRepository.getShowCompleted(this@MainActivity)
            setPadding(0, 14, 0, 14)
            setOnCheckedChangeListener { _, isChecked ->
                TodoRepository.setShowCompleted(this@MainActivity, isChecked)
                refreshMainLists()
            }
        }

        val hideEmptySwitch = Switch(this).apply {
            text = "🙈 할 일·오늘 일정이 0건일 땐 보드 자동 숨김"
            textSize = 14.5f
            isChecked = TodoRepository.getHideWhenEmpty(this@MainActivity)
            setPadding(0, 14, 0, 14)
            setOnCheckedChangeListener { _, isChecked ->
                TodoRepository.setHideWhenEmpty(this@MainActivity, isChecked)
            }
        }

        settingsCard.addView(settingsTitle)
        settingsCard.addView(previewBoardBtn)
        settingsCard.addView(serviceSwitch)
        settingsCard.addView(popupSwitch)
        settingsCard.addView(calendarSwitch)
        settingsCard.addView(showCompletedSwitch)
        settingsCard.addView(hideEmptySwitch)

        val footer = TextView(this).apply {
            text = "고할일 - 잠금화면 할 일 관리  |  제작: 고삼백 (Go300)"
            textSize = 12f
            setTextColor(Color.parseColor("#78909C"))
            gravity = Gravity.CENTER
            setPadding(0, 36, 0, 8)
        }

        root.addView(overlayPermissionBanner)
        root.addView(inputCard)
        root.addView(listCard)
        root.addView(settingsCard)
        root.addView(footer)

        scrollView.addView(root)
        setContentView(scrollView)
    }

    private fun updateOverlayPermissionBanner() {
        val canDraw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
        overlayPermissionBanner.visibility = if (canDraw) View.GONE else View.VISIBLE
    }

    private fun checkAndRequestPermissions() {
        val perms = mutableListOf(Manifest.permission.READ_CALENDAR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 200)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshMainLists()
        TodoLockService.refreshNotificationIfRunning(this)
    }

    private fun refreshMainLists() {
        // 1) 오늘의 캘린더 일정 영역
        calendarSectionLayout.removeAllViews()
        val events = TodoRepository.loadTodayCalendarEvents(this)
        if (events.isNotEmpty()) {
            val calBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = makeLightCard("#E3F2FD", "#90CAF9")
                setPadding(28, 22, 28, 22)
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 20 }
                layoutParams = params
            }
            val title = TextView(this).apply {
                text = "📅 오늘의 캘린더 일정 (${events.size}건)"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1565C0"))
                setPadding(0, 0, 0, 8)
            }
            calBox.addView(title)
            for (ev in events) {
                val line = TextView(this).apply {
                    text = "• [${ev.timeText}] ${ev.title}"
                    textSize = 13.5f
                    setTextColor(Color.parseColor("#0D47A1"))
                    setPadding(4, 4, 4, 4)
                }
                calBox.addView(line)
            }
            calendarSectionLayout.addView(calBox)
        }

        // 2) 할 일 목록 영역
        todoListLayout.removeAllViews()
        val allTodos = TodoRepository.loadTodos(this)
        val showCompleted = TodoRepository.getShowCompleted(this)
        val visibleTodos = if (showCompleted) allTodos else allTodos.filter { !it.isDone }
        val pendingCount = allTodos.count { !it.isDone }

        summaryCountText.text = "미완료 ${pendingCount}건"

        if (visibleTodos.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "등록된 미완료 할 일이 없습니다.\n위 입력창에서 새로운 할 일을 추가해보세요."
                textSize = 14f
                setTextColor(Color.parseColor("#78909C"))
                gravity = Gravity.CENTER
                setPadding(24, 40, 24, 40)
            }
            todoListLayout.addView(emptyView)
            return
        }

        for (item in visibleTodos) {
            val borderCol = when {
                item.isDone -> "#E2E8F0"
                item.isStarred -> "#FFB300"
                else -> "#CFD8DC"
            }
            val bgCol = if (item.isDone) "#F8FAFC" else "#FFFFFF"

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = makeLightCard(bgCol, borderCol)
                setPadding(20, 20, 20, 20)
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 14 }
                layoutParams = params
            }

            val cb = CheckBox(this).apply {
                isChecked = item.isDone
                buttonTintList = ColorStateList.valueOf(Color.parseColor("#0D3B4C"))
                setOnClickListener {
                    TodoRepository.toggleDone(this@MainActivity, item.id)
                    refreshMainLists()
                }
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 0, 12, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleView = TextView(this).apply {
                text = item.title
                textSize = 15.5f
                setTypeface(null, if (item.isStarred && !item.isDone) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (item.isDone) Color.parseColor("#94A3B8") else Color.parseColor("#1E293B"))
                if (item.isDone) {
                    paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                }
            }
            textCol.addView(titleView)

            if (item.dueDate.isNotBlank()) {
                val (badgeStr, _) = TodoRepository.getDueBadgeText(item.dueDate)
                val dueView = TextView(this).apply {
                    text = badgeStr
                    textSize = 12f
                    setTextColor(Color.parseColor("#64748B"))
                    setPadding(0, 4, 0, 0)
                }
                textCol.addView(dueView)
            }

            val starBtn = Button(this).apply {
                text = if (item.isStarred) "⭐" else "☆"
                textSize = 16f
                background = null
                setPadding(8, 8, 8, 8)
                layoutParams = LinearLayout.LayoutParams(100, ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnClickListener {
                    TodoRepository.toggleStar(this@MainActivity, item.id)
                    refreshMainLists()
                }
            }

            val delBtn = Button(this).apply {
                text = "🗑️"
                textSize = 15f
                background = null
                setPadding(8, 8, 8, 8)
                layoutParams = LinearLayout.LayoutParams(100, ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnClickListener {
                    TodoRepository.deleteTodo(this@MainActivity, item.id)
                    refreshMainLists()
                }
            }

            row.addView(cb)
            row.addView(textCol)
            row.addView(starBtn)
            row.addView(delBtn)
            todoListLayout.addView(row)
        }
    }
}
