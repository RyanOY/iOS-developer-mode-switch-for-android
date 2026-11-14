package com.ryanoy.iosdevmode

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the statically linked go-ios binary that ships inside the APK as
 * `jniLibs/arm64-v8a/libgoios.so` (extracted to nativeLibraryDir at install time).
 *
 * go-ios implements the Apple side of the stack: usbmuxd client, lockdown,
 * SRP/TLS pairing and the AMFI developer-mode service. The USB side is
 * implemented in Kotlin by [com.ryanoy.iosdevmode.usb.UsbMuxDaemon], which the
 * binary reaches through `USBMUXD_SOCKET_ADDRESS=tcp://127.0.0.1:27015`.
 */
object GoIos {

    private const val DEFAULT_MUX_SOCKET = "tcp://127.0.0.1:27015"

    class RunResult(val exitCode: Int, val timedOut: Boolean) {
        val ok: Boolean get() = !timedOut && exitCode == 0
    }

    fun binaryPath(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libgoios.so")

    fun isAvailable(context: Context): Boolean = binaryPath(context).isFile

    /**
     * Runs go-ios with [args] and streams the merged stdout/stderr to [onLine]
     * (invoked from a background thread). Blocks until the process exits or
     * [timeoutSeconds] elapses.
     */
    fun run(
        context: Context,
        args: List<String>,
        timeoutSeconds: Long,
        onLine: (String) -> Unit
    ): RunResult {
        val binary = binaryPath(context)
        if (!binary.isFile) {
            onLine("找不到内嵌的 go-ios 可执行文件：${binary.absolutePath}")
            onLine("请使用 GitHub Actions 构建的 APK（其中已打包 arm64-v8a/libgoios.so）。")
            return RunResult(-1, false)
        }
        if (!binary.canExecute()) {
            runCatching { binary.setExecutable(true) }
        }
        onLine("$ " + (listOf(binary.name) + args).joinToString(" "))

        val command = ArrayList<String>(args.size + 1)
        command.add(binary.absolutePath)
        command.addAll(args)

        val process = try {
            ProcessBuilder(command)
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .apply {
                    environment().apply {
                        put("HOME", context.filesDir.absolutePath)
                        put("TMPDIR", context.cacheDir.absolutePath)
                        put("TMP", context.cacheDir.absolutePath)
                        put("USBMUXD_SOCKET_ADDRESS", DEFAULT_MUX_SOCKET)
                        put("PATH", "/system/bin:/system/xbin:/vendor/bin")
                        put("LANG", "C.UTF-8")
                    }
                }
                .start()
        } catch (t: Throwable) {
            onLine("启动 go-ios 失败：${t.javaClass.simpleName}: ${t.message}")
            return RunResult(-1, false)
        }

        val pump = Thread({
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        onLine(line)
                    }
                }
            } catch (_: Throwable) {
                // Process died; nothing useful to report here.
            }
        }, "goios-output")
        pump.isDaemon = true
        pump.start()

        val finished = try {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!finished) {
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
            pump.join(2_000)
            onLine("命令超时（${timeoutSeconds}s），已终止。")
            return RunResult(-1, true)
        }
        pump.join(3_000)
        val code = try {
            process.exitValue()
        } catch (_: Throwable) {
            -1
        }
        onLine("exit=$code")
        return RunResult(code, false)
    }
}
