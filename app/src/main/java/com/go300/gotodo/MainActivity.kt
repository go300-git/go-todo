package com.go300.gotodo

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.KeyguardManager
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
    val dueDate: String,
    val isDone: Boolean,
    val createdAt: Long
)

// 2. 캘린더 일정 데이터 구조 (향후 7일 표시용)
data class CalendarEventItem(
    val title: String,
    val scheduleLabel: String, // 예: "오늘 14:00~15:00", "11/15(일) 10:00 (D-3)"
    val startMillis: Long,
    val isToday: Boolean
)

// 3. 할 일 저장소 및 1주일 내 일정(공휴일·절기 제외) 연동 매니저
object TodoRepository {
    private const val PREF_NAME = "gotodo_prefs"
    private const val KEY_TODOS = "todos_json"
    private const val KEY_SERVICE_ENABLED = "service_enabled"
    private const val KEY_POPUP_ON_SCREEN_ON = "popup_on_screen_on"
    private const val KEY_SHOW_CALENDAR = "show_calendar"
    private const val KEY_SHOW_COMPLETED = "show_completed"
    private const val KEY_HIDE_WHEN_EMPTY = "hide_when_empty"

    // 24절기 및 기본 공휴일/기념일 자동 필터링 키워드
    private val SOLAR_TERMS_AND_HOLIDAYS = setOf(
        "입춘", "우수", "경칩", "춘분", "청명", "곡우",
        "입하", "소만", "망종", "하지", "소서", "대서",
        "입추", "처서", "백로", "추분", "한로", "상강",
        "입동", "소설", "대설", "동지", "소한", "대한",
        "신정", "설날", "설날 연휴", "삼일절", "어린이날", "부처님오신날", "석가탄신일",
        "현충일", "제헌절", "광복절", "추석", "추석 연휴", "개천절", "한글날",
        "기독탄신일", "성탄절", "크리스마스", "대체공휴일", "대체휴일", "임시공휴일",
        "초복", "중복", "말복", "정월대보름", "단오", "칠석", "한식", "동지"
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getServiceEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SERVICE_ENABLED, true)

    fun setServiceEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()

    // false = 3단계(기본 잠금화면 알림 목록 표시), true = 4단계(전체 다크 보드 바로 팝업)
    fun getPopupOnScreenOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_POPUP_ON_SCREEN_ON, false)

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
            val diffDays = ((dueTime - todayCal.timeInMillis) / 86_400_000L).toInt()

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

    // 공휴일/절기 달력인지 판별하는 3단계 필터
    private fun isHolidayOrSolarTermEvent(
        title: String,
        allDay: Boolean,
        accessLevel: Int,
        calName: String,
        ownerAccount: String
    ): Boolean {
        // 1단계: 쓰기 권한이 없는 읽기 전용 구독 달력(공휴일·절기 달력) 제외
        if (accessLevel < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
            return true
        }

        // 2단계: 달력 계정 주소나 달력 이름에 휴일/절기/음력이 포함된 경우 제외
        val lowerOwner = ownerAccount.lowercase(Locale.ROOT)
        val lowerCalName = calName.lowercase(Locale.ROOT)
        if (lowerOwner.contains("holiday") ||
            lowerOwner.contains("group.v.calendar.google.com") ||
            lowerCalName.contains("휴일") ||
            lowerCalName.contains("공휴일") ||
            lowerCalName.contains("holiday") ||
            lowerCalName.contains("절기") ||
            lowerCalName.contains("음력") ||
            lowerCalName.contains("기념일")
        ) {
            return true
        }

        // 3단계: 로컬 달력에 직접 박힌 24절기(입동, 소설 등) 및 법정공휴일 명칭 제외
        val cleanTitle = title.trim().replace(Regex("\\s*\\(.*?\\)\\s*$"), "")
        if (allDay && SOLAR_TERMS_AND_HOLIDAYS.contains(cleanTitle)) {
            return true
        }

        return false
    }

    // 현재 시각 기준 '지나간 일정 제외 + 향후 7일(1주일) 이내의 내가 등록한 일정' 조회
    fun loadUpcomingWeekUserEvents(context: Context): List<CalendarEventItem> {
        if (!getShowCalendar(context)) return emptyList()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        val results = mutableListOf<CalendarEventItem>()
        try {
            val nowMs = System.currentTimeMillis()

            val todayStartCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val weekEndCal = (todayStartCal.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, 7)
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }

            val todayStartMs = todayStartCal.timeInMillis
            val weekEndMs = weekEndCal.timeInMillis

            val localDateSdf = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
            val utcDateSdf = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val shortDateSdf = SimpleDateFormat("M/d(E)", Locale.KOREA)
            val timeFormat = SimpleDateFormat("HH:mm", Locale.KOREA)

            // 종일 일정(UTC 자정 기준) 오차 방지를 위해 전후 1일 여유를 두고 조회
            val queryStart = todayStartMs - 86_400_000L
            val queryEnd = weekEndMs + 86_400_000L

            val projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.OWNER_ACCOUNT
            )

            val cursor = CalendarContract.Instances.query(
                context.contentResolver,
                projection,
                queryStart,
                queryEnd
            )

            cursor?.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(0)?.trim() ?: ""
                    if (title.isEmpty()) continue

                    val begin = c.getLong(1)
                    val end = c.getLong(2)
                    val allDay = c.getInt(3) == 1
                    val accessLevel = c.getInt(4)
                    val calName = c.getString(5) ?: ""
                    val ownerAccount = c.getString(6) ?: ""

                    // 공휴일·24절기 필터링
                    if (isHolidayOrSolarTermEvent(title, allDay, accessLevel, calName, ownerAccount)) {
                        continue
                    }

                    if (allDay) {
                        // 종일 일정은 UTC 날짜 문자열을 로컬 자정 시간으로 변환하여 날짜 비교
                        val eventDateStr = utcDateSdf.format(Date(begin))
                        val eventLocalStartMs = localDateSdf.parse(eventDateStr)?.time ?: continue
                        val diffDays = ((eventLocalStartMs - todayStartMs) / 86_400_000L).toInt()

                        // 어제 이전(지나간 날짜)이거나 7일 초과 일정은 제외
                        if (diffDays < 0 || diffDays > 7) continue

                        val dayLabel = when (diffDays) {
                            0 -> "오늘 종일"
                            1 -> "내일 종일"
                            else -> "${shortDateSdf.format(Date(eventLocalStartMs))} 종일 (D-$diffDays)"
                        }

                        results.add(
                            CalendarEventItem(
                                title = title,
                                scheduleLabel = dayLabel,
                                startMillis = eventLocalStartMs,
                                isToday = (diffDays == 0)
                            )
                        )
                    } else {
                        // 시간 지정 일정: 종료 시각이 현재 시각(nowMs)보다 과거이면 이미 지나간 일정이므로 제외!
                        if (end < nowMs || begin > weekEndMs) continue

                        val eventDayCal = Calendar.getInstance().apply {
                            timeInMillis = begin
                            set(Calendar.HOUR_OF_DAY, 0)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        val diffDays = ((eventDayCal.timeInMillis - todayStartMs) / 86_400_000L).toInt()
                        if (diffDays > 7) continue

                        val startStr = timeFormat.format(Date(begin))
                        val endStr = timeFormat.format(Date(end))

                        val dayPrefix = when {
                            diffDays <= 0 -> "오늘 $startStr~$endStr"
                            diffDays == 1 -> "내일 $startStr~$endStr"
                            else -> "${shortDateSdf.format(Date(begin))} $startStr (D-$diffDays)"
                        }

                        results.add(
                            CalendarEventItem(
                                title = title,
                                scheduleLabel = dayPrefix,
                                startMillis = begin,
                                isToday = (diffDays <= 0)
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        return results
            .distinctBy { "${it.title}_${it.scheduleLabel}" }
            .sortedBy { it.startMillis }
    }
}

// 4. 잠금화면(화면 2) 알림 목록 유지 및 화면 켜짐 감지 서비스
class TodoLockService : Service() {

    companion object {
        // LG V510N 잠금화면에서 작은 아이콘으로 축소되지 않고 목록이 펼쳐지도록 기본 중요도(무음) 채널 사용
        const val CHANNEL_ID = "gotodo_lockscreen_board_v2"
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
                // 화면이 켜질 때마다 지나간 일정은 빼고 최신 목록으로 알림창 갱신
                updateNotification()

                // 3단계 모드(전체 다크 보드 팝업 OFF)일 때는 기본 잠금화면의 알림 목록만 보여주고 끝냄
                if (!TodoRepository.getPopupOnScreenOn(context)) return

                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (am != null && (am.mode == AudioManager.MODE_RINGTONE ||
                            am.mode == AudioManager.MODE_IN_CALL ||
                            am.mode == AudioManager.MODE_IN_COMMUNICATION)
                ) {
                    return
                }

                val pendingTodos = TodoRepository.loadTodos(context).filter { !it.isDone }
                val upcomingEvents = TodoRepository.loadUpcomingWeekUserEvents(context)
                if (TodoRepository.getHideWhenEmpty(context) &&
                    pendingTodos.isEmpty() &&
                    upcomingEvents.isEmpty()
                ) {
                    return
                }

                // 4단계 모드(전체 다크 보드 팝업 ON)일 때만 다크 보드 실행
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
                "고할일 잠금화면 목록 표시 (무음)",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "잠금화면(화면 2)에 할 일과 1주일 내 일정을 소리 없이 펼쳐서 표시합니다."
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildLockScreenNotification(): Notification {
        val pendingTodos = TodoRepository.loadTodos(this).filter { !it.isDone }
        val upcomingEvents = TodoRepository.loadUpcomingWeekUserEvents(this)

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
            pendingTodos.isEmpty() && upcomingEvents.isEmpty() -> "✅ 남은 할 일 및 주간 일정이 없습니다"
            upcomingEvents.isNotEmpty() -> "📋 할 일 ${pendingTodos.size}건 · 📅 7일 내 일정 ${upcomingEvents.size}건"
            else -> "📋 미완료 할 일 ${pendingTodos.size}건"
        }

        val summaryLine = when {
            pendingTodos.isNotEmpty() -> {
                val first = pendingTodos.first()
                val star = if (first.isStarred) "⭐ " else "☐ "
                "$star${first.title}" + if (pendingTodos.size > 1) " 외 ${pendingTodos.size - 1}건" else ""
            }
            upcomingEvents.isNotEmpty() -> {
                val firstEv = upcomingEvents.first()
                "📅 [${firstEv.scheduleLabel}] ${firstEv.title}"
            }
            else -> "새로운 할 일을 등록하려면 터치하세요."
        }

        val inboxStyle = NotificationCompat.InboxStyle().setBigContentTitle(title)
        for (todo in pendingTodos.take(4)) {
            val prefix = if (todo.isStarred) "⭐ " else "☐ "
            val dueTag = if (todo.dueDate.isNotEmpty()) " (${todo.dueDate})" else ""
            inboxStyle.addLine("$prefix${todo.title}$dueTag")
        }
        for (ev in upcomingEvents.take(3)) {
            inboxStyle.addLine("📅 [${ev.scheduleLabel}] ${ev.title}")
        }

        val extraCount = (pendingTodos.size - 4).coerceAtLeast(0) + (upcomingEvents.size - 3).coerceAtLeast(0)
        if (extraCount > 0) {
            inboxStyle.setSummaryText("+ 외 ${extraCount}건 더보기 (터치하여 전체 보드 열기)")
        } else {
            inboxStyle.setSummaryText("고할일 · 터치하면 큰 화면 보드가 열립니다")
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(title)
            .setContentText(summaryLine)
            .setStyle(inboxStyle)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(true)
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

// 6. 전체 다크 보드 액티비티 (4단계 모드 또는 알림 터치 시 표시)
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
            setBackgroundColor(Color.parseColor("#080B11"))
            setPadding(40, 48, 40, 36)
        }

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

        // 하단 버튼 2개: [잠금화면 닫기] / [바로 잠금해제(홈 화면 직행)]
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 0)
        }

        val closeBtn = Button(this).apply {
            text = "보드 닫기"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#CBD5E1"))
            background = makeDarkCard("#1A2332", "#334155")
            setPadding(20, 30, 20, 30)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = 12
            }
            setOnClickListener {
                finish()
            }
        }

        val unlockDirectBtn = Button(this).apply {
            text = "🔓 바로 잠금해제"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = makeDarkCard("#0D3B4C", "#4DB6AC")
            setPadding(20, 30, 20, 30)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = 12
            }
            setOnClickListener {
                val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && km != null && km.isKeyguardLocked) {
                    km.requestDismissKeyguard(this@LockBoardActivity, object : KeyguardManager.KeyguardDismissCallback() {
                        override fun onDismissSucceeded() {
                            finish()
                        }
                    })
                } else {
                    finish()
                }
            }
        }

        bottomRow.addView(closeBtn)
        bottomRow.addView(unlockDirectBtn)

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
        // 1) 향후 7일 이내의 내 캘린더 일정 표시 (공휴일·절기 및 지나간 일정 제외)
        val events = TodoRepository.loadUpcomingWeekUserEvents(this)
        calendarContainer.removeAllViews()
        if (events.isEmpty()) {
            calendarContainer.visibility = View.GONE
        } else {
            calendarContainer.visibility = View.VISIBLE
            val calTitle = TextView(this).apply {
                text = "📅 다가오는 1주일 내 일정 (${events.size}건)"
                textSize = 14.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#64B5F6"))
                setPadding(0, 0, 0, 14)
            }
            calendarContainer.addView(calTitle)

            for (ev in events) {
                val colorHex = if (ev.isToday) "#FFE082" else "#E3F2FD"
                val evText = TextView(this).apply {
                    text = "• [${ev.scheduleLabel}]  ${ev.title}"
                    textSize = 14.5f
                    setTypeface(null, if (ev.isToday) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(Color.parseColor(colorHex))
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

// 7. 화면 1: 할 일 추가 및 잠금화면 3단계/4단계 설정 메인 액티비티
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
            text = "💡 4단계(전체 다크 보드 자동 팝업) 모드를 쓰시려면?"
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E65100"))
        }

        val bannerDesc = TextView(this).apply {
            text = "3단계(기본 잠금화면 알림 목록 표시)는 이 권한 없이도 바로 작동합니다. 화면을 켤 때 전체 다크 보드가 먼저 뜨는 4단계 모드를 원하실 때만 허용해주세요."
            textSize = 13f
            setTextColor(Color.parseColor("#5D4037"))
            setPadding(0, 8, 0, 16)
        }

        val grantOverlayBtn = Button(this).apply {
            text = "다른 앱 위에 표시 권한 설정 (선택)"
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

        // [카드 1] 새 할 일 등록
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

        // [카드 2] 할 일 & 향후 1주일 내 일정 목록
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
            text = "📋 할 일 & 향후 1주일 일정"
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

        // [카드 3] 잠금화면 3단계 / 4단계 표시 설정
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
            text = "⚙️ 잠금화면(화면 2) 표시 방식 설정"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0D3B4C"))
            setPadding(0, 0, 0, 16)
        }

        val previewBoardBtn = Button(this).apply {
            text = "🔍 전체 다크 보드 미리보기"
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
            text = "🔒 잠금화면 할 일 표시 기능 켜기 (기본 3단계)"
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
            text = "📱 4단계 모드: 화면 켤 때 전체 다크 보드 먼저 띄우기\n(끄면 3단계: 기본 잠금화면 알림 목록에만 표시)"
            textSize = 13.5f
            isChecked = TodoRepository.getPopupOnScreenOn(this@MainActivity)
            setPadding(0, 14, 0, 14)
            setOnCheckedChangeListener { _, isChecked ->
                TodoRepository.setPopupOnScreenOn(this@MainActivity, isChecked)
                updateOverlayPermissionBanner()
            }
        }

        val calendarSwitch = Switch(this).apply {
            text = "📅 향후 1주일 내 캘린더 일정 함께 표시 (공휴일·절기 제외)"
            textSize = 13.5f
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
            textSize = 14f
            isChecked = TodoRepository.getShowCompleted(this@MainActivity)
            setPadding(0, 14, 0, 14)
            setOnCheckedChangeListener { _, isChecked ->
                TodoRepository.setShowCompleted(this@MainActivity, isChecked)
                refreshMainLists()
            }
        }

        val hideEmptySwitch = Switch(this).apply {
            text = "🙈 할 일·주간 일정이 0건일 땐 자동 숨김"
            textSize = 14f
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
        val want4Stage = TodoRepository.getPopupOnScreenOn(this)
        overlayPermissionBanner.visibility = if (!canDraw && want4Stage) View.VISIBLE else View.GONE
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
        // 1) 향후 1주일 내 캘린더 일정 영역 (지나간 일정 및 공휴일·절기 제외)
        calendarSectionLayout.removeAllViews()
        val events = TodoRepository.loadUpcomingWeekUserEvents(this)
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
                text = "📅 다가오는 1주일 내 일정 (${events.size}건 · 공휴일/절기 제외)"
                textSize = 13.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1565C0"))
                setPadding(0, 0, 0, 8)
            }
            calBox.addView(title)
            for (ev in events) {
                val line = TextView(this).apply {
                    text = "• [${ev.scheduleLabel}]  ${ev.title}"
                    textSize = 13.5f
                    setTypeface(null, if (ev.isToday) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(Color.parseColor(if (ev.isToday) "#0D47A1" else "#1E293B"))
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
