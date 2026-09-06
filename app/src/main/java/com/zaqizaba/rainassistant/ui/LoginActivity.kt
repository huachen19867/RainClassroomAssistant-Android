package com.zaqizaba.rainassistant.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.zaqizaba.rainassistant.R
import com.zaqizaba.rainassistant.data.SecureStore
import com.zaqizaba.rainassistant.databinding.ActivityLoginBinding
import com.zaqizaba.rainassistant.model.RainNode
import com.zaqizaba.rainassistant.network.RainClassroomApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var secureStore: SecureStore
    private lateinit var node: RainNode
    private var validating = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        secureStore = SecureStore(this)
        node = secureStore.currentNode
        binding.loginStatusText.text = "请完成${node.displayName}登录授权"

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.loginWebView, true)
        }
        binding.loginWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = userAgentString + " RainAssistant/2.0 Android"
        }
        binding.loginWebView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.loginProgress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.loginProgress.visibility = View.GONE
                captureAndValidateCookie(silent = true)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                if (uri.scheme == "http" || uri.scheme == "https") return false
                return runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    binding.loginStatusText.text = "请在微信完成授权，返回后点击检查登录"
                    true
                }.getOrElse {
                    Toast.makeText(this@LoginActivity, "无法打开授权应用：${uri.scheme}", Toast.LENGTH_LONG).show()
                    true
                }
            }
        }

        binding.closeLoginButton.setOnClickListener { finish() }
        binding.checkLoginButton.setOnClickListener { captureAndValidateCookie(silent = false) }
        binding.loginWebView.loadUrl("${node.baseUrl}/web")
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) captureAndValidateCookie(silent = true)
    }

    override fun onDestroy() {
        binding.loginWebView.stopLoading()
        binding.loginWebView.destroy()
        super.onDestroy()
    }

    private fun captureAndValidateCookie(silent: Boolean) {
        if (validating) return
        CookieManager.getInstance().flush()
        val rawCookies = CookieManager.getInstance().getCookie(node.baseUrl).orEmpty()
        val sessionId = rawCookies.split(';')
            .map(String::trim)
            .firstOrNull { it.startsWith("sessionid=") }
            ?.substringAfter('=', "")
            .orEmpty()
        if (sessionId.isBlank()) {
            if (!silent) binding.loginStatusText.text = "尚未取得登录授权，请继续完成登录"
            return
        }

        validating = true
        binding.checkLoginButton.isEnabled = false
        binding.loginStatusText.text = "正在校验登录状态"
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { RainClassroomApi(sessionId, node.baseUrl).validateSession() }
            }
            validating = false
            binding.checkLoginButton.isEnabled = true
            result.onSuccess { user ->
                secureStore.sessionId = sessionId
                secureStore.userName = user.name
                binding.loginStatusText.text = getString(R.string.login_success, user.name)
                setResult(RESULT_OK)
                binding.root.postDelayed({ finish() }, 500)
            }.onFailure { error ->
                binding.loginStatusText.text = error.message ?: "登录状态无效"
            }
        }
    }

}
