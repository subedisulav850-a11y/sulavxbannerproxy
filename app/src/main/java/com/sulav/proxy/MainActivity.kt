package com.sulav.proxy

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.graphics.drawable.GradientDrawable
import android.widget.*
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

private const val BG = 0xFF080B12.toInt()
private const val PANEL = 0xFF111827.toInt()
private const val PANEL2 = 0xFF172235.toInt()
private const val PURPLE = 0xFF8B55F5.toInt()
private const val PURPLE2 = 0xFFA78BFA.toInt()
private const val CYAN = 0xFF67D9E8.toInt()
private const val TEXT = 0xFFF2F4F8.toInt()
private const val MUTED = 0xFF8D96A8.toInt()
private const val GREEN = 0xFF52D273.toInt()
private const val RED = 0xFFE45D6A.toInt()

private const val DEFAULT_TARGET = "https://clientbp.ggpolarbear.com/"

data class Capture(
    val method: String,
    val url: String,
    val status: Int,
    val requestHex: String,
    val responseHex: String,
    val headers: String,
    val requestBytes: Long = 0,
    val responseBytes: Long = 0
)

class MainActivity : Activity() {

    private val executor = Executors.newCachedThreadPool()
    private val captures = CaptureStore.load(filesDir)

    private lateinit var content: LinearLayout
    private lateinit var packetCountView: TextView

    private var proxyRunning = false
    private var target = DEFAULT_TARGET
    private var overlayEnabled = false

    /*
     * Navigation state.
     *
     * CAPTURE
     *   └── CAPTURE_LOG
     *         └── DETAIL
     *
     * Other screens return to CAPTURE when Back is pressed.
     */
    private var currentScreen = "CAPTURE"
    private var lastBackAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = BG
        window.navigationBarColor = BG

        target = getPreferences(0)
            .getString("target", target) ?: target

        buildShell()
        showCapture()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    /*
     * FIXED BACK HANDLING
     *
     * Detail -> Capture Log
     * Capture Log -> Capture
     * Any other page -> Capture
     *
     * On Capture:
     * first Back = stay in app
     * second Back within 1.8 seconds = exit
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {

        when (currentScreen) {

            "DETAIL" -> {
                showCaptureLog()
                return
            }

            "CAPTURE_LOG" -> {
                showCapture()
                return
            }

            "REQUEST",
            "DECODER",
            "DECODED",
            "UPDATES",
            "ACCOUNT" -> {
                showCapture()
                return
            }
        }

        val now = System.currentTimeMillis()

        if (now - lastBackAt < 1800L) {
            lastBackAt = 0L
            super.onBackPressed()
        } else {
            lastBackAt = now
            toast("Press back again to exit")
        }
    }

    private fun buildShell() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(18),
                dp(8),
                dp(18),
                dp(8)
            )
            setBackgroundColor(PANEL)
        }

        top.addView(
            TextView(this).apply {
                text = "◆"
                textSize = 24f
                setTextColor(PURPLE2)
            },
            LinearLayout.LayoutParams(
                dp(42),
                dp(54)
            )
        )

        top.addView(
            TextView(this).apply {
                text = "Sulav Proxy"
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(TEXT)
            },
            LinearLayout.LayoutParams(
                0,
                dp(54),
                1f
            )
        )

        packetCountView = TextView(this).apply {
            text = "0 packets"
            textSize = 11f
            setTextColor(MUTED)
        }

        top.addView(packetCountView)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        root.addView(top)

        root.addView(
            ScrollView(this).apply {
                addView(content)
            },
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(PANEL)
            setPadding(
                dp(6),
                dp(6),
                dp(6),
                dp(6)
            )
        }

        listOf(
            "Capture",
            "Request",
            "Decoder",
            "Updates",
            "Account"
        ).forEach { name ->

            val view = TextView(this).apply {
                text = name.uppercase(Locale.US)
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(TEXT)
            }

            view.setOnClickListener {

                when (name) {

                    "Capture" -> showCapture()

                    "Request" -> showRequest()

                    "Decoder" -> showDecoder()

                    "Updates" -> showUpdates()

                    "Account" -> showAccount()
                }
            }

            nav.addView(
                view,
                LinearLayout.LayoutParams(
                    0,
                    dp(58),
                    1f
                )
            )
        }

        root.addView(nav)

        setContentView(root)
    }

    private fun reset(
        title: String,
        subtitle: String? = null
    ) {
        content.removeAllViews()

        addText(
            title,
            25f,
            TEXT,
            true,
            18,
            14
        )

        if (subtitle != null) {
            addText(
                subtitle,
                13f,
                MUTED,
                false,
                18,
                2
            )
        }
    }

    private fun addText(
        textValue: String,
        size: Float,
        color: Int,
        bold: Boolean = false,
        left: Int = 18,
        top: Int = 8
    ) {

        content.addView(
            TextView(this).apply {
                text = textValue
                textSize = size
                setTextColor(color)

                if (bold) {
                    typeface = Typeface.DEFAULT_BOLD
                }

                setPadding(
                    dp(left),
                    dp(top),
                    dp(18),
                    dp(5)
                )
            }
        )
    }

    private fun card(): LinearLayout {

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(18),
                dp(16),
                dp(18),
                dp(16)
            )

            background = rounded(
                PANEL,
                0xFF27334A.toInt(),
                1,
                20
            )

            layoutParams = LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                setMargins(
                    dp(18),
                    dp(10),
                    dp(18),
                    dp(10)
                )
            }
        }
    }

    private fun rounded(
        fill: Int,
        stroke: Int,
        width: Int,
        radius: Int
    ): GradientDrawable {

        return GradientDrawable().apply {
            setColor(fill)
            setStroke(
                dp(width),
                stroke
            )
            cornerRadius = dp(radius).toFloat()
        }
    }

    private fun label(textValue: String) {
        addText(
            textValue,
            12f,
            CYAN,
            true,
            36,
            2
        )
    }

    private fun edit(
        hintText: String,
        value: String = "",
        multi: Boolean = false
    ): EditText {

        return EditText(this).apply {

            hint = hintText
            setText(value)

            textSize = 14f

            setTextColor(TEXT)
            setHintTextColor(0xFF657084.toInt())

            setPadding(
                dp(14),
                dp(10),
                dp(14),
                dp(10)
            )

            setBackgroundColor(PANEL2)

            if (multi) {

                minLines = 6
                gravity = Gravity.TOP

                inputType =
                    InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE

            } else {

                inputType =
                    InputType.TYPE_CLASS_TEXT
            }
        }
    }

    private fun button(
        textValue: String,
        action: () -> Unit
    ): Button {

        return Button(this).apply {

            text = textValue
            textSize = 12f
            setTextColor(Color.WHITE)

            background = rounded(
                PURPLE,
                PURPLE,
                1,
                16
            )

            isAllCaps = false

            setOnClickListener {
                action()
            }

            stateListAnimator = null

            layoutParams = LinearLayout.LayoutParams(
                -1,
                dp(54)
            ).apply {
                setMargins(
                    0,
                    dp(8),
                    0,
                    0
                )
            }
        }
    }

    private fun addTextTo(
        parent: LinearLayout,
        textValue: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ) {

        parent.addView(
            TextView(this).apply {

                text = textValue
                textSize = size
                setTextColor(color)

                if (bold) {
                    typeface = Typeface.DEFAULT_BOLD
                }

                setPadding(
                    0,
                    dp(5),
                    0,
                    dp(5)
                )
            }
        )
    }

    private fun showCapture() {

        currentScreen = "CAPTURE"

        reset(
            "CAPTURE CONSOLE",
            if (proxyRunning)
                "RUNNING • 0.0.0.0:$proxyPort • HTTPS tunnel ready"
            else
                "IDLE • background proxy service"
        )

        val c = card()

        label("FORWARD TARGET")

        val targetEdit = edit(
            "https://clientbp.ggpolarbear.com/",
            target
        )

        c.addView(targetEdit)

        c.addView(
            button("SAVE TARGET") {

                target = targetEdit.text
                    .toString()
                    .trim()

                getPreferences(0)
                    .edit()
                    .putString("target", target)
                    .apply()

                toast("Target saved")
            }
        )

        label("LOCAL CAPTURE")

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val start = button(
            if (proxyRunning)
                "STOP PROXY"
            else
                "START PROXY"
        ) {

            if (proxyRunning)
                stopProxy()
            else
                startProxy()
        }

        row.addView(
            start,
            LinearLayout.LayoutParams(
                0,
                dp(54),
                1f
            )
        )

        val clear = button("CLEAR LOG") {

            CaptureStore.clear(filesDir)
            captures.clear()

            updatePacketCount()
            showCapture()
        }

        row.addView(
            clear,
            LinearLayout.LayoutParams(
                0,
                dp(54),
                1f
            )
        )

        c.addView(row)

        c.addView(
            button("VIEW CAPTURED REQUESTS") {
                showCaptureLog()
            }
        )

        content.addView(c)

        addText(
            if (captures.isEmpty())
                "NO PACKETS YET\n\nStart the local proxy or use Request to test an endpoint you control."
            else
                "${captures.size} captured request(s)",
            14f,
            MUTED,
            false,
            20,
            22
        )

        updatePacketCount()
    }

    private val proxyPort: Int
        get() =
            getPreferences(0)
                .getString("proxy_port", "8080")
                ?.toIntOrNull()
                ?: 8080

    private fun updatePacketCount() {

        if (::packetCountView.isInitialized) {
            packetCountView.text =
                "${captures.size} packets"
        }
    }

    private fun startProxy() {

        val intent = Intent(
            this,
            ProxyService::class.java
        ).apply {

            action = ProxyService.ACTION_START

            putExtra(
                ProxyService.EXTRA_TARGET,
                target
            )

            putExtra(
                ProxyService.EXTRA_PORT,
                proxyPort
            )

            putExtra(
                ProxyService.EXTRA_OVERLAY,
                overlayEnabled
            )
        }

        if (android.os.Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        proxyRunning = true

        updatePacketCount()
        showCapture()
    }

    private fun stopProxy() {

        stopService(
            Intent(
                this,
                ProxyService::class.java
            ).apply {
                action = ProxyService.ACTION_STOP
            }
        )

        proxyRunning = false

        showCapture()
    }

    private fun showCaptureLog() {

        currentScreen = "CAPTURE_LOG"

        reset(
            "CAPTURED PACKETS",
            "${captures.size} request(s)"
        )

        captures.asReversed().forEach { capture ->

            val c = card()

            addTextTo(
                c,
                "${capture.method}  ${capture.status}",
                14f,
                if (capture.status in 200..399)
                    GREEN
                else
                    RED,
                true
            )

            addTextTo(
                c,
                capture.url,
                12f,
                TEXT
            )

            addTextTo(
                c,
                "${capture.requestBytes} B → ${capture.responseBytes} B",
                11f,
                MUTED
            )

            c.addView(
                button("VIEW DETAIL") {
                    showDetail(capture)
                }
            )

            content.addView(c)
        }
    }

    private fun showDetail(capture: Capture) {

        currentScreen = "DETAIL"

        reset(
            "REQUEST DETAIL",
            "${capture.method} ${capture.url}"
        )

        val c = card()

        addTextTo(
            c,
            "STATUS ${capture.status}",
            14f,
            if (capture.status in 200..399)
                GREEN
            else
                RED,
            true
        )

        addTextTo(
            c,
            "HEADERS",
            12f,
            CYAN,
            true
        )

        addTextTo(
            c,
            capture.headers.ifBlank { "—" },
            11f,
            TEXT
        )

        addTextTo(
            c,
            "REQUEST HEX",
            12f,
            CYAN,
            true
        )

        addTextTo(
            c,
            capture.requestHex.ifBlank { "—" },
            10f,
            TEXT
        )

        addTextTo(
            c,
            "RESPONSE HEX",
            12f,
            CYAN,
            true
        )

        addTextTo(
            c,
            capture.responseHex.ifBlank { "—" },
            10f,
            TEXT
        )

        addTextTo(
            c,
            "HTTPS payload remains encrypted; only tunnel metadata and byte counts are shown.",
            10f,
            MUTED
        )

        content.addView(c)
    }

    private fun showRequest() {

        currentScreen = "REQUEST"

        reset(
            "REQUEST SEND",
            "Send a request to an endpoint you control."
        )

        val c = card()

        label("REQUEST BLOCK")

        val method = Spinner(this).apply {

            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf(
                    "GET",
                    "POST",
                    "PUT",
                    "DELETE"
                )
            )
        }

        c.addView(method)

        val url = edit(
            "URL",
            target
        )

        c.addView(url)

        val headers = edit(
            "Headers (Name: Value per line)",
            "",
            true
        )

        c.addView(headers)

        val body = edit(
            "Payload / body",
            "",
            true
        )

        c.addView(body)

        c.addView(
            button("SEND REQUEST") {

                sendRequest(
                    method.selectedItem.toString(),
                    url.text.toString().trim(),
                    headers.text.toString(),
                    body.text.toString()
                )
            }
        )

        content.addView(c)

        addText(
            "For safety, this sender does not extract authentication tokens or modify third-party game traffic.",
            12f,
            MUTED,
            false,
            20,
            12
        )
    }

    private fun sendRequest(
        method: String,
        urlText: String,
        headerText: String,
        bodyText: String
    ) {

        /*
         * Remember where the request was started.
         *
         * If the user presses another navigation button while
         * the request is running, the response must NOT force
         * the UI back to the Detail page.
         */
        val screenWhenStarted = currentScreen

        executor.execute {

            var connection: HttpURLConnection? = null

            try {

                connection =
                    URL(urlText)
                        .openConnection() as HttpURLConnection

                connection.requestMethod = method

                connection.connectTimeout = 12000
                connection.readTimeout = 12000

                headerText.lines().forEach { line ->

                    val index = line.indexOf(':')

                    if (index > 0) {

                        connection.setRequestProperty(
                            line.substring(
                                0,
                                index
                            ).trim(),

                            line.substring(
                                index + 1
                            ).trim()
                        )
                    }
                }

                if (
                    method != "GET" &&
                    method != "DELETE"
                ) {

                    connection.doOutput = true

                    connection.outputStream.use {
                        it.write(
                            bodyText.toByteArray()
                        )
                    }
                }

                val status =
                    connection.responseCode

                val stream =
                    if (status >= 400)
                        connection.errorStream
                    else
                        connection.inputStream

                val bytes =
                    stream?.let {
                        BufferedInputStream(it).use { input ->
                            readAll(input)
                        }
                    } ?: ByteArray(0)

                val safeHeaders =
                    headerText.lines()
                        .joinToString("\n") { line ->

                            if (
                                line.lowercase(
                                    Locale.US
                                ).startsWith(
                                    "authorization:"
                                )
                            ) {
                                "Authorization: [REDACTED]"
                            } else {
                                line
                            }
                        }

                val capture = Capture(
                    method = method,
                    url = urlText,
                    status = status,
                    requestHex = hex(
                        bodyText.toByteArray(),
                        512
                    ),
                    responseHex = hex(
                        bytes,
                        512
                    ),
                    headers = safeHeaders
                )

                synchronized(captures) {
                    captures.add(capture)
                }

                runOnUiThread {

                    updatePacketCount()

                    toast("Response $status")

                    /*
                     * IMPORTANT:
                     * Only open Detail automatically if the user
                     * is still on the Request screen.
                     *
                     * This prevents the "auto back / auto page"
                     * feeling when navigating elsewhere.
                     */
                    if (currentScreen == screenWhenStarted &&
                        screenWhenStarted == "REQUEST"
                    ) {
                        showDetail(capture)
                    }
                }

            } catch (exception: Exception) {

                runOnUiThread {
                    toast(
                        "Request failed: ${
                            exception.message ?: "error"
                        }"
                    )
                }

            } finally {

                connection?.disconnect()
            }
        }
    }

    private fun showDecoder() {

        currentScreen = "DECODER"

        reset(
            "PROTOBUF DECODER",
            "Decode generic response bytes supplied by you."
        )

        val c = card()

        label("RESPONSE HEX")

        val input = edit(
            "08 03 12 …",
            "",
            true
        )

        c.addView(input)

        c.addView(
            button("DECODE") {
                showDecoded(
                    decodeHex(
                        input.text.toString()
                    )
                )
            }
        )

        c.addView(
            button("CLEAR") {
                input.setText("")
            }
        )

        content.addView(c)

        addText(
            "DECODED JSON / FALLBACK",
            12f,
            CYAN,
            true,
            20,
            16
        )

        addText(
            "—",
            12f,
            TEXT,
            false,
            20,
            6
        )
    }

    private fun showDecoded(bytes: ByteArray) {

        currentScreen = "DECODED"

        reset(
            "PROTOBUF DECODER",
            "Decoded byte content"
        )

        val c = card()

        addTextTo(
            c,
            "DECODED / FALLBACK",
            12f,
            CYAN,
            true
        )

        addTextTo(
            c,
            bytes.toString(
                Charsets.UTF_8
            ).ifBlank { "—" },
            12f,
            TEXT
        )

        addTextTo(
            c,
            "HEX",
            12f,
            CYAN,
            true
        )

        addTextTo(
            c,
            hex(bytes, 4096),
            10f,
            TEXT
        )

        c.addView(
            button("COPY HEX") {

                val clipboard =
                    getSystemService(
                        CLIPBOARD_SERVICE
                    ) as android.content.ClipboardManager

                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        "hex",
                        hex(bytes, 4096)
                    )
                )

                toast("Copied")
            }
        )

        content.addView(c)
    }

    private fun showUpdates() {

        currentScreen = "UPDATES"

        reset(
            "UPDATES",
            "App update information"
        )

        val c = card()

        addTextTo(
            c,
            "You are up to date",
            22f,
            PURPLE2,
            true
        )

        addTextTo(
            c,
            "Version 1.0 is the current build.",
            15f,
            TEXT
        )

        c.addView(
            button("CHECK AGAIN") {
                toast(
                    "No update endpoint configured"
                )
            }
        )

        content.addView(c)
    }

    private fun showAccount() {

        currentScreen = "ACCOUNT"

        reset(
            "SYSTEM & ACCOUNT",
            "Background service and floating capture controls"
        )

        val c = card()

        addTextTo(
            c,
            "BACKGROUND PROXY",
            12f,
            CYAN,
            true
        )

        addTextTo(
            c,
            if (proxyRunning)
                "Running in foreground service"
            else
                "Stopped",
            16f,
            if (proxyRunning)
                GREEN
            else
                MUTED,
            true
        )

        c.addView(
            button(
                if (proxyRunning)
                    "STOP BACKGROUND PROXY"
                else
                    "START BACKGROUND PROXY"
            ) {

                if (proxyRunning)
                    stopProxy()
                else
                    startProxy()
            }
        )

        c.addView(
            button(
                if (overlayEnabled)
                    "DISABLE FLOATING CAPTURE"
                else
                    "ENABLE FLOATING CAPTURE"
            ) {

                if (
                    !Settings.canDrawOverlays(this)
                ) {

                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse(
                                "package:$packageName"
                            )
                        )
                    )

                    toast(
                        "Allow display over other apps, then enable again"
                    )

                } else {

                    overlayEnabled =
                        !overlayEnabled

                    val actionIntent =
                        Intent(
                            this,
                            ProxyService::class.java
                        ).apply {

                            action =
                                if (overlayEnabled)
                                    ProxyService.ACTION_OVERLAY_ON
                                else
                                    ProxyService.ACTION_OVERLAY_OFF
                        }

                    startService(actionIntent)

                    showAccount()
                }
            }
        )

        content.addView(c)

        val https = card()

        addTextTo(
            https,
            "HTTPS CAPTURE",
            12f,
            CYAN,
            true
        )

        addTextTo(
            https,
            "CONNECT tunnel support is enabled. HTTPS stays encrypted end-to-end; the app records destination metadata, status and byte counts without decrypting credentials.",
            12f,
            TEXT
        )

        addTextTo(
            https,
            "Traffic is persistent and is not automatically deleted.",
            12f,
            GREEN,
            true
        )

        content.addView(https)

        val proxy = card()

        addTextTo(
            proxy,
            "PROXY SETUP",
            12f,
            CYAN,
            true
        )

        addTextTo(
            proxy,
            "Host: 127.0.0.1    Port: $proxyPort",
            15f,
            TEXT,
            true
        )

        addTextTo(
            proxy,
            "Configure the device/app you control to use this HTTP proxy. HTTPS uses CONNECT tunneling.",
            12f,
            MUTED
        )

        content.addView(proxy)

        val profile = card()

        addTextTo(
            profile,
            "PROFILE",
            12f,
            CYAN,
            true
        )

        addTextTo(
            profile,
            "Sulav Proxy Owner",
            20f,
            TEXT,
            true
        )

        addTextTo(
            profile,
            "Local profile • Device bound",
            13f,
            MUTED
        )

        profile.addView(
            button("CHANGE PHOTO") {

                startActivityForResult(
                    Intent(
                        Intent.ACTION_OPEN_DOCUMENT
                    ).apply {

                        type = "image/*"

                        addCategory(
                            Intent.CATEGORY_OPENABLE
                        )
                    },
                    1001
                )
            }
        )

        content.addView(profile)
    }

    private fun decodeHex(text: String): ByteArray {

        val cleaned =
            text
                .replace(
                    "0x",
                    "",
                    true
                )
                .replace(
                    Regex("[^0-9A-Fa-f]"),
                    ""
                )

        if (cleaned.length % 2 != 0) {
            return ByteArray(0)
        }

        return ByteArray(
            cleaned.length / 2
        ) { index ->

            cleaned.substring(
                index * 2,
                index * 2 + 2
            ).toInt(16).toByte()
        }
    }

    private fun hex(
        bytes: ByteArray,
        max: Int
    ): String {

        return bytes
            .copyOfRange(
                0,
                minOf(bytes.size, max)
            )
            .joinToString(" ") {
                String.format(
                    "%02X",
                    it
                )
            }
    }

    private fun readAll(
        input: BufferedInputStream
    ): ByteArray {

        val output =
            ByteArrayOutputStream()

        val buffer =
            ByteArray(8192)

        while (true) {

            val count =
                input.read(buffer)

            if (count <= 0) {
                break
            }

            output.write(
                buffer,
                0,
                count
            )
        }

        return output.toByteArray()
    }

    private fun toast(message: String) {

        Toast.makeText(
            this,
            message,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun dp(value: Int): Int {

        return (
            value *
            resources.displayMetrics.density
        ).toInt()
    }
}
