package com.zaqizaba.rainassistant.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpSupport {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

class ApiException(message: String) : Exception(message)
class SessionExpiredException(message: String) : Exception(message)

