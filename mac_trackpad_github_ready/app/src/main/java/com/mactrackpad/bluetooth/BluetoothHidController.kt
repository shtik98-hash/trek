package com.mactrackpad.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

/**
 * Керує нативним профілем Bluetooth HID (Mouse + Keyboard Composite Device).
 * Завдяки цьому Mac сприймає Android-смартфон як офіційний комбінований тачпад/клавіатуру
 * без жодних додаткових драйверів чи серверів на macOS.
 */
@SuppressLint("MissingPermission")
class BluetoothHidController(context: Context) {

    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var hidDevice: BluetoothHidDevice? = null
    private var hostDevice: BluetoothDevice? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _connectionState = MutableStateFlow("Відключено")
    val connectionState: StateFlow<String> = _connectionState

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName

    private val _pairedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val pairedDevices: StateFlow<List<BluetoothDevice>> = _pairedDevices

    private val _lastSentEvent = MutableStateFlow("Очікування жестів...")
    val lastSentEvent: StateFlow<String> = _lastSentEvent

    companion object {
        const val REPORT_ID_MOUSE: Int = 1
        const val REPORT_ID_KEYBOARD: Int = 2

        const val MOD_NONE: Byte = 0x00
        const val MOD_LCTRL: Byte = 0x01      // Control (^)
        const val MOD_LSHIFT: Byte = 0x02     // Shift (⇧)
        const val MOD_LALT: Byte = 0x04       // Option (⌥)
        const val MOD_LGUI: Byte = 0x08       // Command (⌘)

        const val KEY_NONE: Byte = 0x00
        const val KEY_D: Byte = 0x07
        const val KEY_N: Byte = 0x11
        const val KEY_R: Byte = 0x15
        const val KEY_0: Byte = 0x27
        const val KEY_SPACE: Byte = 0x2C
        const val KEY_MINUS: Byte = 0x2D      // Zoom Out (⌘ -)
        const val KEY_EQUAL: Byte = 0x2E      // Zoom In (⌘ +)
        const val KEY_LBRACKET: Byte = 0x2F   // Назад у браузері/Finder (⌘ [)
        const val KEY_RBRACKET: Byte = 0x30   // Вперед у браузері/Finder (⌘ ])
        const val KEY_F3: Byte = 0x3C         // Mission Control / Show Desktop
        const val KEY_F4: Byte = 0x3D         // Launchpad
        const val KEY_RIGHT: Byte = 0x4F      // Стрілка вправо
        const val KEY_LEFT: Byte = 0x50       // Стрілка вліво
        const val KEY_DOWN: Byte = 0x51       // Стрілка вниз (App Exposé)
        const val KEY_UP: Byte = 0x52         // Стрілка вгору (Mission Control)

        val COMPOSITE_HID_DESCRIPTOR = byteArrayOf(
            // ================= MOUSE / TRACKPAD COLLECTION (Report ID 1) =================
            0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
            0x09.toByte(), 0x02.toByte(), // Usage (Mouse)
            0xA1.toByte(), 0x01.toByte(), // Collection (Application)
            0x85.toByte(), 0x01.toByte(), //   Report ID (1)
            0x09.toByte(), 0x01.toByte(), //   Usage (Pointer)
            0xA1.toByte(), 0x00.toByte(), //   Collection (Physical)
            0x05.toByte(), 0x09.toByte(), //     Usage Page (Buttons)
            0x19.toByte(), 0x01.toByte(), //     Usage Minimum (1)
            0x29.toByte(), 0x03.toByte(), //     Usage Maximum (3)
            0x15.toByte(), 0x00.toByte(), //     Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(), //     Logical Maximum (1)
            0x95.toByte(), 0x03.toByte(), //     Report Count (3)
            0x75.toByte(), 0x01.toByte(), //     Report Size (1)
            0x81.toByte(), 0x02.toByte(), //     Input (Data, Variable, Absolute)
            0x95.toByte(), 0x01.toByte(), //     Report Count (1)
            0x75.toByte(), 0x05.toByte(), //     Report Size (5)
            0x81.toByte(), 0x03.toByte(), //     Input (Constant)
            0x05.toByte(), 0x01.toByte(), //     Usage Page (Generic Desktop)
            0x09.toByte(), 0x30.toByte(), //     Usage (X)
            0x09.toByte(), 0x31.toByte(), //     Usage (Y)
            0x09.toByte(), 0x38.toByte(), //     Usage (Wheel)
            0x15.toByte(), 0x81.toByte(), //     Logical Minimum (-127)
            0x25.toByte(), 0x7F.toByte(), //     Logical Maximum (127)
            0x75.toByte(), 0x08.toByte(), //     Report Size (8)
            0x95.toByte(), 0x03.toByte(), //     Report Count (3)
            0x81.toByte(), 0x06.toByte(), //     Input (Data, Variable, Relative)
            0x05.toByte(), 0x0C.toByte(), //     Usage Page (Consumer Devices)
            0x0A.toByte(), 0x38.toByte(), 0x02.toByte(), // Usage (AC Pan)
            0x15.toByte(), 0x81.toByte(), //     Logical Minimum (-127)
            0x25.toByte(), 0x7F.toByte(), //     Logical Maximum (127)
            0x75.toByte(), 0x08.toByte(), //     Report Size (8)
            0x95.toByte(), 0x01.toByte(), //     Report Count (1)
            0x81.toByte(), 0x06.toByte(), //     Input (Data, Variable, Relative)
            0xC0.toByte(),                //   End Collection (Physical)
            0xC0.toByte(),                // End Collection (Application)

            // ================= KEYBOARD COLLECTION (Report ID 2) =================
            0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
            0x09.toByte(), 0x06.toByte(), // Usage (Keyboard)
            0xA1.toByte(), 0x01.toByte(), // Collection (Application)
            0x85.toByte(), 0x02.toByte(), //   Report ID (2)
            0x05.toByte(), 0x07.toByte(), //   Usage Page (Key Codes)
            0x19.toByte(), 0xE0.toByte(), //   Usage Minimum (224 - Left Control)
            0x29.toByte(), 0xE7.toByte(), //   Usage Maximum (231 - Right GUI/Command)
            0x15.toByte(), 0x00.toByte(), //   Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(), //   Logical Maximum (1)
            0x75.toByte(), 0x01.toByte(), //   Report Size (1)
            0x95.toByte(), 0x08.toByte(), //   Report Count (8)
            0x81.toByte(), 0x02.toByte(), //   Input (Data, Variable, Absolute)
            0x95.toByte(), 0x01.toByte(), //   Report Count (1)
            0x75.toByte(), 0x08.toByte(), //   Report Size (8)
            0x81.toByte(), 0x01.toByte(), //   Input (Constant)
            0x95.toByte(), 0x06.toByte(), //   Report Count (6)
            0x75.toByte(), 0x08.toByte(), //   Report Size (8)
            0x15.toByte(), 0x00.toByte(), //   Logical Minimum (0)
            0x25.toByte(), 0x65.toByte(), //   Logical Maximum (101)
            0x05.toByte(), 0x07.toByte(), //   Usage Page (Key Codes)
            0x19.toByte(), 0x00.toByte(), //   Usage Minimum (0)
            0x29.toByte(), 0x65.toByte(), //   Usage Maximum (101)
            0x81.toByte(), 0x00.toByte(), //   Input (Data, Array)
            0xC0.toByte()                 // End Collection (Application)
        )
    }

    private var isDraggingButtonHeld = false

    fun initialize() {
        refreshPairedDevices()
        bluetoothAdapter?.getProfileProxy(
            appContext,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        hidDevice = proxy as BluetoothHidDevice
                        registerHidApp()
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        hidDevice = null
                        _connectionState.value = "Сервіс HID зупинено"
                    }
                }
            },
            BluetoothProfile.HID_DEVICE
        )
    }

    fun refreshPairedDevices() {
        val bonded = bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        _pairedDevices.value = bonded
    }

    private fun registerHidApp() {
        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            "Mac Magic Trackpad (Android)",
            "Apple-Style Multi-Touch Bluetooth HID Trackpad",
            "Android Open HID",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            COMPOSITE_HID_DESCRIPTOR
        )

        val qosSettings = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX
        )

        hidDevice?.registerApp(
            sdpSettings,
            null,
            qosSettings,
            executor,
            object : BluetoothHidDevice.Callback() {
                override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                    _connectionState.value = if (registered) "Готовий до підключення (HID активний)" else "Помилка реєстрації HID"
                    if (registered && pluggedDevice != null) {
                        connectToMac(pluggedDevice)
                    }
                }

                override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                    when (state) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            hostDevice = device
                            _connectedDeviceName.value = device?.name ?: "Mac"
                            _connectionState.value = "Підключено до ${device?.name ?: "Mac"}"
                        }
                        BluetoothProfile.STATE_CONNECTING -> {
                            _connectionState.value = "Підключення до ${device?.name ?: "Mac"}..."
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            if (hostDevice == device) {
                                hostDevice = null
                                _connectedDeviceName.value = null
                            }
                            _connectionState.value = "Відключено"
                        }
                    }
                }
            }
        )
    }

    fun connectToMac(device: BluetoothDevice) {
        hidDevice?.connect(device)
    }

    fun disconnect() {
        hostDevice?.let { hidDevice?.disconnect(it) }
    }

    fun sendMouseReport(
        leftButton: Boolean = isDraggingButtonHeld,
        rightButton: Boolean = false,
        middleButton: Boolean = false,
        dx: Int = 0,
        dy: Int = 0,
        verticalScroll: Int = 0,
        horizontalScroll: Int = 0
    ) {
        val device = hostDevice ?: return
        val hid = hidDevice ?: return

        var buttons = 0
        if (leftButton) buttons = buttons or 1
        if (rightButton) buttons = buttons or 2
        if (middleButton) buttons = buttons or 4

        val report = byteArrayOf(
            buttons.toByte(),
            dx.coerceIn(-127, 127).toByte(),
            dy.coerceIn(-127, 127).toByte(),
            verticalScroll.coerceIn(-127, 127).toByte(),
            horizontalScroll.coerceIn(-127, 127).toByte()
        )
        hid.sendReport(device, REPORT_ID_MOUSE, report)
    }

    fun performClick(left: Boolean = false, right: Boolean = false, middle: Boolean = false, label: String) {
        _lastSentEvent.value = label
        sendMouseReport(leftButton = left, rightButton = right, middleButton = middle)
        mainHandler.postDelayed({
            sendMouseReport(leftButton = false, rightButton = false, middleButton = false)
        }, 25)
    }

    fun setThreeFingerDragState(active: Boolean) {
        if (isDraggingButtonHeld != active) {
            isDraggingButtonHeld = active
            sendMouseReport(leftButton = active)
            _lastSentEvent.value = if (active) "3 пальці: Початок перетягування (Drag)" else "3 пальці: Завершення перетягування"
        }
    }

    fun sendMacShortcut(modifier: Int, keyCode: Byte, gestureDescription: String) {
        val device = hostDevice
        val hid = hidDevice
        _lastSentEvent.value = gestureDescription

        if (device == null || hid == null) return

        val pressReport = byteArrayOf(
            modifier.toByte(),
            0x00,
            keyCode,
            0x00, 0x00, 0x00, 0x00, 0x00
        )
        val releaseReport = ByteArray(8) { 0x00 }

        hid.sendReport(device, REPORT_ID_KEYBOARD, pressReport)
        mainHandler.postDelayed({
            hid.sendReport(device, REPORT_ID_KEYBOARD, releaseReport)
        }, 35)
    }

    fun triggerMissionControl() {
        sendMacShortcut(MOD_LCTRL.toInt(), KEY_UP, "Mission Control (Свайп 3/4 пальцями вгору)")
    }

    fun triggerAppExpose() {
        sendMacShortcut(MOD_LCTRL.toInt(), KEY_DOWN, "App Exposé (Свайп 3/4 пальцями вниз)")
    }

    fun triggerPreviousSpace() {
        sendMacShortcut(MOD_LCTRL.toInt(), KEY_LEFT, "Попередній робочий стіл (Свайп 3/4 пальцями вправо)")
    }

    fun triggerNextSpace() {
        sendMacShortcut(MOD_LCTRL.toInt(), KEY_RIGHT, "Наступний робочий стіл (Свайп 3/4 пальцями вліво)")
    }

    fun triggerLaunchpad() {
        sendMacShortcut(MOD_NONE.toInt(), KEY_F4, "Launchpad (Щипок 4 пальцями всередину)")
    }

    fun triggerShowDesktop() {
        sendMacShortcut(MOD_LGUI.toInt(), KEY_F3, "Показати Робочий стіл (Розведення 4 пальців)")
    }

    fun triggerLookUpOrQuickLook() {
        sendMacShortcut(MOD_LCTRL.toInt() or MOD_LGUI.toInt(), KEY_D, "Force Touch / Довідка (Ctrl+Cmd+D)")
    }

    private var isSmartZoomed = false
    fun triggerSmartZoom() {
        isSmartZoomed = !isSmartZoomed
        if (isSmartZoomed) {
            sendMacShortcut(MOD_LGUI.toInt(), KEY_EQUAL, "Smart Zoom: Збільшити (Подвійний тап 2 пальцями)")
        } else {
            sendMacShortcut(MOD_LGUI.toInt(), KEY_0, "Smart Zoom: 100% (Подвійний тап 2 пальцями)")
        }
    }

    fun triggerPinchZoom(zoomIn: Boolean) {
        if (zoomIn) {
            sendMacShortcut(MOD_LGUI.toInt(), KEY_EQUAL, "Збільшення масштабу (Pinch In ⌘+)")
        } else {
            sendMacShortcut(MOD_LGUI.toInt(), KEY_MINUS, "Зменшення масштабу (Pinch Out ⌘-)")
        }
    }

    fun triggerRotate(clockwise: Boolean) {
        val mod = if (clockwise) MOD_LGUI.toInt() else (MOD_LGUI.toInt() or MOD_LSHIFT.toInt())
        sendMacShortcut(mod, KEY_R, if (clockwise) "Обертання за годинниковою стрілкою (2 пальці)" else "Обертання проти годинникової стрілки (2 пальці)")
    }

    fun triggerNotificationCenter() {
        sendMacShortcut(
            MOD_LCTRL.toInt() or MOD_LALT.toInt() or MOD_LGUI.toInt(),
            KEY_N,
            "Центр сповіщень (Свайп 2 пальцями від правого краю)"
        )
    }

    fun triggerSpotlight() {
        sendMacShortcut(MOD_LGUI.toInt(), KEY_SPACE, "Пошук Spotlight (⌘ + Пробіл)")
    }
}
