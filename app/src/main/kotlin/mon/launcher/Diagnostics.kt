package mon.launcher

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.app.role.RoleManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Display
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Installs the crash recorder at startup. */
class MonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}

/**
 * Saves crash reports to app storage and detects crash loops. As the home app, a launcher that
 * crashes on start would leave the TV unusable, so a loop shows a recovery screen instead.
 */
object CrashLog {
    private const val KEEP = 10
    private const val LOOP_CRASHES = 3
    private const val LOOP_WINDOW_MS = 3 * 60_000L

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Saved crash reports, newest first. */
    fun reports(context: Context): List<File> =
        dir(context).listFiles().orEmpty().filter { it.name.endsWith(".txt") }.sortedByDescending { it.name }

    /** Time of the most recent crash, or 0. */
    fun lastCrashAt(context: Context): Long = reports(context).firstOrNull()?.let(::timeOf) ?: 0L

    /** True if there were at least [LOOP_CRASHES] crashes within [LOOP_WINDOW_MS] and since the last [acknowledge]. */
    fun inCrashLoop(context: Context): Boolean {
        val since = maxOf(System.currentTimeMillis() - LOOP_WINDOW_MS, prefs(context).getLong(ACKNOWLEDGED, 0L))
        return reports(context).count { timeOf(it) > since } >= LOOP_CRASHES
    }

    /** Stops counting crashes up to now toward a crash loop. */
    fun acknowledge(context: Context) {
        prefs(context).edit().putLong(ACKNOWLEDGED, System.currentTimeMillis()).commit()
    }

    fun clear(context: Context) {
        dir(context).listFiles().orEmpty().forEach { it.delete() }
    }

    private fun record(context: Context, thread: Thread, error: Throwable) {
        val now = System.currentTimeMillis()
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        // Capture the log now: after a restart only the new process's log is readable.
        val text = "Crash at ${stamp(now)} on thread \"${thread.name}\"\n${appVersion(context)}\n\n$trace\n" +
            "--- Log before the crash ---\n${DiagnosticsReport.recentLog()}"
        val dir = dir(context).apply { mkdirs() }
        File(dir, "crash-$now.txt").writeText(text)
        reports(context).drop(KEEP).forEach { it.delete() }
    }

    private fun timeOf(file: File) = file.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull() ?: 0L
    private fun dir(context: Context) = File(context.filesDir, "crashes")
    private fun prefs(context: Context) = context.getSharedPreferences("crashes", Context.MODE_PRIVATE)
    private const val ACKNOWLEDGED = "acknowledged_at"
}

/** Logs a recovered error as a warning so it appears in diagnostics reports. */
fun logProblem(what: String, error: Throwable? = null) {
    Log.w("MonLauncher", what, error)
}

/** App name, version name and version code. */
fun appVersion(context: Context): String {
    val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    return "MonLauncher ${info?.versionName ?: "?"} (${info?.longVersionCode ?: "?"})"
}

/** The version name. */
fun appVersionName(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

/** Builds the diagnostics report: app and device details, recent exits, crashes and recent log. */
object DiagnosticsReport {
    fun build(context: Context): String = buildString {
        appendLine(appVersion(context))
        appendLine("Report made ${stamp(System.currentTimeMillis())}")
        appendLine()
        appendLine("Device: ${deviceName()} (${Build.DEVICE})")
        appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), build ${Build.FINGERPRINT}")
        appendLine("Screen: ${screen(context)}")
        appendLine("TV on for ${TimeUnit.MILLISECONDS.toMinutes(SystemClock.elapsedRealtime())} min (since it last restarted)")
        appendLine("Default home app: ${if (isDefaultHome(context)) "yes" else "no"}")
        val photos = context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED
        appendLine("Photo access (wallpaper imports from Download): ${if (photos) "granted" else "not granted"}")
        appendLine()

        val crashes = CrashLog.reports(context)
        appendLine("=== Crashes (${crashes.size}) ===")
        if (crashes.isEmpty()) appendLine("None recorded.")
        crashes.forEach { appendLine(); appendLine(runCatching { it.readText() }.getOrDefault("(unreadable)")) }
        appendLine()

        appendLine("=== Recent exits ===")
        appendLine(recentExits(context))
        appendLine()

        appendLine("=== Recent log (warnings and errors) ===")
        appendLine(recentLog())
    }

    /** Output mode and UI size. TV devices often render the UI at 1080p and upscale it. */
    private fun screen(context: Context): String {
        val metrics = context.resources.displayMetrics
        val ui = "${metrics.widthPixels}x${metrics.heightPixels} @ ${metrics.densityDpi} dpi"
        val mode = runCatching {
            context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).mode
        }.getOrNull() ?: return ui
        val hz = String.format(Locale.US, "%.2f", mode.refreshRate).trimEnd('0').trimEnd('.')
        return "${mode.physicalWidth}x${mode.physicalHeight} @ $hz Hz (UI drawn at $ui)"
    }

    /**
     * Recent process exits as recorded by the system, including ANRs and low-memory kills,
     * which the crash handler doesn't see. ANRs include the main thread's stack.
     */
    private fun recentExits(context: Context): String = runCatching {
        val exits = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 8)
        if (exits.isEmpty()) return@runCatching "None recorded."
        exits.joinToString("\n") { exit ->
            val line = "${stamp(exit.timestamp)}  ${reasonName(exit.reason)}" +
                (exit.description?.let { "  ($it)" } ?: "") +
                (if (exit.pss > 0) "  memory ${exit.pss / 1024} MB" else "")
            if (exit.reason == ApplicationExitInfo.REASON_ANR) line + "\n" + frozenStack(exit) else line
        }
    }.getOrElse { "(couldn't read: ${it.message})" }

    private fun reasonName(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "FROZE (not responding)"
        ApplicationExitInfo.REASON_CRASH -> "Crashed"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Crashed (native)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Killed: low memory"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Killed: too many resources"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Failed to start"
        ApplicationExitInfo.REASON_SIGNALED -> "Killed by the system"
        ApplicationExitInfo.REASON_EXIT_SELF -> "Exited"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "Stopped (force stop / update)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "Stopped by the user"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "Stopped: permission changed"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "Stopped: something it relied on died"
        ApplicationExitInfo.REASON_FREEZER -> "Frozen in the background"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "Updated"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "Stopped: app state changed"
        ApplicationExitInfo.REASON_OTHER -> "Other"
        else -> "Unknown ($reason)"
    }

    /** Main thread stack from an ANR trace. */
    private fun frozenStack(exit: ApplicationExitInfo): String = runCatching {
        val lines = exit.traceInputStream?.bufferedReader()?.use { it.readLines() } ?: return@runCatching "  (no trace)"
        val start = lines.indexOfFirst { it.startsWith("\"main\"") }
        if (start < 0) return@runCatching "  (main thread not in trace)"
        lines.drop(start).takeWhile { it.isNotBlank() }.take(40).joinToString("\n") { "  $it" }
    }.getOrElse { "  (couldn't read trace: ${it.message})" }

    private fun isDefaultHome(context: Context) = runCatching {
        context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)
    }.getOrDefault(false)

    /**
     * Recent warnings and errors from this process. Lines with install paths are removed, since they
     * would list the user's other installed apps.
     */
    fun recentLog(): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-t", "300", "--pid=${Process.myPid()}", "*:W")
            .redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor(3, TimeUnit.SECONDS)
        lines.filterNot { line -> PRIVATE.any { it in line } || NOISE.any { it in line } }
            .joinToString("\n").ifBlank { "(nothing)" }
    }.getOrElse { "(couldn't read the log: ${it.message})" }

    private val PRIVATE = listOf("/data/app/", "ApkAssets")

    /** Routine warnings logged on every start. */
    private val NOISE = listOf(
        "OnBackInvokedCallback", "enableOnBackInvokedCallback", "Unable to match the desired swap behavior",
        "Not starting debugger", "Codec2Client: query -- param skipped",
    )
}

private fun stamp(millis: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))
