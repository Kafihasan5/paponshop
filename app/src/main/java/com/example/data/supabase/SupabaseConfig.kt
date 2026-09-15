package com.example.data.supabase

import com.example.BuildConfig

object SupabaseConfig {
    // Configured directly from user credentials and injected BuildConfig
    const val DEFAULT_URL = "https://pugxpqncbyujatepuamx.supabase.co"
    const val DEFAULT_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InB1Z3hwcW5jYnl1amF0ZXB1YW14Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODkzODg5NDcsImV4cCI6MjEwNDk2NDk0N30.nQnhHY3ouknGESGkQpbjt982RnNpcK7kdErSaWfBDbQ"

    val url: String
        get() = try {
            val configUrl = BuildConfig.SUPABASE_URL
            if (!configUrl.isNullOrBlank() && !configUrl.contains("your_supabase")) configUrl else DEFAULT_URL
        } catch (_: Throwable) {
            DEFAULT_URL
        }

    val anonKey: String
        get() = try {
            val key = BuildConfig.SUPABASE_ANON_KEY
            if (!key.isNullOrBlank() && !key.contains("your_supabase") && !key.contains("placeholder")) key else DEFAULT_ANON_KEY
        } catch (_: Throwable) {
            DEFAULT_ANON_KEY
        }
}
