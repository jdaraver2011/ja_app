package com.example.ja

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Share metadata connections across catalog, title, search and person repositories. */
object JaTmdbHttp {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
}
