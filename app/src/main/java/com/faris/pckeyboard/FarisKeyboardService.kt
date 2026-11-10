package com.faris.pckeyboard

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView

class FarisKeyboardService : InputMethodService() {

    private enum class Kind { CHAR, KEY, NP, SHIFT, CAPS, CTRL, ALT, WIN, LANG, NUMLOCK, PAGE_MAIN, PAGE_FN, PAGE_NUM }
    private enum class Page { MAIN, FN, NUM }

    private class K(
        val label: String,
        val shifted: String? = null,
        val code: Int = 0,
        val kind: Kind = Kind.CHAR,
        val weight: Float = 1f,
        val display: String? = null,
        val repeat: Boolean = false
    )

    private class Row(val keys: List<K>, val h: Float = 1f)

    private val navy = Color.parseColor("#0A1A33")
    private val keyColor = Color.parseColor("#1F5FD6")
    private val specialColor = Color.parseColor("#174AA8")
    private val activeColor = Color.parseColor("#E08A00")
    private val pressedColor = Color.parseColor("#6FA0FF")

    private var page = Page.MAIN
    private var arabic = false
    private var shift = false
    private var caps = false
    private var ctrl = false
    private var alt = false
    private var win = false
    private var numLock = true

    private lateinit var container: LinearLayout
    private val keyViews = ArrayList<Pair<TextView, K>>()
    private val handler = Handler(Looper.getMainLooper())
    private var repeatKey: K? = null
    private val repeater = object : Runnable {
        override fun run() {
            repeatKey?.let { onKey(it); handler.postDelayed(this, 45) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        arabic = getSharedPreferences("faris_kb", MODE_PRIVATE).getBoolean("arabic", false)
    }

    override fun onCreateInputView(): View = buildRoot()

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setInputView(buildRoot())
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        shift = false; ctrl = false; alt = false; win = false
        if (::container.isInitialized) refresh()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        stopRepeat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildRoot(): View {
        val h = minOf(dp(270), (resources.displayMetrics.heightPixels * 0.55f).toInt())
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(navy)
            setPadding(dp(3), dp(4), dp(3), dp(4))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
        }
        showPage()
        return container
    }

    private fun ch(label: String, code: Int, shifted: String? = null, w: Float = 1f) =
        K(label, shifted, code, Kind.CHAR, w)

    private fun key(label: String, code: Int, w: Float = 1f, rep: Boolean = false) =
        K(label, null, code, Kind.KEY, w, null, rep)

    private fun mod(label: String, kind: Kind, w: Float = 1f) =
        K(label = label, kind = kind, weight = w)

    private fun np(label: String, code: Int, w: Float = 1f) =
        K(label, null, code, Kind.NP, w)

    private fun codesOf(s: String) = s.map { KeyEvent.KEYCODE_A + (it - 'a') }

    private fun keysOf(chars: List<String>, codes: List<Int>, shifts: List<String?>): List<K> =
        chars.indices.map { ch(chars[it], codes[it], shifts.getOrNull(it)) }

    private fun nulls(n: Int): List<String?> = List(n) { null }

    private fun space(w: Float) = K(" ", null, KeyEvent.KEYCODE_SPACE, Kind.CHAR, w, "Space")

    private fun mainRows(): List<Row> {
        val f = ArrayList<K>()
        f += key("Esc", KeyEvent.KEYCODE_ESCAPE)
        for (i in 1..12) f += key("F$i", KeyEvent.KEYCODE_F1 + i - 1)

        val syms = "!@#\$%^&*()"
        val n = ArrayList<K>()
        n += if (arabic) ch("ذ", KeyEvent.KEYCODE_GRAVE) else ch("`", KeyEvent.KEYCODE_GRAVE, "~")
        for (i in 1..10) {
            val d = i % 10
            n += ch(d.toString(), KeyEvent.KEYCODE_0 + d, syms[i - 1].toString())
        }
        n += ch("-", KeyEvent.KEYCODE_MINUS, "_")
        n += ch("=", KeyEvent.KEYCODE_EQUALS, "+")
        n += key("⌫", KeyEvent.KEYCODE_DEL, 1.6f, true)

        val r2 = ArrayList<K>()
        r2 += key("Tab", KeyEvent.KEYCODE_TAB, 1.5f)
        val c2 = codesOf("qwertyuiop") + listOf(KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.KEYCODE_RIGHT_BRACKET)
        if (arabic) {
            r2 += keysOf("ضصثقفغعهخحجد".map { it.toString() }, c2, emptyList())
        } else {
            r2 += keysOf("qwertyuiop".map { it.toString() } + listOf("[", "]"), c2, nulls(10) + listOf("{", "}"))
        }
        r2 += ch("\\", KeyEvent.KEYCODE_BACKSLASH, "|", 1.3f)

        val r3 = ArrayList<K>()
        r3 += mod("Caps", Kind.CAPS, 1.7f)
        val c3 = codesOf("asdfghjkl") + listOf(KeyEvent.KEYCODE_SEMICOLON, KeyEvent.KEYCODE_APOSTROPHE)
        if (arabic) {
            r3 += keysOf("شسيبلاتنمكط".map { it.toString() }, c3, emptyList())
        } else {
            r3 += keysOf("asdfghjkl".map { it.toString() } + listOf(";", "'"), c3, nulls(9) + listOf(":", "\""))
        }
        r3 += key("Enter", KeyEvent.KEYCODE_ENTER, 1.9f)

        val r4 = ArrayList<K>()
        r4 += mod("Shift", Kind.SHIFT, 2f)
        val c4 = codesOf("zxcvbnm") + listOf(KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_SLASH)
        if (arabic) {
            r4 += keysOf(
                listOf("ئ", "ء", "ؤ", "ر", "لا", "ى", "ة", "و", "ز", "ظ", "،", "."),
                c4 + listOf(KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD),
                nulls(10) + listOf("؟", ":")
            )
        } else {
            r4 += keysOf(
                "zxcvbnm".map { it.toString() } + listOf(",", ".", "/"),
                c4,
                nulls(7) + listOf("<", ">", "?")
            )
        }
        r4 += mod("Shift", Kind.SHIFT, 2f)

        val r5 = listOf(
            mod("Ctrl", Kind.CTRL, 1.3f), mod("Win", Kind.WIN, 1.3f), mod("Alt", Kind.ALT, 1.3f),
            space(4f),
            mod("EN", Kind.LANG, 1.2f), mod("Fn", Kind.PAGE_FN, 1f),
            key("←", KeyEvent.KEYCODE_DPAD_LEFT, 1f, true),
            key("↓", KeyEvent.KEYCODE_DPAD_DOWN, 1f, true),
            key("↑", KeyEvent.KEYCODE_DPAD_UP, 1f, true),
            key("→", KeyEvent.KEYCODE_DPAD_RIGHT, 1f, true)
        )
        return listOf(Row(f, 0.75f), Row(n), Row(r2), Row(r3), Row(r4), Row(r5))
    }

    private fun fnRows(): List<Row> = listOf(
        Row(listOf(
            key("Esc", KeyEvent.KEYCODE_ESCAPE), key("Insert", KeyEvent.KEYCODE_INSERT),
            key("Del", KeyEvent.KEYCODE_FORWARD_DEL, 1f, true), key("Home", KeyEvent.KEYCODE_MOVE_HOME),
            key("End", KeyEvent.KEYCODE_MOVE_END), key("PgUp", KeyEvent.KEYCODE_PAGE_UP, 1f, true),
            key("PgDn", KeyEvent.KEYCODE_PAGE_DOWN, 1f, true)
        )),
        Row(listOf(
            key("PrtSc", KeyEvent.KEYCODE_SYSRQ), key("Pause", KeyEvent.KEYCODE_BREAK),
            key("ScrLk", KeyEvent.KEYCODE_SCROLL_LOCK), key("Tab", KeyEvent.KEYCODE_TAB),
            key("←", KeyEvent.KEYCODE_DPAD_LEFT, 1f, true), key("↓", KeyEvent.KEYCODE_DPAD_DOWN, 1f, true),
            key("↑", KeyEvent.KEYCODE_DPAD_UP, 1f, true), key("→", KeyEvent.KEYCODE_DPAD_RIGHT, 1f, true)
        )),
        Row(listOf(
            mod("Ctrl", Kind.CTRL), mod("Alt", Kind.ALT), mod("Shift", Kind.SHIFT), mod("Win", Kind.WIN),
            mod("Caps", Kind.CAPS), key("Enter", KeyEvent.KEYCODE_ENTER, 1.3f),
            key("⌫", KeyEvent.KEYCODE_DEL, 1f, true)
        )),
        Row(listOf(
            mod("ABC", Kind.PAGE_MAIN, 1.5f), mod("NumPad", Kind.PAGE_NUM, 1.5f),
            mod("EN", Kind.LANG, 1.2f), space(3f)
        ))
    )

    private fun numRows(): List<Row> = listOf(
        Row(listOf(
            mod("ABC", Kind.PAGE_MAIN), mod("Num", Kind.NUMLOCK),
            ch("/", KeyEvent.KEYCODE_NUMPAD_DIVIDE), ch("*", KeyEvent.KEYCODE_NUMPAD_MULTIPLY),
            ch("-", KeyEvent.KEYCODE_NUMPAD_SUBTRACT)
        )),
        Row(listOf(
            np("7", KeyEvent.KEYCODE_MOVE_HOME), np("8", KeyEvent.KEYCODE_DPAD_UP),
            np("9", KeyEvent.KEYCODE_PAGE_UP), ch("+", KeyEvent.KEYCODE_NUMPAD_ADD)
        )),
        Row(listOf(
            np("4", KeyEvent.KEYCODE_DPAD_LEFT), np("5", 0), np("6", KeyEvent.KEYCODE_DPAD_RIGHT),
            key("⌫", KeyEvent.KEYCODE_DEL, 1f, true)
        )),
        Row(listOf(
            np("1", KeyEvent.KEYCODE_MOVE_END), np("2", KeyEvent.KEYCODE_DPAD_DOWN),
            np("3", KeyEvent.KEYCODE_PAGE_DOWN), key("Enter", KeyEvent.KEYCODE_ENTER)
        )),
        Row(listOf(
            np("0", KeyEvent.KEYCODE_INSERT, 2f), np(".", KeyEvent.KEYCODE_FORWARD_DEL), space(1.5f)
        ))
    )

    private fun showPage() {
        container.removeAllViews()
        keyViews.clear()
        val rows = when (page) {
            Page.MAIN -> mainRows()
            Page.FN -> fnRows()
            Page.NUM -> numRows()
        }
        for (r in rows) {
            val rl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            container.addView(rl, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, r.h))
            for (k in r.keys) {
                val len = (k.display ?: k.label).length
                val sizeSp = when {
                    r.h < 1f -> 11f
                    len > 2 -> 12f
                    else -> 16f
                }
                val tv = TextView(this)
                tv.gravity = Gravity.CENTER
                tv.setTextColor(Color.WHITE)
                tv.includeFontPadding = false
                tv.maxLines = 1
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                val bg = GradientDrawable()
                bg.cornerRadius = dp(6).toFloat()
                bg.setColor(keyColor)
                tv.background = bg
                tv.setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            (v.background as GradientDrawable).setColor(pressedColor)
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onKey(k)
                            if (k.repeat) {
                                repeatKey = k
                                handler.postDelayed(repeater, 400)
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            stopRepeat()
                            (v.background as GradientDrawable).setColor(colorFor(k))
                        }
                    }
                    true
                }
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, k.weight)
                lp.setMargins(dp(2), dp(2), dp(2), dp(2))
                rl.addView(tv, lp)
                keyViews.add(Pair(tv, k))
            }
        }
        refresh()
    }

    private fun isActive(k: K): Boolean = when (k.kind) {
        Kind.SHIFT -> shift
        Kind.CAPS -> caps
        Kind.CTRL -> ctrl
        Kind.ALT -> alt
        Kind.WIN -> win
        Kind.NUMLOCK -> numLock
        else -> false
    }

    private fun colorFor(k: K): Int = when {
        isActive(k) -> activeColor
        k.kind == Kind.CHAR || k.kind == Kind.NP -> keyColor
        else -> specialColor
    }

    private fun textFor(k: K): String {
        val isLatinLetter = k.label.length == 1 && k.label[0] in 'a'..'z'
        val sh = k.shifted
        return when {
            isLatinLetter -> if (shift != caps) k.label.uppercase() else k.label
            shift && sh != null -> sh
            else -> k.label
        }
    }

    private fun refresh() {
        for ((tv, k) in keyViews) {
            val d = k.display
            val t: String = when {
                d != null -> d
                k.kind == Kind.CHAR -> textFor(k)
                k.kind == Kind.LANG -> if (arabic) "AR" else "EN"
                else -> k.label
            }
            if (tv.text.toString() != t) tv.text = t
            (tv.background as GradientDrawable).setColor(colorFor(k))
        }
    }

    private fun clearOneShot() {
        if (shift || ctrl || alt || win) {
            shift = false; ctrl = false; alt = false; win = false
            refresh()
        }
    }

    private fun stopRepeat() {
        handler.removeCallbacks(repeater)
        repeatKey = null
    }

    private fun metaState(): Int {
        var m = 0
        if (shift) m = m or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (ctrl) m = m or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (alt) m = m or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (win) m = m or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        return m
    }

    private fun sendKey(code: Int) {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        val m = metaState()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, m))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, m))
    }

    private fun typeChar(k: K) {
        val ic = currentInputConnection ?: return
        if ((ctrl || alt || win) && k.code != 0) sendKey(k.code)
        else ic.commitText(textFor(k), 1)
        clearOneShot()
    }

    private fun onKey(k: K) {
        when (k.kind) {
            Kind.CHAR -> typeChar(k)
            Kind.KEY -> { sendKey(k.code); clearOneShot() }
            Kind.NP -> {
                if (numLock) currentInputConnection?.commitText(k.label, 1)
                else if (k.code != 0) sendKey(k.code)
                clearOneShot()
            }
            Kind.SHIFT -> { shift = !shift; refresh() }
            Kind.CAPS -> { caps = !caps; refresh() }
            Kind.CTRL -> { ctrl = !ctrl; refresh() }
            Kind.ALT -> { alt = !alt; refresh() }
            Kind.WIN -> { win = !win; refresh() }
            Kind.NUMLOCK -> { numLock = !numLock; refresh() }
            Kind.LANG -> {
                arabic = !arabic
                getSharedPreferences("faris_kb", MODE_PRIVATE).edit().putBoolean("arabic", arabic).apply()
                shift = false
                showPage()
            }
            Kind.PAGE_MAIN -> { page = Page.MAIN; showPage() }
            Kind.PAGE_FN -> { page = if (page == Page.FN) Page.MAIN else Page.FN; showPage() }
            Kind.PAGE_NUM -> { page = Page.NUM; showPage() }
        }
    }
}
