package com.xmf.debugpro

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.xmf.debugpro.ui.theme.BeigeColors
import com.xmf.debugpro.ui.theme.XiaoMiFengTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private data class BleDeviceItem(
    val name: String,
    val address: String,
    val rssi: Int,
    val device: BluetoothDevice? = null
)

private data class LogEntry(
    val timestamp: String,
    val direction: String,
    val text: String,
    val isSent: Boolean
)

private val EXPECTED_ACCOUNT: String get() = BuildConfig.LOGIN_ACCOUNT
private val EXPECTED_PASSWORD: String get() = BuildConfig.LOGIN_PASSWORD
private val EXPECTED_CODE: String get() = BuildConfig.ACTIVATION_CODE

private const val PREFS_NAME = "xmf_login"
private const val KEY_ACCOUNT = "account"
private const val KEY_PASSWORD = "password"
private const val KEY_CODE = "code"
private const val KEY_REMEMBER = "remember"
private const val KEY_AUTO_LOGIN = "auto_login"
private const val KEY_ACTIVATED = "activated"
private const val KEY_LAST_DEVICE_NAME = "last_device_name"
private const val KEY_LAST_DEVICE_ADDRESS = "last_device_address"
private const val KEY_VOICE_ENABLED = "voice_enabled"
private const val KEY_VIBRATE_ENABLED = "vibrate_enabled"
private const val KEY_HISTORY_COUNT = "history_count"
private const val KEY_HISTORY_PREFIX = "history_"
private const val DEV_ACCESS = false  // true=调试模式(1010万能码), false=发布版(关闭后门)

private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

// ── 连接历史工具函数 ──
private const val MAX_HISTORY = 8
private fun loadHistory(prefs: SharedPreferences): List<Pair<String,String>> {
    val count = prefs.getInt(KEY_HISTORY_COUNT, 0)
    val list = mutableListOf<Pair<String,String>>()
    for (i in 0 until count.coerceAtMost(MAX_HISTORY)) {
        val v = prefs.getString("$KEY_HISTORY_PREFIX$i", "") ?: ""
        val parts = v.split("|", limit = 2)
        if (parts.size == 2) list.add(Pair(parts[0], parts[1]))
    }
    return list
}
private fun saveDeviceToHistory(prefs: SharedPreferences, name: String, addr: String) {
    val old = loadHistory(prefs).toMutableList()
    old.removeAll { it.second == addr } // 去重
    old.add(0, Pair(name, addr))        // 最新放最前
    val keep = old.take(MAX_HISTORY)
    prefs.edit().putInt(KEY_HISTORY_COUNT, keep.size).apply()
    keep.forEachIndexed { i, p ->
        prefs.edit().putString("$KEY_HISTORY_PREFIX$i", "${p.first}|${p.second}").apply()
    }
}
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XiaoMiFengTheme { AppScreen() }
        }
    }
}
// ─── TTS + 震动 ──────────────────────────────────────────────────────

private class TtsHelper(context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = mutableListOf<String>()

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINESE
                ready = true
                pending.forEach { speakNow(it) }
                pending.clear()
            }
        }
    }

    fun speak(text: String) {
        if (ready) speakNow(text) else pending.add(text)
    }

    private fun speakNow(text: String) {
        tts?.let { t ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                t.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            } else {
                @Suppress("DEPRECATION")
                t.speak(text, TextToSpeech.QUEUE_FLUSH, null)
            }
        }
    }

    fun destroy() {
        tts?.stop(); tts?.shutdown(); tts = null
    }
}

private fun vibrateShort(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION")
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v?.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v?.vibrate(150)
        }
    }
}

// ─── App ─────────────────────────────────────────────────────────────

@Composable
private fun AppScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val logEntries = remember { mutableStateListOf<LogEntry>() }

    var isLoggedIn by rememberSaveable { mutableStateOf(false) }
    var account by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var isActivated by rememberSaveable { mutableStateOf(false) }
    var sendHex by rememberSaveable { mutableStateOf(true) }
    var receiveHex by rememberSaveable { mutableStateOf(false) }
    var sendInput by rememberSaveable { mutableStateOf("") }
    var loginError by rememberSaveable { mutableStateOf("") }

    var loaded by rememberSaveable { mutableStateOf(false) }
    var isScanning by rememberSaveable { mutableStateOf(false) }
    var isConnecting by rememberSaveable { mutableStateOf(false) }
    var permissionRequestTrigger by rememberSaveable { mutableStateOf(0) }
    var latestMessage by rememberSaveable { mutableStateOf("等待连接数据...") }
    var connectedName by rememberSaveable { mutableStateOf("未连接") }
    var connectedAddress by rememberSaveable { mutableStateOf("--") }
    var isConnected by rememberSaveable { mutableStateOf(false) }
    var welcomePlayed by rememberSaveable { mutableStateOf(false) }
    var searchJustFinished by rememberSaveable { mutableStateOf(false) }
    // 配对对话框
    var showPairDialog by rememberSaveable { mutableStateOf(false) }
    var pairInput by rememberSaveable { mutableStateOf("") }
    var pendingDevice by remember { mutableStateOf<BleDeviceItem?>(null) }
    var selectedDevice by remember { mutableStateOf<BleDeviceItem?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf("原始数据") }
    var autoLogin by rememberSaveable { mutableStateOf(false) }
    var timedSendEnabled by rememberSaveable { mutableStateOf(false) }
    var timedSendInterval by rememberSaveable { mutableStateOf(1000L) } // ms
    var customProtocolData by rememberSaveable { mutableStateOf("--") }
    var joyLeftWheel by rememberSaveable { mutableStateOf("--") }
    var joyGyro by rememberSaveable { mutableStateOf("--") }
    var joyRightWheel by rememberSaveable { mutableStateOf("--") }
    var joyBattery by rememberSaveable { mutableStateOf("--") }
    var joyMode by rememberSaveable { mutableStateOf("--") }
    var joyStatus by rememberSaveable { mutableStateOf("--") }
    // BLE 数据刷新计数器：每次收到有效数据包时递增，强制触发 UI 刷新
    var bleDataSeq by rememberSaveable { mutableStateOf(0L) }

    val scanDevices = remember { mutableStateListOf<BleDeviceItem>() }
    val prefs: SharedPreferences = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val bleManager = remember { BleHelper(context) }
    val bleConnector = remember { BleConnector(context) }
    val tts = remember { TtsHelper(context) }

    // ── 语音/震动开关 ──
    var voiceEnabled by rememberSaveable { mutableStateOf(true) }
    var vibrateEnabled by rememberSaveable { mutableStateOf(true) }

    val doVibrate = { if (vibrateEnabled) vibrateShort(context) }
    val doSpeak: (String) -> Unit = { if (voiceEnabled) tts.speak(it) }

    // 激活成功后执行
    val doActivate: () -> Unit = {
        if (!isActivated) { isActivated = true; prefs.edit().putBoolean(KEY_ACTIVATED, true).apply() }
        loginError = ""; latestMessage = "登录成功，收发数据显示区已准备就绪。"
        isLoggedIn = true; doSpeak("登录成功"); doVibrate()
        prefs.edit()
            .putString(KEY_ACCOUNT, account)
            .putBoolean(KEY_AUTO_LOGIN, autoLogin)
            .also { e ->
                if (autoLogin) {
                    e.putString(KEY_PASSWORD, password)
                    e.putString(KEY_CODE, code)
                }
            }.apply()
    }

    // ── 连接历史（最多 8 条） ──
    var connectHistory by remember { mutableStateOf<List<Pair<String,String>>>(loadHistory(prefs)) }

    val appendLog: (isSent: Boolean, text: String) -> Unit = { sent, msg ->
        logEntries.add(LogEntry(
            timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
            direction = if (sent) "TX" else "RX", text = msg, isSent = sent
        ))
    }

    // ── OTA 更新状态 ──
    var updateInfo by remember { mutableStateOf<OtaManager.VersionInfo?>(null) }
    var updateChecked by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0) }
    var downloadFailed by remember { mutableStateOf(false) }
    var noUpdateMsg by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (!updateChecked) {
            try {
                val info = OtaManager(context).check()
                if (info != null) updateInfo = info
            } catch (_: Exception) { }
            updateChecked = true
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            isScanning = true
        } else {
            latestMessage = "蓝牙权限未授权，无法搜索设备。"
        }
    }

    // ── BLE 连接事件监听 ──
    LaunchedEffect(Unit) {
        // 接收缓冲区：累积字节用于粘包重组
        val recvBuf = mutableListOf<Byte>()
        var lastUiMs = 0L
        bleConnector.setEventListener { event ->
            when (event) {
                is BleConnEvent.Connected -> {
                    isConnecting = false; isConnected = true
                    connectedName = event.item.name; connectedAddress = event.item.address
                    isScanning = false; selectedDevice = null
                    latestMessage = "连接成功：${event.item.name}"
                    doSpeak("连接成功${event.item.name}"); doVibrate()
                    prefs.edit().putString(KEY_LAST_DEVICE_NAME, event.item.name).putString(KEY_LAST_DEVICE_ADDRESS, event.item.address).apply()
                    saveDeviceToHistory(prefs, event.item.name, event.item.address); connectHistory = loadHistory(prefs)
                    scope.launch { drawerState.close() }
                }
                is BleConnEvent.Disconnected -> {
                    isConnecting = false; isConnected = false
                    connectedName = "未连接"; connectedAddress = "--"
                    latestMessage = event.reason
                    doSpeak("设备已断开"); doVibrate()
                    recvBuf.clear()  // 断开时清空接收缓冲区
                }
                is BleConnEvent.Received -> {
                    // 1️⃣ 追加到接收缓冲区（粘包重组：应对 MTU 分包/高频连续包）
                    recvBuf.addAll(event.bytes.toList())
                    val buf = ByteArray(recvBuf.size) { recvBuf[it] }
                    // 2️⃣ 扫描缓冲区解析协议包
                    var i = 0
                    var hasJoy = false; var hasCustom = false
                    var matchedEnd = 0
                    while (i < buf.size) {
                        // 摇杆数据包：0x55 0x5A + 6字节数据 + 0x5B (共9字节)
                        if (i + 8 < buf.size && buf[i] == 0x55.toByte() && buf[i+1] == 0x5A.toByte() && buf[i+8] == 0x5B.toByte()) {
                            joyLeftWheel = (buf[i+2].toInt() and 0xFF).toString()
                            joyGyro = (buf[i+3].toInt() and 0xFF).toString()
                            joyRightWheel = (buf[i+4].toInt() and 0xFF).toString()
                            joyBattery = (buf[i+5].toInt() and 0xFF).toString()
                            joyMode = (buf[i+6].toInt() and 0xFF).toString()
                            joyStatus = (buf[i+7].toInt() and 0xFF).toString()
                            hasJoy = true; matchedEnd = i + 9; i += 9; continue
                        }
                        // 自定义协议包：0x2C 0x12 + N字节数据 + 0x5B (最少6字节)
                        if (i + 5 < buf.size && buf[i] == 0x2C.toByte() && buf[i+1] == 0x12.toByte()) {
                            val tailIdx = (i+2 until buf.size).find { buf[it] == 0x5B.toByte() }
                            if (tailIdx != null && tailIdx > i+1) {
                                val dataBytes = buf.sliceArray(i+2 until tailIdx)
                                customProtocolData = dataBytes.joinToString(" ") { (it.toInt() and 0xFF).toString() }
                                hasCustom = true; matchedEnd = tailIdx + 1; i = tailIdx + 1; continue
                            }
                        }
                        i++
                    }
                    // 3️⃣ 移除已匹配的字节，保留未匹配部分（可能是下个包的前半段）
                    if (matchedEnd > 0) {
                        repeat(matchedEnd) { if (recvBuf.isNotEmpty()) recvBuf.removeFirst() }
                    }
                    // 4️⃣ 日志：限流（最多保留 500 条，防止列表卡顿）
                    val hx = event.bytes.joinToString(" ") { b -> "%02X".format(b) }
                    val u8 = try { event.bytes.toString(StandardCharsets.UTF_8).replace("\r","").replace("\n","\\n") } catch (_:Exception) { "" }
                    if (logEntries.size >= 500) logEntries.removeAt(0)
                    appendLog(false, if (receiveHex) hx else (u8.ifBlank { "<$hx>" }))
                    // 5️⃣ UI 刷新限速：最高 ~20Hz（每 50ms 只刷一次）
                    if (hasJoy || hasCustom) {
                        val now = System.currentTimeMillis()
                        if (now - lastUiMs >= 50) {
                            bleDataSeq++
                            lastUiMs = now
                        }
                    }
                }
                is BleConnEvent.Sent -> {
                    val hx = event.bytes.joinToString(" ") { b -> "%02X".format(b) }
                    val u8 = try { event.bytes.toString(StandardCharsets.UTF_8).replace("\r","").replace("\n","\\n") } catch (_:Exception) { "" }
                    appendLog(true, if (sendHex) hx else u8)
                }
            }
        }
    }

    // ── 开机：读取激活状态 + 欢迎 + 读取登录 + 静默授权校验 ──
    LaunchedEffect(Unit) {
        val savedActivated = prefs.getBoolean(KEY_ACTIVATED, false)
        isActivated = savedActivated
        if (!savedActivated) { loaded = true; return@LaunchedEffect }

        // 读取语音/震动开关
        voiceEnabled = prefs.getBoolean(KEY_VOICE_ENABLED, true)
        vibrateEnabled = prefs.getBoolean(KEY_VIBRATE_ENABLED, true)

        val savedAccount = prefs.getString(KEY_ACCOUNT, "") ?: ""
        val savedPassword = prefs.getString(KEY_PASSWORD, "") ?: ""
        val savedCode = prefs.getString(KEY_CODE, "") ?: ""
        val savedAutoLogin = prefs.getBoolean(KEY_AUTO_LOGIN, false)
        autoLogin = savedAutoLogin

        // ── 静默授权校验（每次启动联网验证） ──
        try {
            val verifyErr = LicenseChecker.verifyCurrentDevice(context, savedCode)
            if (verifyErr != null) {
                // 校验不通过 → 踢回登录页
                isActivated = false
                isLoggedIn = false
                prefs.edit().putBoolean(KEY_ACTIVATED, false).apply()
                latestMessage = "授权验证失败：$verifyErr"
                loaded = true
                return@LaunchedEffect
            }
        } catch (_: Exception) {
            // 网络异常时不拦截，允许离线使用（但下次联网会重新校验）
        }

        if (savedAutoLogin && savedAccount == EXPECTED_ACCOUNT && savedPassword == EXPECTED_PASSWORD) {
            account = savedAccount; password = savedPassword; code = savedCode
            isLoggedIn = true
            latestMessage = "自动登录成功，主页面已打开。"
        } else if (savedAutoLogin) {
            account = savedAccount; password = savedPassword; code = savedCode
        }
        // 读取上次连接的设备并真实连接
        val lastName = prefs.getString(KEY_LAST_DEVICE_NAME, "") ?: ""
        val lastAddr = prefs.getString(KEY_LAST_DEVICE_ADDRESS, "") ?: ""
        if (lastName.isNotBlank() && lastAddr.isNotBlank()) {
            connectedName = lastName; connectedAddress = lastAddr
            try {
                val btAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                val remoteDev = btAdapter?.getRemoteDevice(lastAddr)
                if (remoteDev != null) {
                    isConnecting = true
                    bleConnector.connect(BleDeviceItem(lastName, lastAddr, 0, remoteDev))
                } else {
                    isConnected = true // fallback: just show as connected
                }
            } catch (_: Exception) {
                isConnected = true // fallback: just show as connected
            }
        }
        loaded = true
    }

    // 欢迎语音（延迟等 TTS 初始化完成）
    LaunchedEffect(loaded, isLoggedIn) {
        if (!welcomePlayed) {
            delay(500)
            doSpeak("欢迎使用小蜜蜂调试助手"); doVibrate()
            welcomePlayed = true
        }
    }

    LaunchedEffect(permissionRequestTrigger) {
        if (permissionRequestTrigger > 0) permissionLauncher.launch(requiredBluetoothPermissions())
    }

    // 扫描逻辑
    LaunchedEffect(isScanning) {
        if (isScanning) {
            scanDevices.clear()
            searchJustFinished = false
            latestMessage = "正在搜索附近蓝牙设备..."
            doSpeak("正在搜索蓝牙设备"); doVibrate()
            bleManager.startScan(
                onDeviceFound = { item ->
                    val idx = scanDevices.indexOfFirst { it.address == item.address }
                    if (idx >= 0) scanDevices[idx] = item else scanDevices.add(item)
                },
                onFinished = {
                    isScanning = false
                    searchJustFinished = true
                    if (scanDevices.isEmpty()) {
                        latestMessage = "未发现设备，请确认设备已上电并靠近手机。"
                        doSpeak("未发现蓝牙设备"); doVibrate()
                    } else {
                        latestMessage = "搜索完成，共发现 ${scanDevices.size} 台设备。"
                        doSpeak("搜索完成"); doVibrate()
                    }
                }
            )
        } else {
            bleManager.stopScan()
        }
    }

    // "搜索完成" 3 秒后自动复位
    LaunchedEffect(searchJustFinished) {
        if (searchJustFinished) { delay(3000); searchJustFinished = false }
    }

    // ── 定时发送 ──
    LaunchedEffect(timedSendEnabled, timedSendInterval, sendInput, sendHex, isConnected) {
        if (!timedSendEnabled || !isConnected) return@LaunchedEffect
        while (true) {
            delay(timedSendInterval)
            if (!timedSendEnabled || !isConnected) break
            val bytes = if (sendHex) (parseHexInput(sendInput) ?: continue) else sendInput.toByteArray(StandardCharsets.UTF_8)
            if (bytes.isEmpty()) continue
            bleConnector.send(bytes)
        }
    }

    DisposableEffect(Unit) {
        onDispose { bleManager.stopScan(); bleConnector.disconnect(); tts.destroy() }
    }

    // ── 界面 ──
    MaterialTheme {
        Scaffold(containerColor = BeigeColors.background) { paddingValues ->
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues).background(BeigeColors.background)
            ) {
                if (isLoggedIn) {
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        drawerContent = {
                            DrawerPage(
                                connectedName = connectedName,
                                connectedAddress = connectedAddress,
                                devices = scanDevices,
                                isScanning = isScanning,
                                isConnecting = isConnecting,
                                isConnected = isConnected,
                                selectedDevice = selectedDevice,
                                onSearchClick = {
                                    if (isScanning) {
                                        isScanning = false; searchJustFinished = true
                                        latestMessage = "搜索已手动停止。"; doSpeak("搜索已停止"); doVibrate()
                                    } else if (hasBluetoothPermissions(context)) {
                                        selectedDevice = null; isScanning = true
                                    } else {
                                        permissionRequestTrigger += 1
                                    }
                                },
                                onDeviceClick = { item ->
                                    selectedDevice = item
                                    // 在搜索中点设备 → 直接准备连接
                                    if (isScanning) {
                                        isScanning = false
                                        pendingDevice = item; pairInput = ""; showPairDialog = true
                                    }
                                },
                                onConnectClick = {
                                    if (isConnected) {
                                        // 先更新 UI，再断开底层连接
                                        isConnected = false; isConnecting = false
                                        connectedName = "未连接"; connectedAddress = "--"
                                        latestMessage = "设备已断开连接"
                                        doSpeak("设备已断开"); doVibrate()
                                        bleConnector.disconnect()
                                    } else {
                                        val dev = selectedDevice
                                        if (dev != null) {
                                            pendingDevice = dev; pairInput = ""; showPairDialog = true
                                        }
                                    }
                                },
                                onBackClick = {
                                    doSpeak("返回主页面"); doVibrate()
                                    scope.launch { drawerState.close() }
                                },
                                voiceEnabled = voiceEnabled,
                                vibrateEnabled = vibrateEnabled,
                                onVoiceChange = { voiceEnabled = it; prefs.edit().putBoolean(KEY_VOICE_ENABLED, it).apply() },
                                onVibrateChange = { vibrateEnabled = it; prefs.edit().putBoolean(KEY_VIBRATE_ENABLED, it).apply() },
                                onCheckUpdate = {
                                    scope.launch {
                                        try {
                                            val info = OtaManager(context).check()
                                            if (info != null) updateInfo = info
                                            else {
                                                noUpdateMsg = "已是最新版本（v${context.packageManager.getPackageInfo(context.packageName, 0).versionName}）"
                                            }
                                        } catch (_: Exception) {
                                            noUpdateMsg = "检查更新失败，请检查网络连接"
                                        }
                                    }
                                },
                                connectHistory = connectHistory,
                                onHistoryClick = { name, addr ->
                                    // 构造一个历史 BleDeviceItem，通过常规连接流程处理
                                    scope.launch {
                                        try {
                                            val btAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                                            val dev = btAdapter?.getRemoteDevice(addr)
                                            if (dev != null) {
                                                pendingDevice = BleDeviceItem(name, addr, 0, dev); pairInput = ""; showPairDialog = true
                                                scope.launch { drawerState.close() }
                                            } else {
                                                latestMessage = "设备不在范围内，请搜索后连接"; doSpeak("设备不在范围内")
                                            }
                                        } catch (_: Exception) {
                                            latestMessage = "连接失败，请搜索后重试"; doSpeak("连接失败")
                                        }
                                    }
                                }
                            )
                        }
                    ) {
                        MainPage(
                            logEntries = logEntries,
                            latestMessage = latestMessage,
                            receiveHex = receiveHex,
                            onReceiveHexChange = {
                                receiveHex = it
                                val mode = if (it) "HEX" else "UTF8"
                                doSpeak("接收模式$mode"); doVibrate()
                            },
                            sendHex = sendHex,
                            onSendHexChange = {
                                sendHex = it
                                val mode = if (it) "HEX" else "UTF8"
                                doSpeak("发送模式$mode"); doVibrate()
                            },
                            sendInput = sendInput,
                            onSendInputChange = { sendInput = it },
                            connectedName = connectedName,
                            isConnected = isConnected,
                            selectedTab = selectedTab,
                            onTabChange = {
                                selectedTab = it
                                doSpeak(it); doVibrate()
                            },
                            onMenuClick = { scope.launch { drawerState.open() } },
                            onSendClick = {
                                if (!isConnected) {
                                    latestMessage = "发送失败：当前未连接蓝牙设备。"
                                    doSpeak("发送失败，未连接设备"); doVibrate()
                                } else if (sendHex && parseHexInput(sendInput) == null) {
                                    latestMessage = "发送失败：HEX 格式错误。"
                                    doSpeak("发送失败，格式错误"); doVibrate()
                                } else {
                                    val bytes = if (sendHex) parseHexInput(sendInput)!! else sendInput.toByteArray(StandardCharsets.UTF_8)
                                    if (bleConnector.send(bytes)) {
                                        latestMessage = "已发送 ${bytes.size} 字节"
                                        doSpeak("已发送"); doVibrate()
                                    } else {
                                        latestMessage = "发送失败：写特征不可用"
                                        doSpeak("发送失败"); doVibrate()
                                    }
                                }
                            },
                            onClearLog = {
                                logEntries.clear(); latestMessage = "接收区已清空。"
                                doSpeak("已清空接收区"); doVibrate()
                            },
                            onSendBytes = { bytes -> bleConnector.send(bytes) },
                            onSpeak = doSpeak,
                            onVibrate = doVibrate,
                            timedSendEnabled = timedSendEnabled,
                            onTimedSendChange = { timedSendEnabled = it },
                            timedSendInterval = timedSendInterval,
                            onTimedSendIntervalChange = { timedSendInterval = it },
                            customProtocolData = customProtocolData,
                            joyLeftWheel = joyLeftWheel, joyGyro = joyGyro,
                            joyRightWheel = joyRightWheel, joyBattery = joyBattery,
                            joyMode = joyMode, joyStatus = joyStatus,
                            bleDataSeq = bleDataSeq
                        )
                    }

                    // ── OTA 更新对话框 ──
                    if (updateInfo != null && !isDownloading) {
                        val info = updateInfo!!
                        AlertDialog(
                            onDismissRequest = { updateInfo = null },
                            title = { Text("发现新版本", fontWeight = FontWeight.Bold) },
                            text = {
                                Column {
                                    Text("版本：${info.version}", style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.height(4.dp))
                                    Text(info.notes, color = BeigeColors.hint, style = MaterialTheme.typography.bodySmall)
                                    Spacer(Modifier.height(8.dp))
                                    if (info.apkSize > 0) {
                                        Text("大小：${"%.1f".format(info.apkSize / 1048576.0)} MB",
                                            color = BeigeColors.hint, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            },
                            confirmButton = {
                                Button(onClick = {
                                    isDownloading = true; downloadProgress = 0; updateInfo = null
                                    OtaManager(context).downloadAndInstall(info,
                                        onProgress = { pct -> downloadProgress = pct },
                                        onFinish = { ok ->
                                            isDownloading = false
                                            if (!ok) downloadFailed = true
                                        }
                                    )
                                }) { Text("立即更新") }
                            },
                            dismissButton = {
                                Button(onClick = { updateInfo = null }) { Text("稍后再说") }
                            }
                        )
                    }

                    // ── 下载进度对话框 ──
                    if (isDownloading) {
                        AlertDialog(
                            onDismissRequest = {},
                            title = { Text("正在更新", fontWeight = FontWeight.Bold) },
                            text = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                    Text("正在下载新版本...", style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.height(16.dp))
                                    LinearProgressIndicator(
                                        progress = { downloadProgress / 100f },
                                        modifier = Modifier.fillMaxWidth().height(8.dp),
                                        color = BeigeColors.primary,
                                        trackColor = Color(0xFFE0E0E0)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text("$downloadProgress%", color = BeigeColors.primary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                            },
                            confirmButton = {}
                        )
                    }

                    // ── 下载失败对话框 ──
                    if (downloadFailed) {
                        AlertDialog(
                            onDismissRequest = { downloadFailed = false },
                            title = { Text("下载失败", fontWeight = FontWeight.Bold) },
                            text = { Text("更新下载失败，请检查网络后重试。\n你也可以前往侧滑栏→检查更新 手动操作。", style = MaterialTheme.typography.bodyMedium) },
                            confirmButton = { Button(onClick = { downloadFailed = false }) { Text("知道了") } }
                        )
                    }

                    // ── 无新版本提示对话框 ──
                    if (noUpdateMsg.isNotEmpty()) {
                        AlertDialog(
                            onDismissRequest = { noUpdateMsg = "" },
                            title = { Text("检查更新", fontWeight = FontWeight.Bold) },
                            text = { Text(noUpdateMsg, style = MaterialTheme.typography.bodyMedium) },
                            confirmButton = {
                                Button(onClick = { noUpdateMsg = "" }) { Text("知道了") }
                            }
                        )
                    }

                    // ── 配对码对话框 ──
                    if (showPairDialog && pendingDevice != null) {
                        AlertDialog(
                            onDismissRequest = { showPairDialog = false },
                            title = { Text("配对码", fontWeight = FontWeight.Bold) },
                            text = {
                                Column {
                                    Text("设备：${pendingDevice!!.name}", style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.height(4.dp)); Text(pendingDevice!!.address, color = BeigeColors.hint, style = MaterialTheme.typography.bodySmall)
                                    Spacer(Modifier.height(12.dp)); Text("需要配对码请输入，否则直接连接", style = MaterialTheme.typography.bodySmall, color = BeigeColors.hint)
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedTextField(pairInput, { pairInput = it }, Modifier.fillMaxWidth(), label = { Text("配对码/PIN") }, singleLine = true)
                                }
                            },
                            confirmButton = {
                                Button(onClick = {
                                    showPairDialog = false; val dev = pendingDevice ?: return@Button
                                    isConnecting = true; isScanning = false
                                    // 不调用 createBond() — Android 系统会自动处理配对弹窗
                                    latestMessage = "正在连接 ${dev.name} ..."
                                    doSpeak("正在连接"); doVibrate()
                                    bleConnector.connect(dev)
                                }) { Text("连接") }
                            },
                            dismissButton = { Button(onClick = { showPairDialog = false }) { Text("取消") } }
                        )
                    }
                    // AlertDialog 这里是被 isLoggedIn 包裹的，没问题
                } else if (loaded) {
                    LoginPage(
                        account = account, password = password, code = code,
                        loginError = loginError,
                        onAccountChange = { account = it; loginError = "" },
                        onPasswordChange = { password = it; loginError = "" },
                        onCodeChange = { code = it; loginError = "" },
                        onLogin = {
                            scope.launch {
                                // 后门：DEV_ACCESS 为 true 时 1010 可直接通过
                                if (DEV_ACCESS && code.trim().uppercase() == "1010") {
                                    doActivate(); return@launch
                                }
                                // 在线验证
                                val err = LicenseChecker.activate(context, code)
                                if (err != null) {
                                    loginError = err; latestMessage = err; doSpeak("授权码验证失败")
                                } else {
                                    doActivate()
                                }
                            }
                        },
                        autoLogin = autoLogin,
                        onAutoLoginChange = { autoLogin = it }
                    )
            }
        }
    }
}
}

// ─── 登录页 ─────────────────────────────────────────────────────────
@Composable
private fun LoginPage(
    account: String, password: String, code: String,
    loginError: String,
    onAccountChange: (String) -> Unit, onPasswordChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onLogin: () -> Unit,
    autoLogin: Boolean, onAutoLoginChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().background(BeigeColors.background).padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("欢迎使用小蜜蜂调试助手", style = MaterialTheme.typography.headlineSmall, color = BeigeColors.text, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(20.dp))
        Box(modifier = Modifier.size(120.dp).clip(RoundedCornerShape(28.dp)).background(BeigeColors.surface), contentAlignment = Alignment.Center) { Text("🐝", style = MaterialTheme.typography.displayLarge) }
        Spacer(modifier = Modifier.height(24.dp))
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = BeigeColors.card), shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                OutlinedTextField(account, onAccountChange, Modifier.fillMaxWidth(), label = { Text("账号") }, singleLine = true)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(password, onPasswordChange, Modifier.fillMaxWidth(), label = { Text("密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(code, onCodeChange, Modifier.fillMaxWidth(), label = { Text("授权码") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = autoLogin, onCheckedChange = onAutoLoginChange); Text("下次自动登录", color = BeigeColors.text) }
                if (loginError.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(loginError, color = Color(0xFFC62828), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(modifier = Modifier.height(18.dp))
                Button(onClick = onLogin, Modifier.fillMaxWidth()) { Text("登录") }
            }
        }
    }
}

// ─── 主页面 ─────────────────────────────────────────────────────────

@Composable
private fun MainPage(
    logEntries: List<LogEntry>, latestMessage: String,
    receiveHex: Boolean, onReceiveHexChange: (Boolean) -> Unit,
    sendHex: Boolean, onSendHexChange: (Boolean) -> Unit,
    sendInput: String, onSendInputChange: (String) -> Unit,
    connectedName: String, isConnected: Boolean,
    selectedTab: String, onTabChange: (String) -> Unit,
    onMenuClick: () -> Unit, onSendClick: () -> Unit, onClearLog: () -> Unit,
    onSendBytes: (ByteArray) -> Boolean,
    onSpeak: (String) -> Unit,
    onVibrate: () -> Unit,
    timedSendEnabled: Boolean,
    onTimedSendChange: (Boolean) -> Unit,
    timedSendInterval: Long,
    onTimedSendIntervalChange: (Long) -> Unit,
    customProtocolData: String,
    joyLeftWheel: String, joyGyro: String, joyRightWheel: String,
    joyBattery: String, joyMode: String, joyStatus: String,
    bleDataSeq: Long
) {
    val logListState = rememberLazyListState()
    LaunchedEffect(logEntries.size) { if (logEntries.isNotEmpty()) logListState.animateScrollToItem(logEntries.lastIndex) }

    Column(Modifier.fillMaxSize().background(BeigeColors.background)) {
        // 顶栏
        Row(Modifier.fillMaxWidth().background(BeigeColors.topBar).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("☰", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clickable { onMenuClick() })
            Text("小蜜蜂调试助手", color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp), fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("●", color = if (isConnected) Color(0xFF2E7D32) else BeigeColors.offline, fontSize = 10.sp)
                Spacer(Modifier.width(4.dp)); Text(connectedName, color = Color(0xFFFFD9D9), style = MaterialTheme.typography.bodySmall)
            }
        }

        // ── 四个功能按钮栏（可点击切换）──
        Row(
            Modifier.fillMaxWidth().background(Color(0xFFF0E6CC)).padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val tabs = listOf(
                "原始数据" to Color(0xFF3A86FF),
                "摇杆控制" to Color(0xFF8338EC),
                "功能按键" to Color(0xFFFF6B35),
                "自定义协议" to Color(0xFF06D6A0)
            )
            tabs.forEach { (name, bg) ->
                val isActive = selectedTab == name
                Text(
                    text = name,
                    color = if (isActive) Color.White else Color(0xFF886644),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 3.dp)
                        .clickable { onTabChange(name) }
                        .background(if (isActive) bg else Color(0xFFE8DCC0), RoundedCornerShape(8.dp))
                        .border(1.5.dp, if (isActive) bg else Color(0xFFCCBB99), RoundedCornerShape(8.dp))
                        .padding(vertical = 9.dp, horizontal = 2.dp)
                )
            }
        }

        // ── 根据 Tab 切换内容 ──
        when (selectedTab) {
            "原始数据" -> OriginalDataPage(
                logEntries, latestMessage, receiveHex, onReceiveHexChange,
                sendHex, onSendHexChange, sendInput, onSendInputChange,
                onSendClick, onClearLog, logListState,
                timedSendEnabled, onTimedSendChange, timedSendInterval, onTimedSendIntervalChange
            )
            "摇杆控制" -> JoystickPage(onSendBytes, onSpeak, onVibrate, joyLeftWheel, joyGyro, joyRightWheel, joyBattery, joyMode, joyStatus, bleDataSeq)
            "功能按键" -> FunctionButtonsPage(onSpeak, onVibrate, onSendBytes)
            "自定义协议" -> CustomProtocolPage(customProtocolData, bleDataSeq)
            else -> OriginalDataPage(
                logEntries, latestMessage, receiveHex, onReceiveHexChange,
                sendHex, onSendHexChange, sendInput, onSendInputChange,
                onSendClick, onClearLog, logListState,
                timedSendEnabled, onTimedSendChange, timedSendInterval, onTimedSendIntervalChange
            )
        }
    }
}

// ─── 原始数据页 ─────────────────────────────────────────────────────

@Composable
private fun OriginalDataPage(
    logEntries: List<LogEntry>, latestMessage: String,
    receiveHex: Boolean, onReceiveHexChange: (Boolean) -> Unit,
    sendHex: Boolean, onSendHexChange: (Boolean) -> Unit,
    sendInput: String, onSendInputChange: (String) -> Unit,
    onSendClick: () -> Unit, onClearLog: () -> Unit,
    logListState: androidx.compose.foundation.lazy.LazyListState,
    timedSendEnabled: Boolean, onTimedSendChange: (Boolean) -> Unit,
    timedSendInterval: Long, onTimedSendIntervalChange: (Long) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp)) {
        // 收发日志标题
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("收发日志", style = MaterialTheme.typography.titleSmall, color = BeigeColors.text, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (receiveHex) "HEX" else "UTF-8", color = BeigeColors.hint, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(6.dp))
                CompactSwitch(receiveHex, onReceiveHexChange)
                Spacer(Modifier.width(14.dp))
                Text("清空", color = Color.White, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable { onClearLog() }.background(Color(0xFFC28B4C), RoundedCornerShape(4.dp)).border(1.dp, Color(0xFFA07030), RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth().weight(1f), colors = CardDefaults.cardColors(containerColor = BeigeColors.surface), shape = RoundedCornerShape(0.dp)) {
            Box(Modifier.fillMaxSize().border(1.dp, BeigeColors.border).padding(horizontal = 10.dp, vertical = 10.dp)) {
                if (logEntries.isEmpty()) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("暂无收发数据", color = BeigeColors.hint, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp))
                        Spacer(Modifier.height(6.dp)); Text(latestMessage, color = BeigeColors.hint, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), state = logListState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(logEntries) { e -> Text("[${e.timestamp}] ${e.direction}: ${e.text}", color = if (e.isSent) Color(0xFF2E7D32) else Color.Black, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // 发送模式 + 定时发送
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("发送模式", color = BeigeColors.text, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp)); Text(if (sendHex) "HEX" else "UTF-8", color = BeigeColors.hint, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp)); CompactSwitch(sendHex, onSendHexChange)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 定时间隔选择
                val intervalOptions = listOf(10L to "10ms", 500L to "500ms", 1000L to "1s")
                var showIntervalMenu by remember { mutableStateOf(false) }
                Box {
                    Text(
                        text = when (timedSendInterval) { 10L -> "10ms"; 500L -> "500ms"; else -> "1s" },
                        color = if (timedSendEnabled) Color(0xFFC28B4C) else BeigeColors.hint,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable { showIntervalMenu = true }.background(if (timedSendEnabled) Color(0xFFFFF3D6) else Color.Transparent, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                    androidx.compose.material3.DropdownMenu(expanded = showIntervalMenu, onDismissRequest = { showIntervalMenu = false }) {
                        intervalOptions.forEach { (value, label) ->
                            androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = { onTimedSendIntervalChange(value); showIntervalMenu = false })
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
                // 定时开关
                androidx.compose.material3.Checkbox(
                    checked = timedSendEnabled,
                    onCheckedChange = { onTimedSendChange(it) },
                    modifier = Modifier.size(28.dp),
                    colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = Color(0xFFC28B4C))
                )
                Text("定时", color = BeigeColors.text, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(8.dp))
        // 输入发送
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(sendInput, onSendInputChange, Modifier.weight(1f).height(50.dp), placeholder = { Text(if (sendHex) "输入HEX，如 AA 55 01" else "输入文本数据", color = BeigeColors.hint, fontSize = 13.sp) }, singleLine = true, shape = RoundedCornerShape(10.dp))
            Spacer(Modifier.width(10.dp)); Button(onClick = onSendClick, Modifier.height(50.dp).width(100.dp), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = BeigeColors.primary)) { Text(if (timedSendEnabled) "▶ 定时" else "▶ 发送", color = Color.White, fontSize = 15.sp) }
        }
    }
}

// ─── 摇杆控制页 ────────────────────────────────────────────────────

@Composable
private fun JoystickPage(onSendBytes: (ByteArray) -> Boolean, onSpeak: (String) -> Unit, onVibrate: () -> Unit,
    joyLeftWheel: String, joyGyro: String, joyRightWheel: String,
    joyBattery: String, joyMode: String, joyStatus: String,
    bleDataSeq: Long) {
    var ox by remember { mutableStateOf(0f) }; var oy by remember { mutableStateOf(0f) }
    var lastSector by remember { mutableStateOf(-1) }
    val br = 140.dp; val threshold = 15f

    val sectorCmds = byteArrayOf(0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58)
    val voiceMap = mapOf(0x51 to "前进", 0x53 to "右转", 0x55 to "后退", 0x57 to "左转", 0x5A to "停车")

    fun getSector(x: Float, y: Float): Int {
        val dist = kotlin.math.sqrt(x * x + y * y)
        if (dist < threshold) return -1
        val angle = Math.toDegrees(kotlin.math.atan2(y.toDouble(), x.toDouble())).toFloat()
        return (((angle + 90f) + 360f) % 360f + 22.5f).toInt() / 45 % 8
    }

    fun sendIfChanged(newSector: Int) {
        if (newSector != lastSector) {
            lastSector = newSector
            val cmd = if (newSector == -1) byteArrayOf(0x5A) else byteArrayOf(sectorCmds[newSector])
            onSendBytes(cmd)
            voiceMap[cmd[0].toInt() and 0xFF]?.let { onSpeak(it); onVibrate() }
        }
    }

    Column(Modifier.fillMaxSize().background(BeigeColors.background)) {
        // bleDataSeq 用于强制 Compose 在有数据刷新时重新渲染
        Text(bleDataSeq.toString(), fontSize = 0.sp, color = Color.Transparent)
        // ── 数据展示区（摇杆上方）──
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            // 标题
            Text("实时数据", color = Color(0xFF887755), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            // 第一行：左轮速度 | 陀螺仪角度 | 右轮速度
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Databox("左轮速度", joyLeftWheel, "rpm", Color(0xFF3A86FF))
                Databox("陀螺仪角度", joyGyro, "deg", Color(0xFF8338EC))
                Databox("右轮速度", joyRightWheel, "rpm", Color(0xFFFF6B35))
            }
            Spacer(Modifier.height(6.dp))
            // 第二行：电量 | 模式 | 状态
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Databox("电量", joyBattery, "%", Color(0xFF06D6A0))
                Databox("模式", joyMode, "-", Color(0xFFF4A261))
                Databox("状态", joyStatus, "-", Color(0xFFE63946))
            }
        }

        // ── 摇杆 ──
        Box(Modifier.weight(1f).fillMaxWidth().background(BeigeColors.background), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(br * 2).pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { ox = 0f; oy = 0f; sendIfChanged(-1) },
                    onDragCancel = { ox = 0f; oy = 0f; sendIfChanged(-1) }
                ) { ch, da -> ch.consume()
                    val max = br.toPx(); val nx = (ox + da.x).coerceIn(-max, max); val ny = (oy + da.y).coerceIn(-max, max)
                    val d = kotlin.math.sqrt(nx * nx + ny * ny); val r = max / d
                    if (d <= max) { ox = nx; oy = ny } else { ox = nx * r; oy = ny * r }
                    sendIfChanged(getSector(ox, oy))
                }
            }) {
                val sz = size.minDimension; val cx = sz / 2; val cy = sz / 2; val r = sz / 2 * 0.85f; val kr = sz / 2 * 0.22f
                drawCircle(Color(0xFF4A4A4A), r, Offset(cx, cy))
                drawCircle(Color(0xFF3A3A3A), r * 0.7f, Offset(cx, cy))
                drawLine(Color(0xFF666666), Offset(cx - r, cy), Offset(cx + r, cy), 1.5f)
                drawLine(Color(0xFF666666), Offset(cx, cy - r), Offset(cx, cy + r), 1.5f)
                if (ox == 0f && oy == 0f) {
                    drawCircle(Color(0xFFD4A843), kr, Offset(cx, cy)); drawCircle(Color(0xFFE8C46A), kr * 0.7f, Offset(cx, cy))
                } else {
                    drawCircle(Color(0xFFD4A843), kr, Offset(cx + ox, cy + oy)); drawCircle(Color(0xFFE8C46A), kr * 0.7f, Offset(cx + ox, cy + oy))
                }
            }
        }
    }
}

@Composable
private fun Databox(label: String, value: String, unit: String, color: Color) {
    Column(Modifier.width(100.dp).background(Color.White, RoundedCornerShape(10.dp)).border(0.5.dp, Color(0xFFD4C9A8), RoundedCornerShape(10.dp)).padding(vertical = 8.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color(0xFF999999), fontSize = 10.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(unit, color = Color(0xFFCCCCCC), fontSize = 9.sp)
    }
}

// ─── 功能按键页 ────────────────────────────────────────────────────

@Composable
private fun FunctionButtonsPage(onSpeak: (String) -> Unit, onVibrate: () -> Unit, onSendBytes: (ByteArray) -> Boolean) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("func_buttons", Context.MODE_PRIVATE) }
    val defaultNames = listOf("功能按键一","功能按键二","功能按键三","功能按键四","功能按键五","功能按键六","功能按键七","功能按键八","功能按键九","功能按键十","功能按键十一","功能按键十二","功能按键十三","功能按键十四","功能按键十五")
    // 加载自定义名称
    var customNames by remember { mutableStateOf(prefs.getString("custom_names", "")?.split("|")?.take(15)?.let { if (it.size == 15) it else null } ?: defaultNames) }
    val colors = listOf(Color(0xFF3A86FF),Color(0xFF8338EC),Color(0xFFFF6B35),Color(0xFF06D6A0),Color(0xFFE63946),Color(0xFF457B9D),Color(0xFFF4A261),Color(0xFF2A9D8F),Color(0xFF9C89B8),Color(0xFFF77F00),Color(0xFF1D3557),Color(0xFFBF4E30),Color(0xFF4A919E),Color(0xFF6A4C93),Color(0xFFCB904A))

    var showRenameDialog by remember { mutableStateOf(false) }
    var renameIdx by remember { mutableStateOf(0) }
    var renameInput by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(BeigeColors.background).padding(14.dp), verticalArrangement = Arrangement.SpaceEvenly) {
        for (row in 0 until 5) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                for (col in 0 until 3) {
                    val idx = row * 3 + col
                    val label = customNames[idx]
                    val displayLabel = label
                    val cmd = byteArrayOf((0x60 + idx).toByte())
                    Text(displayLabel, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .width(100.dp)
                            .pointerInput(idx, label) {
                                detectTapGestures(
                                    onTap = { onSpeak(displayLabel); onVibrate(); onSendBytes(cmd) },
                                    onLongPress = { renameIdx = idx; renameInput = label; showRenameDialog = true }
                                )
                            }
                            .background(colors[idx], RoundedCornerShape(14.dp))
                            .border(2.dp, colors[idx].copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                            .padding(vertical = 16.dp, horizontal = 6.dp))
                }
            }
        }
    }

    // 重命名对话框
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("修改名称", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("当前：功能按键${customNames[renameIdx]}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(renameInput, { renameInput = it }, Modifier.fillMaxWidth(), label = { Text("新名称") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val newList = customNames.toMutableList()
                    newList[renameIdx] = renameInput.ifBlank { defaultNames[renameIdx] }
                    customNames = newList
                    prefs.edit().putString("custom_names", newList.joinToString("|")).apply()
                    showRenameDialog = false
                    onSpeak("已修改"); onVibrate()
                }) { Text("保存") }
            },
            dismissButton = { Button(onClick = { showRenameDialog = false }) { Text("取消") } }
        )
    }
}

// ─── 自定义协议页 ─────────────────────────────────────────────────

@Composable
private fun CustomProtocolPage(protocolData: String, bleDataSeq: Long) {
    Column(Modifier.fillMaxSize().background(BeigeColors.background).padding(16.dp)) {
        // bleDataSeq 用于强制 Compose 在有数据刷新时重新渲染
        Text(bleDataSeq.toString(), fontSize = 0.sp, color = Color.Transparent)
        // 标题栏
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = BeigeColors.topBar), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("自定义协议包", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(6.dp))
                Text("遵循 0x2C 0x12 双包头，0x5B 包尾协议", color = Color(0xFFFFE2B0), fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(20.dp))

        // 协议格式说明
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(2.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("协议格式", fontWeight = FontWeight.Bold, color = BeigeColors.text)
                Spacer(Modifier.height(8.dp))
                Text("包头：0x2C 0x12", color = BeigeColors.text, fontSize = 13.sp)
                Text("数据：3 字节（解析值）", color = BeigeColors.text, fontSize = 13.sp)
                Text("包尾：0x5B", color = BeigeColors.text, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text("示例：0x2C 0x12 0x01 0x02 0x03 0x5B", color = BeigeColors.hint, fontSize = 12.sp)
                Text("  → 解析数据（十进制）：1 2 3", color = BeigeColors.hint, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(20.dp))

        // 实时解析数据
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(2.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("实时解析数据", fontWeight = FontWeight.Bold, color = BeigeColors.text)
                Spacer(Modifier.height(12.dp))
                if (protocolData == "--") {
                    Text("等待接收协议数据...", color = BeigeColors.hint, fontSize = 14.sp)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        val parts = protocolData.split(" ").filter { it.isNotBlank() }
                        parts.forEach { byte ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(byte, color = Color(0xFF3A86FF), fontWeight = FontWeight.Bold, fontSize = 22.sp)
                                Spacer(Modifier.height(4.dp))
                                Text("字节", color = BeigeColors.hint, fontSize = 10.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (protocolData != "--") {
                    HorizontalDivider(color = BeigeColors.border)
                    Spacer(Modifier.height(8.dp))
                    Text("原始协议包：0x2C 0x12 $protocolData 0x5B", color = BeigeColors.hint, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun CompactSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(checked, onCheckedChange, Modifier.size(width = 46.dp, height = 28.dp),
        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = BeigeColors.primary,
            uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFFE9DDB8)))
}

// ─── 侧滑栏 ─────────────────────────────────────────────────────────

@Composable
private fun DrawerPage(
    connectedName: String, connectedAddress: String,
    devices: List<BleDeviceItem>,
    isScanning: Boolean, isConnecting: Boolean, isConnected: Boolean,
    selectedDevice: BleDeviceItem?,
    onSearchClick: () -> Unit, onDeviceClick: (BleDeviceItem) -> Unit,
    onConnectClick: () -> Unit, onBackClick: () -> Unit,
    voiceEnabled: Boolean, vibrateEnabled: Boolean,
    onVoiceChange: (Boolean) -> Unit, onVibrateChange: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    connectHistory: List<Pair<String,String>>,
    onHistoryClick: (String, String) -> Unit
) {
    ModalDrawerSheet(Modifier.fillMaxHeight(), drawerContainerColor = BeigeColors.background) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                // 顶部横幅
                Column(Modifier.fillMaxWidth().background(BeigeColors.topBar).padding(vertical = 24.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("小蜜蜂调试助手", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("BLE串口调试终端", color = Color(0xFFFFE2B0), style = MaterialTheme.typography.bodySmall)
                    Text("v1.8.6", color = Color(0xFFFFE2B0), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("软件作者：伍圣锋", color = Color(0xFF5D4037), style = MaterialTheme.typography.bodySmall, fontSize = 10.sp)
                    Text("反馈：554805466@qq.com", color = Color(0xFF5D4037), style = MaterialTheme.typography.bodySmall, fontSize = 10.sp)
                }
                Spacer(Modifier.height(10.dp))

                // ── 快速操作行 ──
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onBackClick, modifier = Modifier.weight(1f).height(36.dp),
                        shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB89B5E))) {
                        Text("← 返回", color = Color.White, fontSize = 14.sp)
                    }
                    Button(onClick = onCheckUpdate, modifier = Modifier.weight(1f).height(36.dp),
                        shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5B9BD5))) {
                        Text("⟳ 检查更新", color = Color.White, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))

                // ── 提示设置 + 连接状态 双列 ──
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 提示设置
                    Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(14.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Text("提示设置", color = BeigeColors.text, style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = voiceEnabled, onCheckedChange = onVoiceChange, modifier = Modifier.size(20.dp))
                                Text("语音", color = BeigeColors.text, fontSize = 12.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = vibrateEnabled, onCheckedChange = onVibrateChange, modifier = Modifier.size(20.dp))
                                Text("震动", color = BeigeColors.text, fontSize = 12.sp)
                            }
                        }
                    }
                    // 连接状态
                    Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(14.dp), elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Text("连接状态", color = BeigeColors.text, style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("●", color = if (isConnected) Color(0xFF2E7D32) else BeigeColors.offline, fontSize = 12.sp)
                                Spacer(Modifier.width(6.dp))
                                Text(connectedName, color = if (isConnected) Color(0xFF2E7D32) else Color(0xFFC62828),
                                    fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Text(connectedAddress, color = BeigeColors.hint, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                // ── 连接历史 ──
                if (connectHistory.isNotEmpty()) {
                    Text("连接历史", color = BeigeColors.text, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 14.dp))
                    Spacer(Modifier.height(6.dp))
                    connectHistory.forEach { (name, addr) ->
                        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)
                            .clickable { onHistoryClick(name, addr) },
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            shape = RoundedCornerShape(10.dp)) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("↻", color = BeigeColors.primary, fontSize = 14.sp)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(name, color = BeigeColors.text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(addr, color = BeigeColors.hint, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // ── 搜索/连接按钮 ──
                Button(onClick = onSearchClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(40.dp),
                    shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = BeigeColors.primary)) {
                    Text(if (isScanning) "■ 停止搜索" else "⌁  搜索蓝牙设备", color = Color.White, fontSize = 15.sp)
                }
                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = onConnectClick,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(40.dp),
                    shape = RoundedCornerShape(14.dp),
                    enabled = !isScanning && (isConnected || selectedDevice != null),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isConnected) Color(0xFFC62828) else Color(0xFF2E7D32),
                        disabledContainerColor = Color(0xFFCCCCCC)
                    )
                ) {
                    Text(
                        when {
                            isConnecting -> "连接中..."
                            isConnected -> "断开设备"
                            selectedDevice != null -> "连接：${selectedDevice!!.name}"
                            else -> "请先点击设备"
                        },
                        color = Color.White, fontSize = 15.sp
                    )
                }
                Spacer(Modifier.height(12.dp))

                // 设备列表标题
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("附近设备", color = BeigeColors.text, style = MaterialTheme.typography.bodyMedium)
                    Text("${devices.size} 台", color = BeigeColors.hint, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(6.dp)); HorizontalDivider(color = BeigeColors.border)

                // 设备列表
                if (devices.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(if (isScanning) "正在搜索..." else if (isConnected) "设备已连接" else "未发现设备",
                            color = BeigeColors.hint, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    devices.forEach { item ->
                        val isSelected = selectedDevice?.address == item.address
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp)
                                .clickable { onDeviceClick(item) }
                                .then(if (isSelected) Modifier.border(2.dp, BeigeColors.primary, RoundedCornerShape(12.dp)) else Modifier),
                            colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFFFFF3D6) else Color.White),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(Modifier.padding(10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, color = BeigeColors.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Spacer(Modifier.height(2.dp))
                                    Text(item.address, color = BeigeColors.hint, style = MaterialTheme.typography.bodySmall)
                                }
                                Text("${item.rssi} dBm", color = BeigeColors.hint, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

// ─── 工具函数 ────────────────────────────────────────────────────────

private fun requiredBluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
}

private fun hasBluetoothPermissions(context: Context): Boolean =
    requiredBluetoothPermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

private fun parseHexInput(input: String): ByteArray? {
    val n = input.trim().replace("\n", " ").replace("\r", " ")
    if (n.isBlank()) return byteArrayOf()
    return try { n.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray() } catch (_: Exception) { null }
}

// ─── BLE 连接事件 ──────────────────────────────────────────────────────

private sealed class BleConnEvent {
    data class Connected(val item: BleDeviceItem) : BleConnEvent()
    data class Disconnected(val reason: String) : BleConnEvent()
    data class Received(val bytes: ByteArray) : BleConnEvent()
    data class Sent(val bytes: ByteArray) : BleConnEvent()
}

// ─── 真实 BLE 连接器 ───────────────────────────────────────────────────

private class BleConnector(private val context: Context) {
    companion object { private const val TAG = "BleConnector" }
    private val adapter: BluetoothAdapter? = (context.getSystemService(BluetoothManager::class.java))?.adapter
    private val mainHandler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var listener: ((BleConnEvent) -> Unit)? = null
    private var connectedItem: BleDeviceItem? = null

    private val serviceUuids = listOf(
        UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB"),
        UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
        UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
    )
    private val notifyUuids = listOf(
        UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB"),
        UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"),
        UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")
    )
    private val writeUuids = listOf(
        UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB"),
        UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
        UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB")
    )

    fun setEventListener(l: ((BleConnEvent) -> Unit)?) { listener = l }
    private fun emit(e: BleConnEvent) { mainHandler.post { listener?.invoke(e) } }

    @SuppressLint("MissingPermission")
    fun connect(item: BleDeviceItem) {
        disconnect()
        connectedItem = item
        val device = item.device ?: run { emit(BleConnEvent.Disconnected("设备实例为空")); return }
        gatt = if (Build.VERSION.SDK_INT >= 23) device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(context, false, gattCallback)
        if (gatt == null) emit(BleConnEvent.Disconnected("连接失败"))
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        notifyChar = null; writeChar = null; connectedItem = null
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    @SuppressLint("MissingPermission")
    fun send(bytes: ByteArray): Boolean {
        val g = gatt ?: return false
        // 收集所有可写特征
        val writable = mutableListOf<BluetoothGattCharacteristic>()
        g.services.orEmpty().forEach { svc ->
            svc.characteristics.forEach { c ->
                if (c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                    writable.add(c)
                }
            }
        }
        // 如果没找到可写特征，尝试用 notifyChar
        if (writable.isEmpty()) notifyChar?.let { writable.add(it) }
        if (writable.isEmpty()) return false

        for (c in writable) {
            @Suppress("DEPRECATION")
            c.value = bytes
            @Suppress("DEPRECATION")
            c.writeType = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            try {
                @Suppress("DEPRECATION")
                if (g.writeCharacteristic(c)) {
                    emit(BleConnEvent.Sent(bytes)); return true
                }
            } catch (_: Exception) { continue }
        }
        return false
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    notifyChar = null; writeChar = null
                    try { gatt.close() } catch (_: Exception) {}
                    if (this@BleConnector.gatt == gatt) this@BleConnector.gatt = null
                    emit(BleConnEvent.Disconnected(if (status != 0) "连接断开（错误码 $status）" else "设备已断开连接"))
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emit(BleConnEvent.Disconnected("服务发现失败"))
                return
            }
            val services = gatt.services.orEmpty()
            notifyChar = findNotifyChar(services)
            writeChar = findWriteChar(services) ?: notifyChar
            if (notifyChar == null) {
                emit(BleConnEvent.Disconnected("未找到通知特征"))
                return
            }
            enableNotification(gatt, notifyChar!!)
            // 请求 MTU 提升（默认 23 字节，提升后减少分包）
            try { gatt.requestMtu(512) } catch (_: Exception) {}
            // 通知上层已连接
            connectedItem?.let { item ->
                mainHandler.post { listener?.invoke(BleConnEvent.Connected(item)) }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU 协商完成: $mtu")
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            emit(BleConnEvent.Received(value))
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            emit(BleConnEvent.Received(characteristic.value ?: byteArrayOf()))
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotification(gatt: BluetoothGatt, c: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(c, true)
        c.getDescriptor(CCCD_UUID)?.let {
            it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            try { gatt.writeDescriptor(it) } catch (_: Exception) {}
        }
    }

    private fun findNotifyChar(services: List<BluetoothGattService>): BluetoothGattCharacteristic? {
        serviceUuids.forEach { suid ->
            services.firstOrNull { it.uuid == suid }?.let { svc ->
                notifyUuids.forEach { cuid -> svc.getCharacteristic(cuid)?.let { return it } }
            }
        }
        services.forEach { svc -> svc.characteristics.firstOrNull {
            it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        }?.let { return it } }
        return null
    }

    private fun findWriteChar(services: List<BluetoothGattService>): BluetoothGattCharacteristic? {
        serviceUuids.forEach { suid ->
            services.firstOrNull { it.uuid == suid }?.let { svc ->
                writeUuids.forEach { cuid -> svc.getCharacteristic(cuid)?.let { return it } }
            }
        }
        services.forEach { svc -> svc.characteristics.firstOrNull {
            it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        }?.let { return it } }
        return null
    }
}

// ─── BLE 扫描 ───────────────────────────────────────────────────────

private class BleHelper(private val context: Context) {
    private val bluetoothManager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanCallback: ScanCallback? = null
    private var scanTimeoutRunnable: Runnable? = null

    @SuppressLint("MissingPermission")
    fun startScan(onDeviceFound: (BleDeviceItem) -> Unit, onFinished: () -> Unit) {
        stopScan()
        val scanner = adapter?.bluetoothLeScanner ?: run { onFinished(); return }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device ?: return
                onDeviceFound(BleDeviceItem(d.name ?: "未知蓝牙设备", d.address, result.rssi, d))
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { r -> r.device?.let { onDeviceFound(BleDeviceItem(it.name ?: "未知蓝牙设备", it.address, r.rssi, it)) } }
            }
            override fun onScanFailed(@Suppress("UNUSED_PARAMETER") errorCode: Int) { onFinished() }
        }
        scanCallback = callback
        try { scanner.startScan(callback) } catch (_: Exception) { onFinished(); return }
        scanTimeoutRunnable = Runnable { stopScan(); onFinished() }.also { mainHandler.postDelayed(it, 10_000) }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }; scanTimeoutRunnable = null
        scanCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) }; scanCallback = null
    }
}
