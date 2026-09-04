package com.mrgreenapps.a11ypilot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EventLog {
    private const val MAX_ENTRIES = 200
    private const val TAG = "MetisEvent"
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events.asStateFlow()

    fun append(line: String) {
        val stamped = "${timeFormat.format(Date())}  $line"
        // Keep the in-app timeline for the UI and mirror it to logcat so a device run can be
        // diagnosed after the task leaves the foreground.
        Log.d(TAG, stamped)
        // Atomic read-modify-write via MutableStateFlow.update; a plain read→assign sequence can
        // drop lines under concurrent appends (the accessibility service logs from multiple
        // threads).
        _events.update { current ->
            if (current.size >= MAX_ENTRIES) {
                current.drop(current.size - MAX_ENTRIES + 1) + stamped
            } else {
                current + stamped
            }
        }
    }

    fun clear() {
        _events.value = emptyList()
    }
}
