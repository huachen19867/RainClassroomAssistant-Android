package com.zaqizaba.rainassistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.zaqizaba.rainassistant.R
import com.zaqizaba.rainassistant.data.SecureStore
import com.zaqizaba.rainassistant.model.WorkPhase
import com.zaqizaba.rainassistant.network.DeepSeekClient
import com.zaqizaba.rainassistant.network.RainClassroomApi
import com.zaqizaba.rainassistant.network.SessionExpiredException
import com.zaqizaba.rainassistant.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class ClassroomService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workers = ConcurrentHashMap<String, LessonWorker>()
    private var monitorJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var secureStore: SecureStore

    override fun onCreate() {
        super.onCreate()
        secureStore = SecureStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopAutomation("用户已停止自动化")
            ACTION_START -> startAutomation()
            null -> {
                if (controlPreferences().getBoolean(KEY_ENABLED, false)) startAutomation() else stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        monitorJob?.cancel()
        workers.values.forEach(LessonWorker::close)
        workers.clear()
        wakeLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun startAutomation() {
        if (monitorJob?.isActive == true) return
        startForeground(NOTIFICATION_ID, buildNotification("正在启动"))

        val sessionId = secureStore.sessionId
        val apiKey = secureStore.effectiveApiKey
        val node = secureStore.currentNode
        val answerDelaySeconds = secureStore.answerDelaySeconds
        when {
            sessionId.isBlank() -> {
                updateStatus(WorkPhase.NEED_LOGIN, "请先在 App 内登录雨课堂")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            apiKey.isBlank() -> {
                updateStatus(WorkPhase.NEED_API, "请在设置中填写 DeepSeek API Key")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
        }

        controlPreferences().edit().putBoolean(KEY_ENABLED, true).apply()
        acquireWakeLock()
        monitorJob = scope.launch {
            val rainApi = RainClassroomApi(sessionId, node.baseUrl)
            val deepSeek = DeepSeekClient(apiKey)
            var userInfo: com.zaqizaba.rainassistant.model.UserInfo? = null
            try {
                while (isActive) {
                    try {
                        if (userInfo == null) {
                            val validatedUser = rainApi.validateSession()
                            deepSeek.testConnection()
                            userInfo = validatedUser
                            secureStore.userName = validatedUser.name
                            updateStatus(
                                WorkPhase.READY,
                                "${node.displayName}登录与答题模型已就绪，答题延迟 ${answerDelaySeconds} 秒",
                            )
                        }

                        if (workers.isEmpty()) {
                            updateStatus(WorkPhase.MONITORING, "正在等待${node.displayName}的课程和题目")
                        } else {
                            updateStatus(WorkPhase.COURSE_FOUND, "正在监听 ${workers.size} 门课程，可以自动处理题目")
                        }
                        val activeLessons = rainApi.getOnLessons()
                        val activeKeys = activeLessons.map { "${it.lessonId}:${it.classroomId}" }.toSet()
                        activeLessons.forEach { lesson ->
                            val key = "${lesson.lessonId}:${lesson.classroomId}"
                            workers.computeIfAbsent(key) {
                                LessonWorker(
                                    lesson = lesson,
                                    userInfo = requireNotNull(userInfo),
                                    rainApi = rainApi,
                                    deepSeekClient = deepSeek,
                                    answerDelaySeconds = answerDelaySeconds,
                                    scope = scope,
                                    report = ::updateStatus,
                                    onFinished = workers::remove,
                                ).also(LessonWorker::start)
                            }
                        }
                        workers.keys.filter { it !in activeKeys }.forEach { staleKey ->
                            workers.remove(staleKey)?.close()
                        }
                        delay(POLL_INTERVAL_MS)
                    } catch (expired: SessionExpiredException) {
                        secureStore.clearLogin(node.key)
                        controlPreferences().edit().putBoolean(KEY_ENABLED, false).apply()
                        updateStatus(WorkPhase.SESSION_EXPIRED, expired.message ?: "登录已失效")
                        break
                    } catch (error: Exception) {
                        updateStatus(WorkPhase.NETWORK_ERROR, error.message ?: "网络访问失败，5 秒后重试")
                        delay(RETRY_INTERVAL_MS)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                workers.values.forEach(LessonWorker::close)
                workers.clear()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopAutomation(detail: String) {
        controlPreferences().edit().putBoolean(KEY_ENABLED, false).apply()
        monitorJob?.cancel()
        monitorJob = null
        workers.values.forEach(LessonWorker::close)
        workers.clear()
        updateStatus(WorkPhase.STOPPED, detail)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateStatus(phase: WorkPhase, detail: String) {
        StatusBus.publish(this, phase, detail)
        getSystemService<NotificationManager>()?.notify(
            NOTIFICATION_ID,
            buildNotification("${phase.title} · $detail"),
        )
    }

    private fun buildNotification(content: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_app)
        .setContentTitle("雨课堂助手正在工作")
        .setContentText(content)
        .setStyle(NotificationCompat.BigTextStyle().bigText(content))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .addAction(
            0,
            "停止",
            PendingIntent.getService(
                this,
                1,
                Intent(this, ClassroomService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "课堂自动化状态",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示自动签到与答题服务的运行状态"
        }
        getSystemService<NotificationManager>()?.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        val manager = getSystemService<PowerManager>() ?: return
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:classroom")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun controlPreferences() = getSharedPreferences(CONTROL_PREFS, Context.MODE_PRIVATE)

    companion object {
        const val ACTION_START = "com.zaqizaba.rainassistant.action.START"
        const val ACTION_STOP = "com.zaqizaba.rainassistant.action.STOP"
        private const val CHANNEL_ID = "classroom_automation"
        private const val NOTIFICATION_ID = 1105
        private const val CONTROL_PREFS = "service_control"
        private const val KEY_ENABLED = "enabled"
        private const val POLL_INTERVAL_MS = 15_000L
        private const val RETRY_INTERVAL_MS = 5_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1_000L

        fun isEnabled(context: Context): Boolean = context
            .getSharedPreferences(CONTROL_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }
}
