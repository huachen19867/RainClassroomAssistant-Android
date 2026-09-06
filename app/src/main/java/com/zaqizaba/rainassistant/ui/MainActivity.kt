package com.zaqizaba.rainassistant.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.zaqizaba.rainassistant.BuildConfig
import com.zaqizaba.rainassistant.R
import com.zaqizaba.rainassistant.data.SecureStore
import com.zaqizaba.rainassistant.databinding.ActivityMainBinding
import com.zaqizaba.rainassistant.model.AnswerDelay
import com.zaqizaba.rainassistant.model.RainNodes
import com.zaqizaba.rainassistant.model.WorkPhase
import com.zaqizaba.rainassistant.model.WorkStatus
import com.zaqizaba.rainassistant.network.DeepSeekClient
import com.zaqizaba.rainassistant.service.ClassroomService
import com.zaqizaba.rainassistant.service.StatusBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var secureStore: SecureStore
    private var receiverRegistered = false

    private val loginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshAccount()
        if (it.resultCode == RESULT_OK) {
            Toast.makeText(this, "雨课堂登录成功", Toast.LENGTH_SHORT).show()
            val needsApi = secureStore.effectiveApiKey.isBlank()
            StatusBus.publish(
                this,
                if (needsApi) WorkPhase.NEED_API else WorkPhase.READY,
                if (needsApi) "登录成功，请在设置中填写 DeepSeek API Key" else "登录成功，点击开始工作",
            )
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderStatus(StatusBus.read(this@MainActivity))
            refreshAccount()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        secureStore = SecureStore(this)

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val showSettings = item.itemId == R.id.navigation_settings
            binding.homeContainer.visibility = if (showSettings) View.GONE else View.VISIBLE
            binding.settingsContainer.visibility = if (showSettings) View.VISIBLE else View.GONE
            true
        }

        binding.loginButton.setOnClickListener {
            loginLauncher.launch(Intent(this, QrLoginActivity::class.java))
        }
        binding.startButton.setOnClickListener { startAutomation() }
        binding.stopButton.setOnClickListener {
            startService(Intent(this, ClassroomService::class.java).setAction(ClassroomService.ACTION_STOP))
        }
        binding.saveApiKeyButton.setOnClickListener { saveUserApiKey() }
        binding.clearApiKeyButton.setOnClickListener {
            secureStore.clearCustomApiKey()
            binding.apiKeyInput.text?.clear()
            refreshApiKeyState()
            renderStatus(StatusBus.read(this))
            val message = if (secureStore.apiKeySource == SecureStore.ApiKeySource.BUILT_IN) {
                "已恢复使用内置 API Key"
            } else {
                "已清除用户 API Key"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
        binding.testApiButton.setOnClickListener { testApiKey() }
        binding.saveAnswerDelayButton.setOnClickListener { saveAnswerDelay() }
        binding.batterySettingsButton.setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.onFailure {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }

        requestNotificationPermission()
        setupNodeSettings()
        binding.answerDelayInput.setText(secureStore.answerDelaySeconds.toString())
        refreshAccount()
        refreshApiKeyState()
        renderStatus(StatusBus.read(this))
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                statusReceiver,
                IntentFilter(StatusBus.ACTION_STATUS_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        refreshAccount()
        renderStatus(StatusBus.read(this))
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun startAutomation() {
        when {
            secureStore.sessionId.isBlank() -> {
                StatusBus.publish(this, WorkPhase.NEED_LOGIN, "请先登录雨课堂")
                loginLauncher.launch(Intent(this, QrLoginActivity::class.java))
            }
            secureStore.effectiveApiKey.isBlank() -> {
                StatusBus.publish(this, WorkPhase.NEED_API, "请在设置中填写 DeepSeek API Key")
                binding.bottomNavigation.selectedItemId = R.id.navigation_settings
            }
            else -> ContextCompat.startForegroundService(
                this,
                Intent(this, ClassroomService::class.java).setAction(ClassroomService.ACTION_START),
            )
        }
    }

    private fun saveUserApiKey() {
        val value = binding.apiKeyInput.text?.toString().orEmpty().trim()
        if (value.isBlank()) {
            Toast.makeText(this, "请输入 API Key", Toast.LENGTH_SHORT).show()
            return
        }
        secureStore.saveCustomApiKey(value)
        binding.apiKeyInput.text?.clear()
        refreshApiKeyState()
        renderStatus(StatusBus.read(this))
        Toast.makeText(this, "用户 API Key 已加密保存", Toast.LENGTH_SHORT).show()
    }

    private fun setupNodeSettings() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            RainNodes.all.map { it.displayName },
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.nodeSpinner.adapter = adapter
        binding.nodeSpinner.setSelection(
            RainNodes.all.indexOfFirst { it.key == secureStore.selectedNodeKey }.coerceAtLeast(0),
            false,
        )
        binding.nodeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = RainNodes.all.getOrNull(position) ?: return
                if (selected.key == secureStore.selectedNodeKey) return
                val wasRunning = ClassroomService.isEnabled(this@MainActivity)
                if (wasRunning) {
                    startService(
                        Intent(this@MainActivity, ClassroomService::class.java)
                            .setAction(ClassroomService.ACTION_STOP),
                    )
                }
                secureStore.selectedNodeKey = selected.key
                refreshAccount()
                val detail = when {
                    secureStore.sessionId.isBlank() -> "已切换到${selected.displayName}，请先完成扫码登录"
                    secureStore.effectiveApiKey.isBlank() -> "已切换到${selected.displayName}，请配置 DeepSeek API Key"
                    else -> "已切换到${selected.displayName}，登录态已就绪"
                }
                val phase = when {
                    secureStore.sessionId.isBlank() -> WorkPhase.NEED_LOGIN
                    secureStore.effectiveApiKey.isBlank() -> WorkPhase.NEED_API
                    else -> WorkPhase.READY
                }
                StatusBus.publish(
                    this@MainActivity,
                    phase,
                    detail,
                )
                renderStatus(StatusBus.read(this@MainActivity))
                Toast.makeText(
                    this@MainActivity,
                    if (wasRunning) "$detail；后台服务已停止" else detail,
                    Toast.LENGTH_LONG,
                ).show()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun saveAnswerDelay() {
        val raw = binding.answerDelayInput.text?.toString()?.trim().orEmpty()
        val parsed = raw.toIntOrNull()
        if (parsed == null) {
            Toast.makeText(this, "请输入 0 到 60 的整数秒数", Toast.LENGTH_SHORT).show()
            return
        }
        val normalized = AnswerDelay.normalize(parsed)
        secureStore.answerDelaySeconds = normalized
        binding.answerDelayInput.setText(normalized.toString())
        val wasRunning = ClassroomService.isEnabled(this)
        if (wasRunning) {
            startService(Intent(this, ClassroomService::class.java).setAction(ClassroomService.ACTION_STOP))
        }
        Toast.makeText(
            this,
            if (wasRunning) "答题延迟已设为 ${normalized} 秒；请重新开始工作使其生效" else "答题延迟已设为 ${normalized} 秒",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun testApiKey() {
        val pendingValue = binding.apiKeyInput.text?.toString().orEmpty().trim()
        val key = pendingValue.ifBlank { secureStore.effectiveApiKey }
        if (key.isBlank()) {
            Toast.makeText(this, "请先填写 API Key", Toast.LENGTH_SHORT).show()
            return
        }
        binding.testApiButton.isEnabled = false
        binding.testApiButton.text = "测试中"
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { DeepSeekClient(key).testConnection() }
            }
            binding.testApiButton.isEnabled = true
            binding.testApiButton.text = "测试"
            result.onSuccess { model ->
                Toast.makeText(this@MainActivity, "连接成功：$model", Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                Toast.makeText(this@MainActivity, error.message ?: "连接失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshAccount() {
        val sessionId = secureStore.sessionId
        val node = secureStore.currentNode
        binding.nodeText.text = "节点：${node.displayName}（${node.host}）"
        binding.loginButton.text = if (sessionId.isBlank()) "限时扫码登录" else "重新扫码登录"
        binding.accountText.text = if (sessionId.isBlank()) {
            "账号：未登录"
        } else {
            "账号：${secureStore.userName.ifBlank { "已保存登录态" }}"
        }
    }

    private fun refreshApiKeyState() {
        val text = when (secureStore.apiKeySource) {
            SecureStore.ApiKeySource.USER -> "当前：用户 API Key（本机加密存储）"
            SecureStore.ApiKeySource.BUILT_IN -> "当前：${BuildConfig.DEFAULT_DEEPSEEK_API_LABEL}"
            SecureStore.ApiKeySource.MISSING -> "当前：未配置"
        }
        binding.apiKeySourceText.text = text
        binding.modelReadyText.text = when (secureStore.apiKeySource) {
            SecureStore.ApiKeySource.MISSING -> "答题模型：未配置 API Key"
            else -> "答题模型：已配置，可调用视觉模型"
        }
    }

    private fun renderStatus(status: WorkStatus) {
        binding.statusTitle.text = status.phase.title
        binding.statusDetail.text = status.detail
        binding.recentLogText.text = if (status.updatedAt > 0L) {
            "${TIME_FORMAT.format(Date(status.updatedAt))}  ${status.detail}"
        } else {
            status.detail
        }
        binding.statusDot.setTextColor(
            when (status.phase) {
                WorkPhase.READY,
                WorkPhase.MONITORING,
                WorkPhase.COURSE_FOUND,
                WorkPhase.CHECKING_IN,
                WorkPhase.WAITING_TO_SOLVE,
                WorkPhase.SOLVING,
                WorkPhase.ANSWERED,
                -> ContextCompat.getColor(this, R.color.success)
                WorkPhase.NEED_LOGIN,
                WorkPhase.NEED_API,
                -> Color.parseColor("#B86B00")
                WorkPhase.NETWORK_ERROR,
                WorkPhase.SESSION_EXPIRED,
                -> Color.parseColor("#C13737")
                WorkPhase.STOPPED -> Color.parseColor("#66717F")
            },
        )
        val serviceEnabled = ClassroomService.isEnabled(this)
        binding.answerCapabilityText.text = when {
            !serviceEnabled -> "自动答题：当前不可用（请点击开始工作）"
            status.phase.canAnswer -> "自动答题：可以，App 正在工作"
            else -> "自动答题：暂不可用（${status.phase.title}）"
        }
        binding.answerCapabilityText.setTextColor(
            when {
                serviceEnabled && status.phase.canAnswer -> Color.parseColor("#168A4B")
                status.phase == WorkPhase.NETWORK_ERROR || status.phase == WorkPhase.SESSION_EXPIRED -> {
                    Color.parseColor("#C13737")
                }
                else -> Color.parseColor("#66717F")
            },
        )
        binding.startButton.isEnabled = !serviceEnabled
        binding.stopButton.isEnabled = serviceEnabled
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    }
}
