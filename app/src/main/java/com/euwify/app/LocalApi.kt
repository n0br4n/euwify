package com.euwify.app

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the Node service (main.js) on 127.0.0.1.
 * Every call blocks, so only use it from a background thread.
 */
class LocalApi(private val dataDir: File, private val secret: String) {

    class Result(val code: Int, val json: JSONObject)

    /** Port written by main.js, or null while Node is still starting. */
    private fun port(): Int? =
        File(dataDir, "api-port").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()

    fun get(path: String, timeoutMs: Int = 10_000) = request("GET", path, null, timeoutMs)

    fun post(path: String, body: JSONObject = JSONObject(), timeoutMs: Int = 30_000) =
        request("POST", path, body, timeoutMs)

    private fun request(method: String, path: String, body: JSONObject?, timeoutMs: Int): Result {
        val p = port() ?: return Result(0, JSONObject().put("error", "Node is still starting"))
        val conn = URL("http://127.0.0.1:$p$path").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.connectTimeout = 3_000
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("X-Token", secret)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            Result(code, if (text.isBlank()) JSONObject() else JSONObject(text))
        } catch (e: Exception) {
            Result(0, JSONObject().put("error", e.javaClass.simpleName + ": " + (e.message ?: "")))
        } finally {
            conn.disconnect()
        }
    }
}
