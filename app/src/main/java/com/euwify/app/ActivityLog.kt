package com.euwify.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Recent activity": short lines saved on the phone so they survive a restart.
 * The app writes app-activity.log, Node (main.js) writes node-activity.log;
 * the screen shows both merged by time. Never write passwords or codes here.
 */
object ActivityLog {
    private const val MAX_LINES = 300
    private var writes = 0

    private fun dir(context: Context) = File(context.applicationContext.filesDir, "eufy-data")
    private fun appFile(context: Context) = File(dir(context), "app-activity.log")
    private fun nodeFile(context: Context) = File(dir(context), "node-activity.log")

    @Synchronized
    fun add(context: Context, text: String) {
        try {
            val f = appFile(context)
            f.parentFile?.mkdirs()
            f.appendText("${System.currentTimeMillis()}\t${text.replace('\n', ' ')}\n")
            if (++writes % 50 == 0) trim(f)
        } catch (_: Exception) {
            // logging must never break the app
        }
    }

    private fun trim(f: File) {
        val lines = f.readLines()
        if (lines.size > MAX_LINES) f.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
    }

    private fun parse(f: File, tag: String): List<Pair<Long, String>> =
        if (!f.exists()) emptyList() else f.readLines().mapNotNull { line ->
            val tab = line.indexOf('\t')
            val ts = if (tab > 0) line.substring(0, tab).toLongOrNull() else null
            ts?.let { it to tag + line.substring(tab + 1) }
        }

    /** Newest first, as ready-to-show text. */
    fun read(context: Context, limit: Int = 80): String {
        val all = (parse(appFile(context), "") + parse(nodeFile(context), "· "))
            .sortedByDescending { it.first }
            .take(limit)
        if (all.isEmpty()) return "Nothing yet."
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val day = SimpleDateFormat("yyyyMMdd", Locale.US)
        val time = SimpleDateFormat("HH:mm:ss", Locale.US)
        val dateTime = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)
        return all.joinToString("\n") { (ts, text) ->
            val d = Date(ts)
            (if (day.format(d) == today) time.format(d) else dateTime.format(d)) + "  " + text
        }
    }

    @Synchronized
    fun clear(context: Context) {
        appFile(context).delete()
        nodeFile(context).delete()
    }
}
