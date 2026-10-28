package com.yueyin.tv

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志存储：应用发生未捕获异常时，把线程与堆栈信息写入本地文件。
 * 设置页「查看崩溃日志」可读取最近一次崩溃的日志，便于排查问题。
 * 另外 Android 11+ 还可读取系统记录的历史进程退出原因（ANR / 原生崩溃 / 内存不足被终止等），
 * 这些事件不会触发未捕获异常处理器，需要单独读取。
 */
object CrashLogStore {
    private const val FILE_NAME = "crash_log.txt"
    private const val MAX_BYTES = 512 * 1024
    /** 自我恢复（静默重载）的频率限制存储，避免崩溃死循环导致无限重启。 */
    private const val RECOVERY_PREFS = "crash_recovery"
    private const val RECOVERY_LAST_AT = "last_recovery_at"
    private const val RECOVERY_MIN_INTERVAL_MS = 15_000L

    @Volatile
    private var installed = false

    /** 在 Application.onCreate 中调用，注册全局未捕获异常处理器。 */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            append(context, thread, throwable)
            // Compose 焦点系统的内部缺陷：不让它变成"已停止运行"，静默重载（见下）。
            if (recoverFromComposeFocusCrash(context, throwable)) return@setDefaultUncaughtExceptionHandler
            // 其余异常仍交给系统默认处理器，保持原有崩溃行为（应用退出）
            if (originalHandler != null) {
                originalHandler.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    /**
     * 兜底：Compose 焦点系统的已知内部缺陷
     * `IllegalStateException: visitAncestors called on an unattached node`。
     *
     * 它是在系统 `ViewRootImpl.restoreDefaultFocus` 调进 Compose 的栈里抛出的，
     * 业务层的 `runCatching` 完全拦不到，一旦触发就是整进程死亡
     * （表现为"闪退 + 音乐立即停"）。这类异常只影响焦点管理本身，
     * 不代表播放/数据状态已损坏，所以这里不让它演变成"已停止运行"弹窗：
     * 先把首页重新拉起来，再安静地从干净进程重启。
     *
     * 同时做 15 秒一次的频率限制：如果重启后立刻又崩（说明陷入了死循环），
     * 就不再自我恢复，交回系统默认处理，避免无限重启。
     */
    private fun recoverFromComposeFocusCrash(context: Context, throwable: Throwable): Boolean {
        if (!isComposeFocusCrash(throwable)) return false
        if (!markRecovery(context)) return false
        runCatching {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(intent)
        }
        // 主线程的 Looper 已经退出，本进程无法再承载新界面：
        // 留一点时间让 startActivity 落到 ActivityManager，再杀掉自己，
        // 由系统在干净的新进程里重建 MainActivity。
        runCatching {
            Thread {
                runCatching { Thread.sleep(500) }
                android.os.Process.killProcess(android.os.Process.myPid())
            }.start()
        }
        return true
    }

    private fun isComposeFocusCrash(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            if (current is IllegalStateException &&
                current.message?.contains("visitAncestors called on an unattached node") == true
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun markRecovery(context: Context): Boolean = runCatching {
        val prefs = context.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(RECOVERY_LAST_AT, 0L)
        if (now - last < RECOVERY_MIN_INTERVAL_MS) {
            false
        } else {
            prefs.edit().putLong(RECOVERY_LAST_AT, now).commit()
            true
        }
    }.getOrDefault(false)

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** 追加一条带时间戳的文本记录（如搜索诊断），与崩溃日志写入同一文件，新的记录排在文件末尾。 */
    fun appendEntry(context: Context, text: String) {
        if (text.isBlank()) return
        runCatching {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val sb = StringBuilder("=== 记录时间：").append(time).append(" ===\n").append(text.trimEnd()).append("\n\n")
            val existing = if (file(context).exists()) file(context).readText() else ""
            val merged = existing + sb.toString()
            val trimmed = if (merged.length > MAX_BYTES) merged.takeLast(MAX_BYTES) else merged
            file(context).writeText(trimmed)
        }
    }

    private fun append(context: Context, thread: Thread, throwable: Throwable) {
        // 崩溃时进程即将退出，尽力而为地写入，任何失败都不影响后续默认崩溃流程
        runCatching {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val sb = StringBuilder()
            sb.append("=== 崩溃时间：").append(time).append(" ===\n")
            sb.append("线程：").append(thread.name).append('\n')
            appendThrowable(sb, throwable)
            sb.append('\n')
            val file = file(context)
            val existing = if (file.exists()) file.readText() else ""
            val text = existing + sb.toString()
            val trimmed = if (text.length > MAX_BYTES) text.takeLast(MAX_BYTES) else text
            file.writeText(trimmed)
        }
    }

    private fun appendThrowable(sb: StringBuilder, throwable: Throwable) {
        sb.append(throwable.toString()).append('\n')
        for (frame in throwable.stackTrace) {
            sb.append("\tat ").append(frame).append('\n')
        }
        throwable.cause?.let { cause ->
            sb.append("Caused by: ")
            appendThrowable(sb, cause)
        }
    }

    /** 读取日志（未捕获异常记录 + 系统退出原因，按时间倒序、最新的在前）；没有记录或读取失败时返回 null。 */
    fun read(context: Context): String? {
        val crashPart = runCatching {
            val file = file(context)
            if (!file.exists()) null else reverseEntries(file.readText()).trim().ifEmpty { null }
        }.getOrNull()
        val exitPart = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { exitInfoLog(context) }.getOrNull()
        } else {
            null
        }
        return listOfNotNull(crashPart, exitPart).joinToString("\n\n").ifBlank { null }
    }

    /** 把日志条目按时间倒序（最新的在前），便于设置页直接查看最近一次记录。 */
    private fun reverseEntries(text: String): String {
        val entries = text.split(Regex("(?=^=== )", RegexOption.MULTILINE))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return entries.asReversed().joinToString("\n\n")
    }

    /** 清空崩溃日志。 */
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /** Android 11+：读取系统记录的历史进程退出原因（ANR / 原生崩溃 / 内存不足被终止等）。 */
    private fun exitInfoLog(context: Context): String? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val reasons = am.getHistoricalProcessExitReasons(context.packageName, 0, 3)
        if (reasons.isEmpty()) return null
        val lines = mutableListOf<String>()
        lines += "=== 最近进程退出记录（系统） ==="
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        for (r in reasons) {
            val reason = when (r.reason) {
                ApplicationExitInfo.REASON_ANR -> "ANR（应用无响应，被系统终止）"
                ApplicationExitInfo.REASON_CRASH -> "崩溃（Java 未捕获异常）"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生崩溃（Native Crash）"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足，被系统终止"
                ApplicationExitInfo.REASON_SIGNALED -> "收到终止信号"
                ApplicationExitInfo.REASON_EXIT_SELF -> "应用自行退出"
                else -> "其他（code=${r.reason}）"
            }
            lines += "· ${fmt.format(Date(r.timestamp))} 退出原因：$reason（进程 ${r.pid}）"
            if (r.reason == ApplicationExitInfo.REASON_ANR || r.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                runCatching {
                    r.traceInputStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull()?.let { trace ->
                    if (trace.isNotBlank()) lines += "  系统 Trace 片段：${trace.take(2000)}"
                }
            }
        }
        return lines.joinToString("\n")
    }
}
