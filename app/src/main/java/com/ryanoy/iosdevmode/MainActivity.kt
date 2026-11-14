package com.ryanoy.iosdevmode

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import com.ryanoy.iosdevmode.usb.AppleUsbDevice
import com.ryanoy.iosdevmode.usb.UsbMuxDaemon
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single screen app:
 *
 *   1. ask for USB permission for the attached Apple device
 *   2. start the in-app usbmuxd bridge (Kotlin, 127.0.0.1:27015)
 *   3. run the bundled go-ios binary to pair with the iPad
 *   4. run `devmode reveal` / `devmode enable` to surface or turn on
 *      Settings -> Privacy & Security -> Developer Mode
 */
class MainActivity : Activity() {

    private companion object {
        const val ACTION_USB_PERMISSION = "com.ryanoy.iosdevmode.USB_PERMISSION"
        const val PAIR_TIMEOUT_SECONDS = 240L
        const val DEVMODE_TIMEOUT_SECONDS = 180L
        const val ENABLE_TIMEOUT_SECONDS = 300L
        const val PAIR_ATTEMPTS = 4
        const val PAIR_RETRY_DELAY_MS = 8_000L
    }

    private lateinit var usbManager: UsbManager
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var btnConnect: Button
    private lateinit var btnReveal: Button
    private lateinit var btnEnable: Button
    private lateinit var btnQuery: Button
    private lateinit var btnReset: Button

    private var daemon: UsbMuxDaemon? = null
    private var receiversRegistered = false

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val pendingAutoConnect = AtomicBoolean(false)
    private val logBuffer = StringBuilder()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) {
                log("已获得 USB 访问权限")
                startConnectFlow()
            } else {
                log("USB 权限被拒绝。请重新插拔 iPad 后再试。")
                setStatus("USB 权限被拒绝")
            }
        }
    }

    private val usbStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    log("检测到 USB 设备插入")
                    if (busy.get()) pendingAutoConnect.set(true)
                    requestUsbPermission(autoStart = true)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    log("USB 设备已断开")
                    setStatus("iPad 已断开")
                    stopDaemon()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        tvStatus = findViewById(R.id.tv_status)
        tvLog = findViewById(R.id.tv_log)
        logScroll = findViewById(R.id.log_scroll)
        btnConnect = findViewById(R.id.btn_connect)
        btnReveal = findViewById(R.id.btn_reveal)
        btnEnable = findViewById(R.id.btn_enable)
        btnQuery = findViewById(R.id.btn_query)
        btnReset = findViewById(R.id.btn_reset)

        btnConnect.setOnClickListener { startConnectFlow() }
        btnReveal.setOnClickListener { revealOnly() }
        btnEnable.setOnClickListener { enableDevMode() }
        btnQuery.setOnClickListener { queryDevMode() }
        btnReset.setOnClickListener { resetPairing() }

        registerReceivers()

        log("iPad 开发者模式开关 · Android 版")
        log("原理：App 内实现 usbmuxd + 运行 go-ios，配对后调用 AMFI 服务。")
        checkAbi()
        if (!GoIos.isAvailable(this)) {
            log("警告：APK 中缺少 libgoios.so，所有设备命令都会失败。")
        }
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            log("由 USB 插入事件启动")
        }
        requestUsbPermission(autoStart = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            log("检测到 USB 设备插入")
            requestUsbPermission(autoStart = true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (receiversRegistered) {
            runCatching { unregisterReceiver(usbPermissionReceiver) }
            runCatching { unregisterReceiver(usbStateReceiver) }
            receiversRegistered = false
        }
        stopDaemon()
        worker.shutdownNow()
    }

    // ---- UI glue ----------------------------------------------------------

    private fun setStatus(text: String) {
        ui.post {
            if (::tvStatus.isInitialized) tvStatus.text = text
        }
    }

    private fun log(message: String) {
        ui.post {
            if (!::tvLog.isInitialized) return@post
            logBuffer.append('[')
                .append(timeFormat.format(Date()))
                .append("] ")
                .append(message)
                .append('\n')
            if (logBuffer.length > 200_000) {
                logBuffer.delete(0, logBuffer.length - 150_000)
            }
            tvLog.text = logBuffer
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        if (!::btnConnect.isInitialized) return
        btnConnect.isEnabled = enabled
        btnReveal.isEnabled = enabled
        btnEnable.isEnabled = enabled
        btnQuery.isEnabled = enabled
        btnReset.isEnabled = enabled
    }

    private fun runExclusive(label: String, block: () -> Unit) {
        if (!busy.compareAndSet(false, true)) {
            log("“$label”正在进行中，待当前任务结束后自动继续…")
            return
        }
        pendingAutoConnect.set(false)
        setButtonsEnabled(false)
        worker.execute {
            try {
                block()
            } catch (t: Throwable) {
                log("$label 出错：${t.javaClass.simpleName}: ${t.message}")
            } finally {
                busy.set(false)
                if (pendingAutoConnect.compareAndSet(true, false)) {
                    log("检测到 iPad 重新接入，自动继续…")
                    ui.post { startConnectFlow() }
                }
                ui.post { setButtonsEnabled(true) }
            }
        }
    }

    // ---- USB --------------------------------------------------------------

    private fun registerReceivers() {
        val permissionFilter = IntentFilter(ACTION_USB_PERMISSION)
        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbPermissionReceiver, permissionFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(usbStateReceiver, usbFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbPermissionReceiver, permissionFilter)
            @Suppress("DEPRECATION")
            registerReceiver(usbStateReceiver, usbFilter)
        }
        receiversRegistered = true
    }

    private fun findAppleDevice(): UsbDevice? {
        val apple = usbManager.deviceList.values.filter { AppleUsbDevice.isAppleDevice(it) }
        if (apple.isEmpty()) return null
        return apple.firstOrNull { usbManager.hasPermission(it) } ?: apple.first()
    }

    private fun requestUsbPermission(autoStart: Boolean) {
        val device = findAppleDevice()
        if (device == null) {
            setStatus("未检测到 iPad")
            log("未检测到 Apple USB 设备。请用支持数据传输的数据线/OTG/Hub 连接 iPad。")
            val others = usbManager.deviceList.values.take(8)
            if (others.isEmpty()) {
                log("Android 目前没有枚举到任何 USB 设备：iPad 可能还没枚举完成、在锁屏休眠，或线只能充电。")
            } else {
                others.forEach { d ->
                    log("当前 USB 设备：VID=0x${d.vendorId.toString(16)} PID=0x${d.productId.toString(16)} ${d.deviceName}")
                }
            }
            return
        }
        log(
            "发现 Apple 设备：VID=0x${device.vendorId.toString(16)} " +
                "PID=0x${device.productId.toString(16)}"
        )
        if (usbManager.hasPermission(device)) {
            log("已有 USB 访问权限")
            if (autoStart) startConnectFlow()
            return
        }
        val mutability = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or mutability
        )
        setStatus("等待 USB 授权…")
        log("请求 USB 访问权限，请在系统弹窗中点“允许”。")
        usbManager.requestPermission(device, pending)
    }

    private fun checkAbi() {
        val abis = Build.SUPPORTED_ABIS.toList()
        if (abis.none { it == "arm64-v8a" }) {
            log("警告：本机 ABI=$abis，而 go-ios 仅提供 arm64-v8a，命令无法运行。")
        }
    }

    // ---- daemon -----------------------------------------------------------

    @Synchronized
    private fun ensureDaemon(): UsbMuxDaemon {
        daemon?.let { return it }
        val created = UsbMuxDaemon(applicationContext) { message -> log(message) }
        created.start()
        daemon = created
        return created
    }

    private fun stopDaemon() {
        runCatching { daemon?.stop() }
        daemon = null
    }

    private fun pairRecordFile(udid: String): File =
        File(File(filesDir, "pair"), "$udid.plist")

    /**
     * Ensures USB permission + daemon + a readable UDID. Returns the UDID or null.
     * May only be called from the worker thread.
     */
    private fun waitForDevice(): String? {
        val device = findAppleDevice()
        if (device == null) {
            log("未检测到 iPad。")
            setStatus("未检测到 iPad")
            return null
        }
        if (!usbManager.hasPermission(device)) {
            log("没有 USB 权限：请点“① 连接 iPad、配对并显示开发者模式开关”。")
            setStatus("需要 USB 授权")
            return null
        }
        val bridge = ensureDaemon()
        val deadline = System.currentTimeMillis() + 15_000L
        while (System.currentTimeMillis() < deadline) {
            val udid = bridge.udid()
            if (!udid.isNullOrBlank()) return udid
            Thread.sleep(400L)
        }
        log("已经连上 USB，但读不到 UDID（iPad 可能未解锁，或被其它 App 占用）。")
        setStatus("无法读取 UDID")
        return null
    }

    private fun preparedDevice(): String? {
        val udid = waitForDevice() ?: return null
        if (!pairRecordFile(udid).isFile) {
            log("还没有配对记录，请先点“① 连接 iPad、配对并显示开发者模式开关”。")
            setStatus("尚未配对")
            return null
        }
        return udid
    }

    // ---- flows ------------------------------------------------------------

    private fun startConnectFlow() {
        runExclusive("连接并配对") { startConnectFlowInternal() }
    }

    private fun startConnectFlowInternal() {
        val udid = waitForDevice() ?: return
        log("iPad UDID: $udid")
        setStatus("已识别 iPad\n$udid")
        if (!pairRecordFile(udid).isFile) {
            if (!pairLoop(udid)) {
                setStatus("配对失败\n请解锁 iPad 后重试")
                return
            }
        } else {
            log("已存在配对记录，跳过配对。")
        }
        setStatus("配对完成\n$udid")
        revealInternal()
    }

    private fun pairLoop(udid: String): Boolean {
        repeat(PAIR_ATTEMPTS) { attempt ->
            if (pairRecordFile(udid).isFile) {
                log("配对成功，配对记录已保存。")
                return true
            }
            log(">>> 配对（第 ${attempt + 1}/$PAIR_ATTEMPTS 次）")
            log("如果 iPad 弹出“要信任此电脑吗？”，请点“信任”并输入锁屏密码。")
            setStatus("等待 iPad 上点“信任”…")
            val output = StringBuilder()
            val result = GoIos.run(this, listOf("pair"), PAIR_TIMEOUT_SECONDS) { line ->
                output.append(line).append('\n')
                log(line)
            }
            if (pairRecordFile(udid).isFile) {
                log("配对成功，配对记录已保存。")
                return true
            }
            if (result.timedOut) {
                log("配对命令超时。")
                return false
            }
            val text = output.toString()
            if (text.contains("PairingDialog", ignoreCase = true) ||
                text.contains("Please accept", ignoreCase = true)
            ) {
                log("iPad 还在等你点“信任”，${PAIR_RETRY_DELAY_MS / 1000} 秒后自动重试…")
            } else {
                log("配对未完成（exit=${result.exitCode}），${PAIR_RETRY_DELAY_MS / 1000} 秒后重试…")
            }
            Thread.sleep(PAIR_RETRY_DELAY_MS)
        }
        return pairRecordFile(udid).isFile
    }

    private fun revealInternal() {
        log(">>> 请求 iPad 显示“开发者模式”开关（AMFI action 0）")
        setStatus("正在显示开发者模式开关…")
        val output = StringBuilder()
        val result = GoIos.run(this, listOf("devmode", "reveal"), DEVMODE_TIMEOUT_SECONDS) { line ->
            output.append(line).append('\n')
            log(line)
        }
        val text = output.toString()
        if (result.ok) {
            log("已请求显示。接下来请在 iPad 上打开：设置 → 隐私与安全性 → 开发者模式")
            log("打开后 iPad 会要求重启；重启后会弹窗要你用锁屏密码确认一次。")
            setStatus("开关已显示\n请到 iPad 设置→隐私与安全性→开发者模式")
        } else if (text.contains("PasswordProtected", ignoreCase = true)) {
            log("iPad 现在处于锁屏状态，系统拒绝 AMFI 服务（PasswordProtected）。")
            log("请先解锁 iPad 并停在桌面，再点一次「② 只显示开发者模式开关」。")
            setStatus("iPad 锁屏中\n解锁后再点②")
        } else {
            log("显示开关失败（exit=${result.exitCode}）。请确认 iPad 已解锁、已信任本机后重试。")
            setStatus("显示开关失败")
        }
    }

    private fun revealOnly() {
        runExclusive("显示开发者模式开关") {
            val udid = preparedDevice() ?: return@runExclusive
            log("使用 iPad: $udid")
            revealInternal()
        }
    }

    private fun enableDevMode() {
        runExclusive("开启开发者模式") {
            val udid = preparedDevice() ?: return@runExclusive
            log("使用 iPad: $udid")
            log(">>> 尝试直接开启开发者模式（AMFI action 1）")
            log("注意：iPad 设置了锁屏密码时系统不允许这样直接开启，会自动退回“显示开关”。")
            // No --enable-post-restart: after the reboot Android drops the USB
            // permission, so the automatic post-restart step can never connect.
            // We detect success from the log instead of relying on the exit code.
            val output = StringBuilder()
            val result = GoIos.run(
                this,
                listOf("devmode", "enable"),
                ENABLE_TIMEOUT_SECONDS
            ) { line ->
                output.append(line).append('\n')
                log(line)
            }
            val text = output.toString()
            val accepted = text.contains("Successfully enabled developer mode", ignoreCase = true) ||
                text.contains("device will restart", ignoreCase = true)
            if (result.ok || accepted) {
                log("iPad 已接受“开启开发者模式”请求，设备会重启。")
                log("重启后请按 iPad 屏幕提示确认；若没有提示，直接去 设置 → 隐私与安全性 → 开发者模式 查看开关。")
                setStatus("已请求开启\n重启后按 iPad 提示确认")
            } else {
                log("直接开启没有成功（有锁屏密码时属正常），改为显示“开发者模式”开关…")
                revealInternal()
            }
        }
    }

    private fun queryDevMode() {
        runExclusive("查询开发者模式状态") {
            val udid = preparedDevice() ?: return@runExclusive
            log("使用 iPad: $udid")
            log(">>> 查询开发者模式状态")
            val output = StringBuilder()
            val result = GoIos.run(this, listOf("devmode", "get"), DEVMODE_TIMEOUT_SECONDS) { line ->
                output.append(line).append('\n')
                log(line)
            }
            val text = output.toString()
            val enabledTrue = text.contains("\"DeveloperModeEnabled\":true") ||
                text.contains("\"DeveloperModeEnabled\": true")
            val enabledFalse = text.contains("\"DeveloperModeEnabled\":false") ||
                text.contains("\"DeveloperModeEnabled\": false")
            when {
                !result.ok -> {
                    log("查询失败。部分 iPadOS 版本在挂载开发者镜像前不支持该查询，可直接看设置里的开关。")
                    setStatus("查询失败\n直接看 iPad 设置里的开关")
                }
                enabledTrue -> {
                    log("✅ 开发者模式已经开启（DeveloperModeEnabled = true）。")
                    log("如果 iPad 刚重启过，在锁屏界面按提示用密码确认一次即可。")
                    setStatus("开发者模式：已开启 ✅")
                }
                enabledFalse -> {
                    log("开发者模式还没开启：请到 iPad 设置 → 隐私与安全性 → 开发者模式，打开开关。")
                    setStatus("开发者模式：未开启\n到 iPad 设置里打开")
                }
                else -> setStatus("查询完成（结果见日志）")
            }
        }
    }

    private fun resetPairing() {
        runExclusive("重置配对记录") {
            val dir = File(filesDir, "pair")
            var deleted = 0
            dir.listFiles()?.forEach { file ->
                if (file.delete()) deleted++
            }
            log("已删除 $deleted 个配对记录，iPad 那边可能仍认为本机已配对。")
            stopDaemon()
            Thread.sleep(500L)
            startConnectFlowInternal()
        }
    }
}
