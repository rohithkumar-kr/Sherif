package com.sherif.backend

import com.sherif.backend.http.SafeLogger
import java.util.Collections

/** Captures request lines so tests can assert who the server *thought* called. */
class RecordingSafeLogger : SafeLogger {

    data class Entry(
        val method: String,
        val path: String,
        val status: Int,
        val userId: String?
    )

    val entries: MutableList<Entry> = Collections.synchronizedList(mutableListOf<Entry>())

    override fun request(
        method: String,
        path: String,
        status: Int,
        durationMillis: Long,
        userId: String?
    ) {
        entries += Entry(method, path, status, userId)
    }

    override fun info(message: String) = Unit

    override fun failure(message: String, throwable: Throwable?) = Unit

    fun usersWhoCalled(): Set<String?> = entries.map { it.userId }.toSet()
}
