package io.github.thoitiet.emuse.exec

/**
 * Fixed root command builders in the style of Eta's RootShellDeviceController:
 * arguments are validated (never raw string concatenation), each builder
 * returns a complete `su -c` command string.
 */
object RootCommands {
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val CHMOD_MODE = Regex("^[0-7]{3,4}$")

    fun requirePackage(pkg: String): String {
        require(PACKAGE.matches(pkg)) { "invalid package name: $pkg" }
        return pkg
    }

    fun requireMode(mode: String): String {
        require(CHMOD_MODE.matches(mode)) { "invalid mode: $mode" }
        return mode
    }

    /** POSIX single-quote escaping for shell arguments. */
    fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun inputTap(x: Int, y: Int): String = "input tap $x $y"

    fun inputSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String =
        "input swipe $x1 $y1 $x2 $y2 ${durationMs.coerceIn(50, 10_000)}"

    fun inputKey(keyCode: Int): String {
        require(keyCode in 0..300) { "invalid keyCode: $keyCode" }
        return "input keyevent $keyCode"
    }

    /**
     * `input text` with safe quoting. Note the platform limits (also noted in
     * Eta): no Unicode, `%s` is not needed when the arg is quoted.
     */
    fun inputText(text: String): String {
        require(text.isNotEmpty() && text.length <= 500) { "text empty or too long" }
        return "input text ${shQuote(text)}"
    }

    fun forceStop(pkg: String): String = "am force-stop ${requirePackage(pkg)}"

    fun screenSize(): String = "wm size"

    fun screencapTo(tmpPng: String): String = "screencap -p ${shQuote(tmpPng)}"

    fun uiautomatorDump(tmpXml: String): String = "uiautomator dump ${shQuote(tmpXml)}"

    fun copyTo(src: String, dst: String, mode: String?): String = buildString {
        append("cp ${shQuote(src)} ${shQuote(dst)}")
        if (!mode.isNullOrEmpty()) append(" && chmod ${requireMode(mode)} ${shQuote(dst)}")
    }
}

/**
 * Cached screen dimensions (Eta's RootShellDeviceController.screenSize),
 * used to validate tap/swipe coordinates before dispatching.
 */
object DeviceScreen {
    @Volatile
    private var cached: Pair<Int, Int>? = null

    fun size(): Pair<Int, Int> {
        cached?.let { return it }
        val m = Regex("""(\d+)x(\d+)""")
            .find(ShellExecutor.exec(RootCommands.screenSize()).stdout)
        val size = if (m != null) {
            Pair(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        } else {
            Pair(0, 0)
        }
        cached = size
        return size
    }

    fun validatePoint(x: Int, y: Int) {
        val (w, h) = size()
        if (w > 0 && h > 0) {
            require(x in 0 until w && y in 0 until h) {
                "coordinates out of bounds: ($x,$y) not in ${w}x$h"
            }
        }
    }

    fun invalidate() {
        cached = null
    }
}

/** Eta's waitForUiSettle: let the UI settle after an action before returning. */
fun settleAfter(tool: String) {
    val delayMs = when (tool) {
        "tap", "key" -> 350L
        "swipe" -> 650L
        "text" -> 500L
        else -> 250L
    }
    try {
        Thread.sleep(delayMs)
    } catch (_: InterruptedException) {
    }
}
