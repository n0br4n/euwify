package com.euwify.app

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.security.SecureRandom
import java.util.zip.ZipInputStream

/**
 * Starts the Node service (main.js) once per app process.
 * Node can only be started once per process, so if it ever exits we end the
 * process and let Android restart the foreground service.
 */
object NodeRunner {
    private const val TAG = "EuWiFy"

    init {
        System.loadLibrary("eufyguard")
    }

    @JvmStatic
    private external fun startNodeWithArguments(arguments: Array<String>): Int

    /** Random secret for this run. Node only answers requests that carry it. */
    private val secret: String by lazy {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        bytes.joinToString("") { "%02x".format(it) }
    }

    @Volatile private var started = false
    @Volatile private var apiInstance: LocalApi? = null

    fun api(context: Context): LocalApi =
        apiInstance ?: synchronized(this) {
            apiInstance ?: LocalApi(dataDir(context), secret).also { apiInstance = it }
        }

    private fun dataDir(context: Context) = File(context.applicationContext.filesDir, "eufy-data")

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        Thread({ run(app) }, "node").start()
    }

    private fun run(context: Context) {
        try {
            val project = File(context.filesDir, "nodejs-project")
            val data = dataDir(context).apply { mkdirs() }

            // Unpack node_modules only when the app was installed or updated.
            val installed = context.packageManager
                .getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
            val marker = File(project, ".installed")
            if (!marker.exists() || marker.readText() != installed) {
                Log.i(TAG, "unpacking node_modules (first start after install/update)")
                project.deleteRecursively()
                project.mkdirs()
                unzipAsset(context, "nodejs-project/node_modules.zip", project)
                marker.writeText(installed)
            }
            context.assets.open("nodejs-project/main.js").use { input ->
                File(project, "main.js").outputStream().use { input.copyTo(it) }
            }
            File(data, "api-port").delete()

            Log.i(TAG, "starting Node")
            val code = startNodeWithArguments(
                arrayOf(
                    "node", File(project, "main.js").absolutePath,
                    "--data", data.absolutePath,
                    "--secret", secret,
                )
            )
            Log.e(TAG, "Node exited with code $code, restarting app process")
        } catch (e: Throwable) {
            Log.e(TAG, "Node start failed", e)
        }
        Thread.sleep(3_000)
        Process.killProcess(Process.myPid())
    }

    private fun unzipAsset(context: Context, assetPath: String, targetDir: File) {
        val root = targetDir.canonicalPath + File.separator
        var count = 0
        ZipInputStream(context.assets.open(assetPath).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(targetDir, entry.name)
                if (!out.canonicalPath.startsWith(root)) throw SecurityException("bad zip entry")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                    count++
                }
            }
        }
        Log.i(TAG, "unpacked $count files")
    }
}
