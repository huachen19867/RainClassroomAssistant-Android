package com.zaqizaba.rainassistant.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.zaqizaba.rainassistant.data.SecureStore
import com.zaqizaba.rainassistant.databinding.ActivityQrLoginBinding
import com.zaqizaba.rainassistant.model.RainNode
import com.zaqizaba.rainassistant.network.HttpSupport
import com.zaqizaba.rainassistant.network.QrTicketUrl
import com.zaqizaba.rainassistant.network.RainClassroomApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

class QrLoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityQrLoginBinding
    private lateinit var secureStore: SecureStore
    private lateinit var node: RainNode
    private var socket: WebSocket? = null
    private var countdownJob: Job? = null
    private var refreshJob: Job? = null
    private var generation = 0
    private var deadlineMs = 0L
    private var qrRefreshDeadlineMs = 0L
    private var exchanging = false

    private val webLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            setResult(RESULT_OK)
            finish()
        } else {
            startQrLogin(resetDeadline = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        secureStore = SecureStore(this)
        node = secureStore.currentNode

        binding.qrNodeText.text = "当前节点：${node.displayName}"
        binding.closeQrButton.setOnClickListener { finish() }
        binding.refreshQrButton.setOnClickListener { startQrLogin(resetDeadline = true) }
        binding.webLoginButton.setOnClickListener {
            stopQrLogin()
            webLoginLauncher.launch(android.content.Intent(this, LoginActivity::class.java))
        }
        startQrLogin(resetDeadline = true)
    }

    override fun onDestroy() {
        stopQrLogin()
        super.onDestroy()
    }

    private fun startQrLogin(resetDeadline: Boolean, refreshing: Boolean = false) {
        stopQrLogin()
        generation += 1
        exchanging = false
        binding.qrImage.setImageDrawable(null)
        binding.qrProgress.visibility = View.VISIBLE
        binding.qrStatusText.text = if (refreshing) {
            "二维码已到期，正在建立新的登录连接"
        } else {
            "正在连接${node.displayName}"
        }
        binding.refreshQrButton.isEnabled = false
        if (resetDeadline || deadlineMs == 0L) {
            deadlineMs = System.currentTimeMillis() + LOGIN_TIMEOUT_SECONDS * 1_000L
        }
        qrRefreshDeadlineMs = System.currentTimeMillis() + QR_REFRESH_SECONDS * 1_000L
        val activeGeneration = generation
        startCountdown(activeGeneration)

        val request = Request.Builder()
            .url(node.webSocketUrl)
            .header("User-Agent", USER_AGENT)
            .build()
        socket = HttpSupport.client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (activeGeneration != generation) return
                if (!sendLoginRequest(webSocket)) {
                    showFailure(activeGeneration, "二维码请求发送失败，请重试")
                    return
                }
                runOnUiThread {
                    if (activeGeneration == generation && !isFinishing) {
                        binding.qrStatusText.text = "连接成功，正在获取二维码"
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (activeGeneration != generation) return
                val data = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (data.optString("op")) {
                    "requestlogin" -> {
                        val ticketUrl = QrTicketUrl.resolve(
                            topLevelTicket = data.optString("ticket"),
                            nestedTicket = data.optJSONObject("data")?.optString("ticket"),
                            baseUrl = node.baseUrl,
                        )
                        if (ticketUrl == null) {
                            showFailure(activeGeneration, "服务器未返回有效二维码地址，正在等待自动重连")
                        } else {
                            downloadQr(activeGeneration, ticketUrl)
                        }
                    }

                    "loginsuccess" -> {
                        val userId = data.opt("UserID")?.toString().orEmpty()
                        val auth = data.optString("Auth")
                        if (userId.isBlank() || auth.isBlank()) {
                            showFailure(activeGeneration, "扫码成功，但服务器未返回完整授权信息")
                        } else {
                            exchangeLogin(activeGeneration, userId, auth)
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                if (activeGeneration != generation || exchanging) return
                showFailure(activeGeneration, "扫码连接失败：${error.message ?: "网络异常"}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (activeGeneration != generation || exchanging || isFinishing) return
                showFailure(activeGeneration, "扫码连接已断开，请重试")
            }
        })

        refreshJob = lifecycleScope.launch {
            while (isActive && activeGeneration == generation) {
                delay(QR_REFRESH_SECONDS * 1_000L)
                if (!exchanging && System.currentTimeMillis() < deadlineMs) {
                    startQrLogin(resetDeadline = false, refreshing = true)
                    return@launch
                }
            }
        }
    }

    private fun startCountdown(activeGeneration: Int) {
        countdownJob = lifecycleScope.launch {
            while (isActive && activeGeneration == generation) {
                val secondsLeft = ((deadlineMs - System.currentTimeMillis() + 999L) / 1_000L)
                    .coerceAtLeast(0L)
                val qrSecondsLeft = ((qrRefreshDeadlineMs - System.currentTimeMillis() + 999L) / 1_000L)
                    .coerceIn(0L, QR_REFRESH_SECONDS)
                binding.qrCountdownText.text =
                    "总流程剩余 ${secondsLeft} 秒 · 当前二维码 ${qrSecondsLeft} 秒后刷新"
                if (secondsLeft == 0L) {
                    generation += 1
                    refreshJob?.cancel()
                    socket?.cancel()
                    socket = null
                    binding.qrProgress.visibility = View.GONE
                    binding.qrStatusText.text = "扫码已超时，请点击重新获取"
                    binding.refreshQrButton.isEnabled = true
                    break
                }
                delay(1_000L)
            }
        }
    }

    private fun downloadQr(activeGeneration: Int, ticketUrl: String) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    HttpSupport.client.newCall(Request.Builder().url(ticketUrl).get().build()).execute().use { response ->
                        if (!response.isSuccessful) error("HTTP ${response.code}")
                        val bytes = response.body?.bytes() ?: error("二维码内容为空")
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ?: error("二维码图片无法解析")
                    }
                }
            }
            if (activeGeneration != generation || isFinishing) return@launch
            result.onSuccess { bitmap ->
                qrRefreshDeadlineMs = System.currentTimeMillis() + QR_REFRESH_SECONDS * 1_000L
                binding.qrImage.setImageBitmap(bitmap)
                binding.qrProgress.visibility = View.GONE
                binding.qrStatusText.text = "请使用微信扫码授权"
                binding.refreshQrButton.isEnabled = true
            }.onFailure { error ->
                showFailure(activeGeneration, "二维码下载失败：${error.message}")
            }
        }
    }

    private fun exchangeLogin(activeGeneration: Int, userId: String, auth: String) {
        if (exchanging) return
        exchanging = true
        runOnUiThread {
            binding.qrProgress.visibility = View.VISIBLE
            binding.qrStatusText.text = "扫码成功，正在换取并校验登录状态"
            binding.refreshQrButton.isEnabled = false
        }
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val body = JSONObject()
                        .put("UserID", userId)
                        .put("Auth", auth)
                        .toString()
                        .toRequestBody(JSON_MEDIA_TYPE)
                    val request = Request.Builder()
                        .url("${node.baseUrl}/pc/web_login")
                        .header("User-Agent", USER_AGENT)
                        .post(body)
                        .build()
                    val sessionId = HttpSupport.client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("登录接口 HTTP ${response.code}")
                        Cookie.parseAll(response.request.url, response.headers)
                            .firstOrNull { it.name == "sessionid" }
                            ?.value
                            .orEmpty()
                            .ifBlank { error("服务器未返回 sessionid") }
                    }
                    val user = RainClassroomApi(sessionId, node.baseUrl).validateSession()
                    sessionId to user
                }
            }
            if (activeGeneration != generation || isFinishing) return@launch
            result.onSuccess { (sessionId, user) ->
                secureStore.selectedNodeKey = node.key
                secureStore.sessionId = sessionId
                secureStore.userName = user.name
                binding.qrProgress.visibility = View.GONE
                binding.qrStatusText.text = "登录成功：${user.name}"
                setResult(RESULT_OK)
                socket?.close(1000, "login succeeded")
                binding.root.postDelayed({ finish() }, 600L)
            }.onFailure { error ->
                exchanging = false
                showFailure(activeGeneration, "扫码登录失败：${error.message}")
            }
        }
    }

    private fun showFailure(activeGeneration: Int, message: String) {
        runOnUiThread {
            if (activeGeneration != generation || isFinishing || isDestroyed) return@runOnUiThread
            binding.qrProgress.visibility = View.GONE
            binding.qrStatusText.text = message
            binding.refreshQrButton.isEnabled = true
        }
    }

    private fun stopQrLogin() {
        generation += 1
        countdownJob?.cancel()
        refreshJob?.cancel()
        countdownJob = null
        refreshJob = null
        socket?.cancel()
        socket = null
    }

    private fun sendLoginRequest(webSocket: WebSocket): Boolean {
        val payload = JSONObject()
            .put("op", "requestlogin")
            .put("role", "web")
            .put("version", 1.4)
            .put("type", "qrcode")
            .put("from", "web")
        return webSocket.send(payload.toString())
    }

    companion object {
        private const val LOGIN_TIMEOUT_SECONDS = 240L
        private const val QR_REFRESH_SECONDS = 60L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/121 Mobile Safari/537.36"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
