package com.faris.pckeyboard

import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

class FarisKeyboardService : InputMethodService() {

    private enum class Kind {
        CHAR, KEY, NP, SHIFT, CAPS, CTRL, ALT, WIN, LANG, NUMLOCK,
        PAGE_MAIN, PAGE_FN, PAGE_NUM,
        EMOJI, CLIP, MIC, THEME, EMOJI_CAT, CLIP_CLEAR, SPACER
    }

    private enum class Page { MAIN, FN, NUM, EMOJI, CLIP, THEME }

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

    private class T(val bg: Int, val key: Int, val special: Int, val active: Int, val text: Int, val pressed: Int)

    private fun t(bg: String, key: String, sp: String, act: String, tx: String, pr: String) =
        T(Color.parseColor(bg), Color.parseColor(key), Color.parseColor(sp),
            Color.parseColor(act), Color.parseColor(tx), Color.parseColor(pr))

    private val themes = listOf(
        t("#0A1A33", "#1F5FD6", "#174AA8", "#E08A00", "#FFFFFF", "#6FA0FF"),
        t("#000000", "#2B2B2B", "#1C1C1C", "#E08A00", "#FFFFFF", "#5A5A5A"),
        t("#0A2A1A", "#1E9B57", "#167A44", "#E0B000", "#FFFFFF", "#5CCB8B"),
        t("#1B0E33", "#7B3FD6", "#5B2FA8", "#E08A00", "#FFFFFF", "#A57BEA"),
        t("#330A0A", "#D63A3A", "#A82A2A", "#FFC107", "#FFFFFF", "#F08080"),
        t("#0A2B33", "#1F9FB5", "#177F90", "#E08A00", "#FFFFFF", "#6FD3E3"),
        t("#330A22", "#D63A96", "#A82A76", "#FFC107", "#FFFFFF", "#F08CC4"),
        t("#2B2F36", "#5A6270", "#454B57", "#E08A00", "#FFFFFF", "#8B94A5"),
        t("#33200A", "#E08A00", "#B56E00", "#1F5FD6", "#FFFFFF", "#FFC266"),
        t("#DDE1E6", "#FFFFFF", "#C3C9D1", "#FFB74D", "#111111", "#B0BEC5")
    )
    private var themeIdx = 0
    private val th: T get() = themes[themeIdx]

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    private var page = Page.MAIN
    private var arabic = false
    private var shift = false
    private var caps = false
    private var ctrl = false
    private var alt = false
    private var win = false
    private var numLock = true
    private var emojiCat = 0

    private lateinit var root: FrameLayout
    private lateinit var container: LinearLayout
    private lateinit var overlay: LinearLayout
    private val keyViews = ArrayList<Pair<TextView, K>>()
    private val handler = Handler(Looper.getMainLooper())
    private var repeatKey: K? = null
    private val repeater = object : Runnable {
        override fun run() {
            repeatKey?.let { onKey(it); handler.postDelayed(this, 45) }
        }
    }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { readClip() }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        arabic = prefs().getBoolean("arabic", false)
        themeIdx = prefs().getInt("theme", 0).coerceIn(0, themes.size - 1)
        try {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).addPrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        try {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).removePrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    override fun onCreateInputView(): View = buildRoot()

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setInputView(buildRoot())
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val p = VoiceBridge.pending
        if (p != null && System.currentTimeMillis() - VoiceBridge.time < 20000) {
            VoiceBridge.pending = null
            handler.postDelayed({ currentInputConnection?.commitText(p + " ", 1) }, 250)
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        shift = false; ctrl = false; alt = false; win = false
        readClip()
        saveClips(loadClips())
        if (::container.isInitialized) refresh()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        stopRepeat()
    }

    // ------------------------------------------------------------------- layout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun prefs() = getSharedPreferences("faris_kb", MODE_PRIVATE)

    private fun buildRoot(): View {
        val h = minOf(dp(290), (resources.displayMetrics.heightPixels * 0.58f).toInt())
        root = FrameLayout(this)
        root.layoutDirection = View.LAYOUT_DIRECTION_LTR
        root.setBackgroundColor(th.bg)
        root.layoutParams = ViewGroup.LayoutParams(MP, h)
        container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(dp(3), dp(4), dp(3), dp(4))
        root.addView(container, FrameLayout.LayoutParams(MP, MP))
        overlay = LinearLayout(this)
        overlay.orientation = LinearLayout.HORIZONTAL
        overlay.visibility = View.GONE
        root.addView(overlay, FrameLayout.LayoutParams(WC, WC))
        showPage()
        return root
    }

    private fun ch(label: String, code: Int, shifted: String? = null, w: Float = 1f) =
        K(label, shifted, code, Kind.CHAR, w)

    private fun key(label: String, code: Int, w: Float = 1f, rep: Boolean = false) =
        K(label, null, code, Kind.KEY, w, null, rep)

    private fun mod(label: String, kind: Kind, w: Float = 1f) =
        K(label = label, kind = kind, weight = w)

    private fun np(label: String, code: Int, w: Float = 1f) =
        K(label, null, code, Kind.NP, w)

    private fun spacer(w: Float) = K(label = "", kind = Kind.SPACER, weight = w)

    private fun codesOf(s: String) = s.map { KeyEvent.KEYCODE_A + (it - 'a') }

    private fun keysOf(chars: List<String>, codes: List<Int>, shifts: List<String?>): List<K> =
        chars.indices.map { ch(chars[it], codes[it], shifts.getOrNull(it)) }

    private fun nulls(n: Int): List<String?> = List(n) { null }

    private fun space(w: Float) = K(" ", null, KeyEvent.KEYCODE_SPACE, Kind.CHAR, w, "Space")

    private fun mainRows(): List<Row> {
        // صف الأيقونات (فوق F1..F4)
        val icons = listOf(
            spacer(1f),
            mod("😀", Kind.EMOJI), mod("📋", Kind.CLIP), mod("🎤", Kind.MIC), mod("🎨", Kind.THEME),
            spacer(8f)
        )

        val f = ArrayList<K>()
        f += key("Esc", KeyEvent.KEYCODE_ESCAPE)
        for (i in 1..12) f += key("F$i", KeyEvent.KEYCODE_F1 + i - 1)

        val syms = "!@#\$%^&*()"
        val n = ArrayList<K>()
        n += ch("`", KeyEvent.KEYCODE_GRAVE, "~")
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
            r2 += keysOf("ضصثقفغعهخحج".map { it.toString() }, c2, emptyList())
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
                listOf("ذ", "ء", "ؤ", "ر", "ى", "ة", "و", "ز", "ظ", "د", "،", "."),
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
        r4 += mod("123", Kind.PAGE_NUM, 2f)

        val r5 = listOf(
            mod("Ctrl", Kind.CTRL, 1.3f), mod("Win", Kind.WIN, 1.3f), mod("Alt", Kind.ALT, 1.3f),
            space(4f),
            mod("EN", Kind.LANG, 1.2f), mod("Fn", Kind.PAGE_FN, 1f),
            key("←", KeyEvent.KEYCODE_DPAD_LEFT, 1f, true),
            key("↓", KeyEvent.KEYCODE_DPAD_DOWN, 1f, true),
            key("↑", KeyEvent.KEYCODE_DPAD_UP, 1f, true),
            key("→", KeyEvent.KEYCODE_DPAD_RIGHT, 1f, true)
        )
        return listOf(Row(icons, 0.7f), Row(f, 0.75f), Row(n), Row(r2), Row(r3), Row(r4), Row(r5))
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
            mod("ABC", Kind.PAGE_MAIN, 1.5f), mod("123", Kind.PAGE_NUM, 1.5f),
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

    private fun bottomBar(extra: List<K>): Row =
        Row(listOf(mod("ABC", Kind.PAGE_MAIN, 1.6f)) + extra + listOf(key("⌫", KeyEvent.KEYCODE_DEL, 1.6f, true)))

    private fun showPage() {
        if (::overlay.isInitialized) hideOverlay()
        container.removeAllViews()
        keyViews.clear()
        when (page) {
            Page.MAIN -> buildRows(mainRows())
            Page.FN -> buildRows(fnRows())
            Page.NUM -> buildRows(numRows())
            Page.EMOJI -> showEmoji()
            Page.CLIP -> showClip()
            Page.THEME -> showThemes()
        }
        refresh()
    }

    private fun buildRows(rows: List<Row>) {
        for (r in rows) {
            val rl = LinearLayout(this)
            rl.orientation = LinearLayout.HORIZONTAL
            container.addView(rl, LinearLayout.LayoutParams(MP, 0, r.h))
            for (k in r.keys) {
                if (k.kind == Kind.SPACER) {
                    rl.addView(View(this), LinearLayout.LayoutParams(0, MP, k.weight))
                    continue
                }
                val len = (k.display ?: k.label).length
                val isIcon = k.kind == Kind.EMOJI || k.kind == Kind.CLIP || k.kind == Kind.MIC || k.kind == Kind.THEME
                val sizeSp = when {
                    isIcon -> 14f
                    k.kind == Kind.EMOJI_CAT -> 15f
                    r.h < 1f -> 11f
                    len > 2 -> 12f
                    else -> 16f
                }
                val tv = TextView(this)
                tv.gravity = Gravity.CENTER
                tv.setTextColor(th.text)
                tv.includeFontPadding = false
                tv.maxLines = 1
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                val bg = GradientDrawable()
                bg.cornerRadius = dp(6).toFloat()
                bg.setColor(th.key)
                tv.background = bg

                var pending = false
                var shown = false
                var variants: List<String> = emptyList()
                var sel = 0
                val longPress = Runnable {
                    if (pending) {
                        val vs = variantsFor(k)
                        if (vs.isNotEmpty()) {
                            variants = vs
                            shown = true
                            sel = 0
                            tv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            showVariants(tv, vs)
                        }
                    }
                }

                tv.setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            (v.background as GradientDrawable).setColor(th.pressed)
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            if (k.kind == Kind.CHAR && !ctrl && !alt && !win && variantsFor(k).isNotEmpty()) {
                                pending = true
                                shown = false
                                handler.postDelayed(longPress, 350)
                            } else {
                                onKey(k)
                                if (k.repeat) {
                                    repeatKey = k
                                    handler.postDelayed(repeater, 400)
                                }
                            }
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (shown) {
                                sel = indexAt(e.rawX, variants.size)
                                highlight(sel)
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            stopRepeat()
                            handler.removeCallbacks(longPress)
                            if (shown) {
                                commitVariant(variants[sel])
                                hideOverlay()
                            } else if (pending) {
                                onKey(k)
                            }
                            pending = false
                            shown = false
                            (v.background as GradientDrawable).setColor(colorFor(k))
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            stopRepeat()
                            handler.removeCallbacks(longPress)
                            if (shown) hideOverlay()
                            pending = false
                            shown = false
                            (v.background as GradientDrawable).setColor(colorFor(k))
                        }
                    }
                    true
                }
                val lpar = LinearLayout.LayoutParams(0, MP, k.weight)
                lpar.setMargins(dp(2), dp(2), dp(2), dp(2))
                rl.addView(tv, lpar)
                keyViews.add(Pair(tv, k))
            }
        }
    }

    // ------------------------------------------------------- ضغطة مطولة (بدل Shift)

    private fun variantsFor(k: K): List<String> {
        if (k.kind != Kind.CHAR || k.display != null) return emptyList()
        if (arabic) {
            if (k.label == "ى") return listOf("ئ")
            if (k.label == "ا") return listOf("أ", "إ", "آ")
        }
        val isLatin = k.label.length == 1 && k.label[0] in 'a'..'z'
        if (isLatin) {
            val upperNow = shift != caps
            return listOf(if (upperNow) k.label else k.label.uppercase())
        }
        val sh = k.shifted
        if (sh != null) return listOf(if (shift) k.label else sh)
        return emptyList()
    }

    private fun showVariants(anchor: View, items: List<String>) {
        overlay.removeAllViews()
        val itemW = maxOf(anchor.width, dp(40))
        val itemH = anchor.height
        for (s in items) {
            val tv = TextView(this)
            tv.text = s
            tv.gravity = Gravity.CENTER
            tv.setTextColor(th.text)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            overlay.addView(tv, LinearLayout.LayoutParams(itemW, itemH))
        }
        val bg = GradientDrawable()
        bg.cornerRadius = dp(8).toFloat()
        bg.setColor(th.special)
        bg.setStroke(dp(1), th.pressed)
        overlay.background = bg
        overlay.visibility = View.VISIBLE
        val a = IntArray(2)
        val b = IntArray(2)
        anchor.getLocationInWindow(a)
        root.getLocationInWindow(b)
        val totalW = itemW * items.size
        var x = a[0] - b[0]
        x = x.coerceIn(0, maxOf(0, root.width - totalW))
        var y = a[1] - b[1] - itemH - dp(2)
        if (y < 0) y = 0
        overlay.x = x.toFloat()
        overlay.y = y.toFloat()
        highlight(0)
    }

    private fun highlight(sel: Int) {
        for (i in 0 until overlay.childCount) {
            val c = overlay.getChildAt(i) as TextView
            if (i == sel) {
                val g = GradientDrawable()
                g.cornerRadius = dp(6).toFloat()
                g.setColor(th.active)
                c.background = g
            } else {
                c.background = null
            }
        }
    }

    private fun indexAt(rawX: Float, n: Int): Int {
        val loc = IntArray(2)
        overlay.getLocationOnScreen(loc)
        val w = overlay.width.toFloat()
        if (w <= 0f || n <= 0) return 0
        val i = ((rawX - loc[0]) / (w / n)).toInt()
        return i.coerceIn(0, n - 1)
    }

    private fun hideOverlay() {
        overlay.visibility = View.GONE
    }

    private fun commitVariant(s: String) {
        currentInputConnection?.commitText(s, 1)
        clearOneShot()
    }

    // -------------------------------------------------------------- الإيموجي

    private fun cps(vararg ranges: IntRange): List<String> =
        ranges.flatMap { r -> r.map { String(Character.toChars(it)) } }

    private val emojiCats: List<Pair<String, List<String>>> by lazy {
        listOf(
            "😀" to cps(0x1F600..0x1F64F),
            "❤️" to listOf(
                "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔", "❣️", "💕", "💞",
                "💓", "💗", "💖", "💘", "💝", "💟", "💌", "😍", "😘", "💋", "🌹", "💐"
            ),
            "👍" to cps(0x1F440..0x1F450, 0x1F466..0x1F478, 0x1F485..0x1F487, 0x1F4AA..0x1F4AA),
            "🐶" to cps(0x1F400..0x1F43E, 0x1F980..0x1F98F),
            "🍔" to cps(0x1F32D..0x1F32F, 0x1F345..0x1F37F),
            "⚽" to (listOf("⚽", "⚾") + cps(0x1F380..0x1F393, 0x1F3A0..0x1F3CA)),
            "🌍" to cps(0x1F30D..0x1F320, 0x1F3E0..0x1F3F0, 0x1F680..0x1F6A4),
            "💡" to cps(0x1F4A1..0x1F4FC, 0x1F50A..0x1F52E),
            "🔣" to listOf(
                "✅", "❌", "⭕", "❗", "❓", "‼️", "⚠️", "🚫", "⭐", "🌟", "✨", "💫", "🔥", "💯",
                "⚡", "☀️", "🌙", "🌈", "☁️", "❄️", "💧", "🎉", "🎁", "🏆", "👑", "💎", "🔔",
                "💬", "💭", "✔️", "➕", "➖", "➗", "✖️", "♻️", "☮️", "✝️", "☪️", "🔴", "🔵",
                "⚫", "⚪", "⬆️", "⬇️", "⬅️", "➡️"
            )
        )
    }

    private fun showEmoji() {
        val cat = emojiCats[emojiCat].second
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val perRow = 8
        var i = 0
        while (i < cat.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val end = minOf(i + perRow, cat.size)
            for (j in i until end) {
                val tv = TextView(this)
                tv.text = cat[j]
                tv.gravity = Gravity.CENTER
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                val em = cat[j]
                tv.setOnClickListener { currentInputConnection?.commitText(em, 1) }
                row.addView(tv, LinearLayout.LayoutParams(0, dp(44), 1f))
            }
            for (j in (end - i) until perRow) {
                row.addView(View(this), LinearLayout.LayoutParams(0, dp(44), 1f))
            }
            col.addView(row, LinearLayout.LayoutParams(MP, WC))
            i += perRow
        }
        sv.addView(col)
        container.addView(sv, LinearLayout.LayoutParams(MP, 0, 5f))
        val tabs = emojiCats.mapIndexed { idx, c -> K(label = c.first, code = idx, kind = Kind.EMOJI_CAT) }
        buildRows(listOf(bottomBar(tabs)))
    }

    // ------------------------------------------------------------ حافظة النص

    private fun loadClips(): MutableList<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        try {
            val arr = JSONArray(prefs().getString("clips", "[]"))
            val limit = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val tm = o.getLong("t")
                if (tm >= limit) out.add(Pair(tm, o.getString("s")))
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun saveClips(list: List<Pair<Long, String>>) {
        val arr = JSONArray()
        for (p in list) arr.put(JSONObject().put("t", p.first).put("s", p.second))
        prefs().edit().putString("clips", arr.toString()).apply()
    }

    private fun addClip(text: String) {
        if (text.trim().isEmpty()) return
        val list = loadClips()
        list.removeAll { it.second == text }
        list.add(0, Pair(System.currentTimeMillis(), text))
        while (list.size > 100) list.removeAt(list.size - 1)
        saveClips(list)
    }

    private fun readClip() {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            if (Build.VERSION.SDK_INT >= 33) {
                val ex = clip.description.extras
                if (ex != null && ex.getBoolean("android.content.extra.IS_SENSITIVE", false)) return
            }
            val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
            addClip(text)
        } catch (e: Exception) {
        }
    }

    private fun showClip() {
        val items = loadClips()
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        if (items.isEmpty()) {
            val empty = TextView(this)
            empty.text = "لا يوجد نص منسوخ"
            empty.setTextColor(th.text)
            empty.gravity = Gravity.CENTER
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            col.addView(empty, LinearLayout.LayoutParams(MP, dp(80)))
        }
        for (item in items) {
            val text = item.second
            val tm = item.first
            val tv = TextView(this)
            tv.text = text
            tv.maxLines = 3
            tv.ellipsize = TextUtils.TruncateAt.END
            tv.setTextColor(th.text)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            tv.setPadding(dp(10), dp(8), dp(10), dp(8))
            tv.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            val bg = GradientDrawable()
            bg.cornerRadius = dp(8).toFloat()
            bg.setColor(th.key)
            tv.background = bg
            tv.setOnClickListener { currentInputConnection?.commitText(text, 1) }
            tv.setOnLongClickListener {
                val list = loadClips()
                list.removeAll { p -> p.first == tm && p.second == text }
                saveClips(list)
                showPage()
                true
            }
            val lpar = LinearLayout.LayoutParams(MP, WC)
            lpar.setMargins(dp(3), dp(3), dp(3), dp(3))
            col.addView(tv, lpar)
        }
        sv.addView(col)
        container.addView(sv, LinearLayout.LayoutParams(MP, 0, 5f))
        buildRows(listOf(bottomBar(listOf(K(label = "🗑 مسح الكل", kind = Kind.CLIP_CLEAR, weight = 4f)))))
    }

    // ----------------------------------------------------------- تغيير اللون

    private fun showThemes() {
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        for (r in 0..1) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (c in 0..4) {
                val idx = r * 5 + c
                val tt = themes[idx]
                val sw = TextView(this)
                sw.gravity = Gravity.CENTER
                sw.text = if (idx == themeIdx) "✓" else ""
                sw.setTextColor(tt.text)
                sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                val g = GradientDrawable()
                g.cornerRadius = dp(10).toFloat()
                g.setColor(tt.key)
                g.setStroke(dp(3), tt.special)
                sw.background = g
                sw.setOnClickListener {
                    themeIdx = idx
                    prefs().edit().putInt("theme", idx).apply()
                    root.setBackgroundColor(th.bg)
                    showPage()
                }
                val lpar = LinearLayout.LayoutParams(0, MP, 1f)
                lpar.setMargins(dp(4), dp(4), dp(4), dp(4))
                row.addView(sw, lpar)
            }
            grid.addView(row, LinearLayout.LayoutParams(MP, 0, 1f))
        }
        container.addView(grid, LinearLayout.LayoutParams(MP, 0, 5f))
        buildRows(listOf(bottomBar(emptyList())))
    }

    // -------------------------------------------------------------------- state

    private fun isActive(k: K): Boolean = when (k.kind) {
        Kind.SHIFT -> shift
        Kind.CAPS -> caps
        Kind.CTRL -> ctrl
        Kind.ALT -> alt
        Kind.WIN -> win
        Kind.NUMLOCK -> numLock
        Kind.EMOJI_CAT -> k.code == emojiCat
        else -> false
    }

    private fun colorFor(k: K): Int = when {
        isActive(k) -> th.active
        k.kind == Kind.CHAR || k.kind == Kind.NP -> th.key
        else -> th.special
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
            val s: String = when {
                d != null -> d
                k.kind == Kind.CHAR -> textFor(k)
                k.kind == Kind.LANG -> if (arabic) "AR" else "EN"
                else -> k.label
            }
            if (tv.text.toString() != s) tv.text = s
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

    // ------------------------------------------------------------------- output

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

    private fun startVoice() {
        val i = Intent(this, VoiceActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        i.putExtra("arabic", arabic)
        startActivity(i)
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
                prefs().edit().putBoolean("arabic", arabic).apply()
                shift = false
                showPage()
            }
            Kind.PAGE_MAIN -> { page = Page.MAIN; showPage() }
            Kind.PAGE_FN -> { page = if (page == Page.FN) Page.MAIN else Page.FN; showPage() }
            Kind.PAGE_NUM -> { page = Page.NUM; showPage() }
            Kind.EMOJI -> { page = Page.EMOJI; showPage() }
            Kind.CLIP -> { page = Page.CLIP; showPage() }
            Kind.THEME -> { page = Page.THEME; showPage() }
            Kind.EMOJI_CAT -> { emojiCat = k.code; showPage() }
            Kind.CLIP_CLEAR -> { saveClips(emptyList()); showPage() }
            Kind.MIC -> startVoice()
            Kind.SPACER -> {}
        }
    }
}
