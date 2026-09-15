package com.example.util

import java.util.concurrent.atomic.AtomicInteger

/**
 * Generates globally unique 64-bit Long IDs for multi-device synchronization.
 * Safe for Room SQLite primary keys and Supabase / Postgres bigint columns.
 */
object IdGenerator {
    private val counter = AtomicInteger(0)

    fun nextId(deviceSlot: Int = 1): Long {
        val timeMs = System.currentTimeMillis()
        val seq = counter.incrementAndGet() % 100
        val slot = (deviceSlot.coerceIn(1, 9))
        return (timeMs * 1000L) + (slot * 100L) + seq
    }
}
