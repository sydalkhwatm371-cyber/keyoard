package com.example.mykeyboard

import android.content.ClipDescription
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.min

class MyKeyboardService : InputMethodService() {

    companion object {
        const val AR = 0
        const val EN = 1
        const val SYM = 2

        const val P_KEYS = 0
        const val P_GIF = 1
        const val P_TR = 2
        const val P_FONT = 3

        val BG_COLOR = 0xFF263238.toInt()
        val PANEL_BG = 0xFF1C272C.toInt()
        val KEY_BG = 0xFF455A64.toInt()
        val SPECIAL_BG = 0xFF37474F.toInt()
        val ACCENT_BG = 0xFF00897B.toInt()
        val SELECT_BG = 0xFFF57C00.toInt()
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(4)
    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout

    // الحالة
    private var mode = AR
    private var lastLetterMode = AR
    private var shift = false
    private var panel = P_KEYS
    private var showSettings = false
    private var editMode = false
    private var selId: String? = null
    private var activeStyle: Style? = null

    // الإعدادات المحفوظة
    private var heightScale = 1f
    private var gapDp = 5
    private val layouts = HashMap<Int, MutableList<MutableList<String>>>()
    private val params = HashMap<String, FloatArray>()

    // GIF
    private var gifGen = 0
    private var gifStrip: LinearLayout? = null
    private var gifStatus: TextView? = null

    // الترجمة
    private var trSource = ""
    private var trWasSel = false
    private var trResult = ""
    private var trGen = 0
    private var trOut: TextView? = null

    private val iraqiMap = mapOf(
        "ج" to "چ", "ك" to "گ", "ب" to "پ", "ف" to "ڤ", "ز" to "ژ"
    )
    private val arRows = listOf("ضصثقفغعهخحجد", "شسيبلاتنمكطذ", "ئءؤرىةوزظ")
    private val enRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val symRows = listOf("1234567890", "@#$%&-+()/", "!?:;'\"*=_~")

    // ================= دورة الحياة =================

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("kb", MODE_PRIVATE)
        loadPrefs()
        cleanCache()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_COLOR)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(3), dp(4), dp(3), dp(6))
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        renderAll()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        shift = false
        if (::root.isInitialized) renderAll()
    }

    // ================= الحفظ والتحميل =================

    private fun loadPrefs() {
        heightScale = prefs.getFloat("hs", 1f).coerceIn(0.5f, 1.6f)
        gapDp = prefs.getInt("gap", 5).coerceIn(0, 16)
        try {
            prefs.getString("layouts", null)?.let { s ->
                val o = JSONObject(s)
                o.keys().forEach { k ->
                    val ra = o.getJSONArray(k)
                    val rows = mutableListOf<MutableList<String>>()
                    for (i in 0 until ra.length()) {
                        val r = ra.getJSONArray(i)
                        val l = mutableListOf<String>()
                        for (j in 0 until r.length()) l.add(r.getString(j))
                        rows.add(l)
                    }
                    layouts[k.toInt()] = rows
                }
            }
            prefs.getString("params", null)?.let { s ->
                val o = JSONObject(s)
                o.keys().forEach { k ->
                    val a = o.getJSONArray(k)
                    params[k] = floatArrayOf(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())
                }
            }
        } catch (e: Exception) {
            layouts.clear()
            params.clear()
        }
    }

    private fun savePrefs() {
        val lj = JSONObject()
        for ((m, rows) in layouts) {
            val ra = JSONArray()
            rows.forEach { r -> ra.put(JSONArray(r)) }
            lj.put(m.toString(), ra)
        }
        val pj = JSONObject()
        for ((k, v) in params) {
            pj.put(k, JSONArray().put(v[0].toDouble()).put(v[1].toDouble()))
        }
        prefs.edit()
            .putString("layouts", lj.toString())
            .putString("params", pj.toString())
            .putFloat("hs", heightScale)
            .putInt("gap", gapDp)
            .apply()
    }

    private fun resetAll() {
        layouts.clear()
        params.clear()
        heightScale = 1f
        gapDp = 5
        selId = null
        savePrefs()
    }

    // ================= تخطيط الأزرار =================

    private fun defaultLayout(m: Int): MutableList<MutableList<String>> {
        fun chars(s: String) = s.map { it.toString() }.toMutableList()
        val rows = mutableListOf<MutableList<String>>()
        if (m == SYM) {
            rows.add(chars(symRows[0]))
            rows.add(chars(symRows[1]))
            rows.add((chars(symRows[2]) + "::DEL").toMutableList())
        } else {
            val src = if (m == AR) arRows else enRows
            // نخزّن الترتيب كما يظهر على الشاشة (من اليسار لليمين)
            fun v(s: String) = if (m == AR) chars(s).reversed().toMutableList() else chars(s)
            rows.add(chars("1234567890"))
            rows.add(v(src[0]))
            rows.add(v(src[1]))
            rows.add((listOf("::SHIFT") + v(src[2]) + "::DEL").toMutableList())
        }
        rows.add(mutableListOf("::SYM", "::LANG", "::COMMA", "::SPACE", "::DOT", "::ENTER"))
        return rows
    }

    private fun layoutFor(m: Int) = layouts.getOrPut(m) { defaultLayout(m) }

    private fun param(id: String) = params.getOrPut("$mode|$id") { floatArrayOf(1f, 1f) }

    private fun find(id: String): Pair<Int, Int>? {
        val rows = layoutFor(mode)
        for (r in rows.indices) {
            val i = rows[r].indexOf(id)
            if (i >= 0) return Pair(r, i)
        }
        return null
    }

    private fun moveH(d: Int) {
        val id = selId ?: return
        val (r, i) = find(id) ?: return
        val row = layoutFor(mode)[r]
        val j = i + d
        if (j !in row.indices) return
        Collections.swap(row, i, j)
        savePrefs(); renderContent()
    }

    private fun moveV(d: Int) {
        val id = selId ?: return
        val (r, i) = find(id) ?: return
        val rows = layoutFor(mode)
        rows[r].removeAt(i)
        val t = r + d
        when {
            t < 0 -> rows.add(0, mutableListOf(id))
            t >= rows.size -> rows.add(mutableListOf(id))
            else -> rows[t].add(min(i, rows[t].size), id)
        }
        rows.removeAll { it.isEmpty() }
        savePrefs(); renderContent()
    }

    private fun resize(dw: Float, dh: Float) {
        val id = selId ?: return
        val p = param(id)
        p[0] = (p[0] + dw).coerceIn(0.4f, 3f)
        p[1] = (p[1] + dh).coerceIn(0.4f, 3f)
        savePrefs(); renderContent()
    }

    // ================= الرسم العام =================

    private fun renderAll() {
        root.removeAllViews()
        root.addView(toolbar())
        if (showSettings && panel == P_KEYS) root.addView(settingsPanel())
        root.addView(content)
        renderContent()
    }

    private fun renderContent() {
        content.removeAllViews()
        when (panel) {
            P_KEYS -> renderKeys()
            P_GIF -> content.addView(gifPanel())
            P_TR -> content.addView(translatePanel())
            P_FONT -> content.addView(fontsPanel())
        }
    }

    private fun togglePanel(p: Int) {
        panel = if (panel == p) P_KEYS else p
        showSettings = false
        editMode = false
        selId = null
        renderAll()
    }

    private fun toolbar(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        row.addView(pill("GIF", 1f, if (panel == P_GIF) ACCENT_BG else SPECIAL_BG, 34, 13f) { togglePanel(P_GIF) })
        row.addView(pill("文A ترجمة", 1.4f, if (panel == P_TR) ACCENT_BG else SPECIAL_BG, 34, 13f) { togglePanel(P_TR) })
        row.addView(
            pill("𝓐 زخرفة", 1.4f,
                if (panel == P_FONT || activeStyle != null) ACCENT_BG else SPECIAL_BG, 34, 13f) { togglePanel(P_FONT) }
        )
        row.addView(pill("⚙ ضبط", 1.2f, if (showSettings) ACCENT_BG else SPECIAL_BG, 34, 13f) {
            panel = P_KEYS
            showSettings = !showSettings
            if (!showSettings) { editMode = false; selId = null }
            renderAll()
        })
        return row
    }

    // ================= لوحة الضبط والتعديل =================

    private fun settingsPanel(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PANEL_BG)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        box.addView(sliderRow("↕ الارتفاع", 50, 160, (heightScale * 100).toInt(), { "${it}%" }) {
            heightScale = it / 100f
            renderContent()
        })
        box.addView(sliderRow("↔ الفراغ", 0, 16, gapDp, { "$it" }) {
            gapDp = it
            renderContent()
        })

        val btns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        btns.addView(pill("✏️ تعديل الأزرار", 1.6f, if (editMode) SELECT_BG else SPECIAL_BG, 38, 13f) {
            editMode = !editMode
            if (!editMode) selId = null
            renderAll()
        })
        btns.addView(pill("↺ افتراضي", 1.2f, SPECIAL_BG, 38, 13f) {
            resetAll(); renderAll()
        })
        btns.addView(pill("✔ إغلاق", 1f, ACCENT_BG, 38, 13f) {
            showSettings = false; editMode = false; selId = null
            savePrefs(); renderAll()
        })
        box.addView(btns)
        if (editMode) box.addView(editBar())
        return box
    }

    private fun sliderRow(
        name: String, lo: Int, hi: Int, value: Int,
        fmt: (Int) -> String, onChange: (Int) -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val nameTv = label(name, 13f, Color.WHITE).apply {
            layoutParams = LinearLayout.LayoutParams(dp(78), WRAP)
        }
        val valueTv = label(fmt(value), 13f, Color.WHITE).apply {
            layoutParams = LinearLayout.LayoutParams(dp(46), WRAP)
            gravity = Gravity.CENTER
        }
        val sb = SeekBar(this).apply {
            max = hi - lo
            progress = value - lo
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val v = p + lo
                        valueTv.text = fmt(v)
                        onChange(v)
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { savePrefs() }
            })
        }
        row.addView(nameTv); row.addView(sb); row.addView(valueTv)
        return row
    }

    private fun editBar(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sel = selId
        col.addView(
            label(
                if (sel == null) "✏️ اضغط على أي زر في الكيبورد لتحديده"
                else "المحدد: ${keyName(sel)}",
                13f, 0xFFFFB74D.toInt()
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, dp(3))
            }
        )
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        fun b(l: String, f: () -> Unit) {
            r.addView(pill(l, 1f, SPECIAL_BG, 38, 15f) { if (selId != null) f() })
        }
        b("◀") { moveH(-1) }
        b("▶") { moveH(1) }
        b("▲") { moveV(-1) }
        b("▼") { moveV(1) }
        b("↔+") { resize(0.15f, 0f) }
        b("↔−") { resize(-0.15f, 0f) }
        b("↕+") { resize(0f, 0.15f) }
        b("↕−") { resize(0f, -0.15f) }
        col.addView(r)
        return col
    }

    private fun keyName(id: String): String = when (id) {
        "::SHIFT" -> "Shift"
        "::DEL" -> "حذف"
        "::SYM" -> "أرقام/رموز"
        "::LANG" -> "🌐 اللغة"
        "::SPACE" -> "مسافة"
        "::ENTER" -> "إدخال"
        "::COMMA" -> "فاصلة"
        "::DOT" -> "نقطة"
        else -> id
    }

    // ================= الأزرار =================

    private fun renderKeys() {
        for (r in layoutFor(mode)) {
            if (r.isEmpty()) continue
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
            r.forEach { row.addView(keyFor(it)) }
            content.addView(row)
        }
    }

    private fun keyFor(id: String): View {
        val p = param(id)
        val w = p[0]
        val h = p[1]
        return when (id) {
            "::SHIFT" -> key(id, "⇧", 1.5f * w, h, if (shift) ACCENT_BG else SPECIAL_BG, 20f) {
                shift = !shift; renderContent()
            }
            "::DEL" -> key(id, "⌫", 1.5f * w, h, SPECIAL_BG, 20f, true) { deleteOne() }
            "::SYM" -> key(id, symLabel(), 1.4f * w, h, SPECIAL_BG, 16f) { toggleSym() }
            "::LANG" -> key(id, "🌐", 1.2f * w, h, SPECIAL_BG, 18f) {
                val base = if (mode == SYM) lastLetterMode else mode
                mode = if (base == AR) EN else AR
                lastLetterMode = mode
                shift = false
                selId = null
                renderContent()
            }
            "::COMMA" -> {
                val c = if (mode == AR) "،" else ","
                key(id, c, w, h, KEY_BG, 20f) { type(c) }
            }
            "::DOT" -> key(id, ".", w, h, KEY_BG, 20f) { type(".") }
            "::SPACE" -> key(id, if (mode == AR) "مسافة" else "space", 4f * w, h, KEY_BG, 14f) { type(" ") }
            "::ENTER" -> key(id, "⏎", 1.4f * w, h, ACCENT_BG, 20f) { pressEnter() }
            else -> {
                val s = display(id)
                key(id, s, w, h, KEY_BG, 20f) { type(s) }
            }
        }
    }

    private fun display(ch: String): String = when {
        mode == AR && shift -> iraqiMap[ch] ?: ch
        mode == EN && shift -> ch.uppercase()
        else -> ch
    }

    private fun symLabel() =
        if (mode == SYM) (if (lastLetterMode == AR) "أبج" else "ABC") else "?123"

    private fun toggleSym() {
        if (mode == SYM) {
            mode = lastLetterMode
        } else {
            lastLetterMode = mode
            mode = SYM
        }
        shift = false
        selId = null
        renderContent()
    }

    private fun key(
        id: String, text: String, weight: Float, hMul: Float, bg: Int,
        size: Float, repeat: Boolean = false, act: () -> Unit
    ): TextView {
        val v = TextView(this)
        v.text = text
        v.gravity = Gravity.CENTER
        v.setTextColor(Color.WHITE)
        v.textSize = size * (0.75f + 0.25f * heightScale)
        val isSel = editMode && id == selId
        v.background = rounded(if (isSel) SELECT_BG else bg)
        v.isClickable = true
        val g = dp(gapDp) / 2
        val hPx = (dp(48) * heightScale * hMul).toInt().coerceAtLeast(dp(18))
        v.layoutParams = LinearLayout.LayoutParams(0, hPx, weight).apply { setMargins(g, g, g, g) }

        val select = { selId = id; renderAll() }

        if (!repeat) {
            v.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (editMode) select() else act()
            }
        } else {
            val repeater = object : Runnable {
                override fun run() {
                    act()
                    ui.postDelayed(this, 50)
                }
            }
            v.setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        if (editMode) {
                            select()
                        } else {
                            act()
                            ui.postDelayed(repeater, 400)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(repeater)
                }
                true
            }
        }
        return v
    }

    // ================= أدوات واجهة =================

    private fun pill(text: String, weight: Float, bg: Int, hDp: Int, size: Float, click: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = size
            background = rounded(bg)
            layoutParams = LinearLayout.LayoutParams(0, dp(hDp), weight).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                click()
            }
        }
    }

    private fun chip(text: String, active: Boolean, click: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(if (active) ACCENT_BG else SPECIAL_BG)
            layoutParams = LinearLayout.LayoutParams(WRAP, dp(38)).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            setOnClickListener { click() }
        }
    }

    private fun label(text: String, size: Float = 13f, color: Int = Color.LTGRAY): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
        }

    private fun hScroll(vararg views: View): HorizontalScrollView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        views.forEach { row.addView(it) }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(8).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun panelHeight(): Int =
        (dp(48) * heightScale * 5 + dp(gapDp) * 5).toInt().coerceIn(dp(210), dp(380))

    private fun panelBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, panelHeight())
        setBackgroundColor(PANEL_BG)
        setPadding(dp(6), dp(4), dp(6), dp(4))
    }

    // ================= الإدخال =================

    private fun type(s: String) {
        val ic = currentInputConnection ?: return
        val st = activeStyle
        if (st != null && st.local) {
            if (s.isNotEmpty() && s[0].isWhitespace() && st.joiner.isNotEmpty()) {
                val prev = ic.getTextBeforeCursor(st.joiner.length, 0)
                if (prev != null && prev.toString() == st.joiner) {
                    ic.deleteSurroundingText(st.joiner.length, 0)
                }
            }
            ic.commitText(st.applyChar(s), 1)
        } else {
            ic.commitText(s, 1)
        }
        if (shift) {
            shift = false
            renderContent()
        }
    }

    private fun deleteOne() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1)
        else ic.deleteSurroundingText(1, 0)
    }

    private fun pressEnter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val opts = info?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnterAction && action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    /** النص المحدد، وإن لم يوجد فكل نص الحقل. */
    private fun fieldText(): Pair<String, Boolean> {
        val ic = currentInputConnection ?: return Pair("", false)
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) return Pair(sel.toString(), true)
        val b = ic.getTextBeforeCursor(5000, 0) ?: ""
        val a = ic.getTextAfterCursor(5000, 0) ?: ""
        return Pair(b.toString() + a.toString(), false)
    }

    private fun replaceFieldText(newText: String, wasSelection: Boolean) {
        val ic = currentInputConnection ?: return
        if (wasSelection) {
            ic.commitText(newText, 1)
        } else {
            val b = ic.getTextBeforeCursor(5000, 0)?.length ?: 0
            val a = ic.getTextAfterCursor(5000, 0)?.length ?: 0
            ic.deleteSurroundingText(b, a)
            ic.commitText(newText, 1)
        }
    }

    // ================= الزخرفة =================

    private fun fontsPanel(): View {
        val box = panelBox()

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        top.addView(pill("🔄 زخرف النص الحالي", 1.6f, ACCENT_BG, 38, 13f) {
            val st = activeStyle
            if (st == null) {
                toast("اختر زخرفة أولاً")
            } else {
                val (t, sel) = fieldText()
                if (t.isNotEmpty()) replaceFieldText(st.apply(t), sel)
            }
        })
        top.addView(pill("✖ إيقاف", 1f, SPECIAL_BG, 38, 13f) {
            activeStyle = null
            renderAll()
        })
        box.addView(top)

        val st = activeStyle
        box.addView(
            label(
                if (st != null) "مفعّلة: ${st.name} ← كل ما تكتبه سيُزخرف"
                else "اختر زخرفة ثم اكتب، أو حوّل نصاً موجوداً. (الأطر تُطبَّق على النص مباشرة)",
                12f, Color.LTGRAY
            ).apply { setPadding(dp(4), dp(2), dp(4), dp(2)) }
        )

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        Styles.all.chunked(3).forEach { group ->
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
            group.forEach { s ->
                val active = activeStyle === s
                r.addView(pill(s.sample, 1f, if (active) ACCENT_BG else KEY_BG, 44, 15f) {
                    if (s.local) {
                        activeStyle = if (activeStyle === s) null else s
                        renderAll()
                    } else {
                        val (t, sel) = fieldText()
                        if (t.isNotEmpty()) replaceFieldText(s.apply(t), sel) else toast("اكتب نصاً أولاً")
                    }
                })
            }
            repeat(3 - group.size) {
                r.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f) })
            }
            grid.addView(r)
        }
        box.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            addView(grid)
        })
        return box
    }

    // ================= الترجمة =================

    private fun translatePanel(): View {
        val box = panelBox()
        val (txt, sel) = fieldText()
        trSource = txt
        trWasSel = sel
        trResult = ""

        box.addView(label(if (sel) "النص المحدد:" else "نص الحقل:", 12f))
        box.addView(label(
            if (txt.isBlank()) "اكتب نصاً في الحقل ثم اختر لغة الترجمة" else txt, 15f, Color.WHITE
        ).apply {
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            background = rounded(SPECIAL_BG)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        })

        val langs = listOf(
            "العربية" to "ar", "English" to "en", "Türkçe" to "tr", "فارسی" to "fa",
            "کوردی" to "ckb", "Français" to "fr", "Español" to "es", "Deutsch" to "de",
            "Русский" to "ru", "Italiano" to "it", "中文" to "zh-CN", "हिन्दी" to "hi"
        )
        box.addView(hScroll(*langs.map { (name, code) ->
            chip(name, false) { doTranslate(code) }
        }.toTypedArray()))

        val out = label("", 16f, 0xFFB2DFDB.toInt()).apply {
            maxLines = 4
            minHeight = dp(56)
            background = rounded(SPECIAL_BG)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(4), 0, dp(4)) }
        }
        trOut = out
        box.addView(out)

        val acts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        acts.addView(pill("🔄 تحديث", 1f, SPECIAL_BG, 40, 13f) { renderContent() })
        acts.addView(pill("✔ استبدال", 1.2f, ACCENT_BG, 40, 13f) {
            if (trResult.isNotBlank()) {
                replaceFieldText(trResult, trWasSel)
                panel = P_KEYS
                renderAll()
            }
        })
        acts.addView(pill("➕ إدراج", 1f, SPECIAL_BG, 40, 13f) {
            if (trResult.isNotBlank()) currentInputConnection?.commitText(trResult, 1)
        })
        box.addView(acts)
        return box
    }

    private fun doTranslate(lang: String) {
        if (trSource.isBlank()) { trOut?.text = "لا يوجد نص للترجمة"; return }
        val gen = ++trGen
        trOut?.text = "جاري الترجمة…"
        val src = trSource
        io.execute {
            try {
                val res = Net.translate(src, lang)
                ui.post {
                    if (gen == trGen) { trResult = res; trOut?.text = res }
                }
            } catch (e: Exception) {
                ui.post { if (gen == trGen) trOut?.text = "تعذّرت الترجمة، تأكد من الإنترنت" }
            }
        }
    }

    // ================= GIF =================

    private fun gifPanel(): View {
        val box = panelBox()
        val status = label("", 12f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))
        }
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        gifStatus = status
        gifStrip = strip

        val cats = listOf(
            "🔥 رائج" to "", "🔍 بحث بنص الحقل" to "::FIELD", "😂" to "funny", "❤️" to "love",
            "👍" to "thumbs up", "😢" to "sad", "🎉" to "party", "😮" to "wow",
            "😡" to "angry", "🥰" to "cute", "🤝" to "thanks"
        )
        box.addView(hScroll(*cats.map { (name, q) ->
            chip(name, false) {
                if (q == "::FIELD") {
                    val t = fieldText().first.trim().take(50)
                    if (t.isEmpty()) status.text = "اكتب كلمة البحث في الحقل أولاً" else loadGifs(t)
                } else loadGifs(q)
            }
        }.toTypedArray()))
        box.addView(status)
        box.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            addView(strip)
        })

        if (Config.GIPHY_KEY.startsWith("PUT_")) {
            status.text = "⚠️ ضع مفتاح GIPHY في ملف Config.kt (الخطوات في README)"
        } else {
            loadGifs("")
        }
        return box
    }

    private fun loadGifs(q: String) {
        val gen = ++gifGen
        gifStatus?.text = "جاري التحميل…"
        gifStrip?.removeAllViews()
        io.execute {
            try {
                val items = Net.giphy(q)
                ui.post {
                    if (gen != gifGen) return@post
                    gifStatus?.text =
                        if (items.isEmpty()) "لا توجد نتائج" else "اضغط على الصورة لإرسالها • Powered by GIPHY"
                    items.forEach { (thumb, full) -> addGifThumb(thumb, full, gen) }
                }
            } catch (e: Exception) {
                ui.post {
                    if (gen == gifGen) gifStatus?.text = "تعذّر الاتصال (الإنترنت أو مفتاح GIPHY)"
                }
            }
        }
    }

    private fun addGifThumb(thumbUrl: String, fullUrl: String, gen: Int) {
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(SPECIAL_BG)
            layoutParams = LinearLayout.LayoutParams(dp(130), dp(130)).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            setOnClickListener { sendGif(fullUrl) }
        }
        gifStrip?.addView(iv)
        io.execute {
            try {
                val bmp = URL(thumbUrl).openStream().use { BitmapFactory.decodeStream(it) }
                if (bmp != null) ui.post { if (gen == gifGen) iv.setImageBitmap(bmp) }
            } catch (e: Exception) {
            }
        }
    }

    private fun sendGif(url: String) {
        gifStatus?.text = "جاري تجهيز الـGIF…"
        io.execute {
            try {
                val dir = File(cacheDir, "gifs").apply { mkdirs() }
                val f = File(dir, "gif_${System.currentTimeMillis()}.gif")
                URL(url).openStream().use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                ui.post { commitGif(f, url) }
            } catch (e: Exception) {
                ui.post { gifStatus?.text = "تعذّر تحميل الـGIF" }
            }
        }
    }

    private fun commitGif(f: File, url: String) {
        val ic = currentInputConnection
        val info = currentInputEditorInfo
        if (ic == null || info == null) return
        val supported = EditorInfoCompat.getContentMimeTypes(info)
            .any { ClipDescription.compareMimeTypes("image/gif", it) }
        if (!supported) {
            ic.commitText(url, 1)
            gifStatus?.text = "هذا التطبيق لا يقبل GIF مباشرة، أُدرج الرابط بدلاً منه"
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val desc = ClipDescription("gif", arrayOf("image/gif"))
            val content = InputContentInfoCompat(uri, desc, Uri.parse(url))
            var flags = 0
            if (Build.VERSION.SDK_INT >= 25) {
                flags = flags or InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
            }
            val ok = InputConnectionCompat.commitContent(ic, info, content, flags, null)
            if (!ok) {
                ic.commitText(url, 1)
                gifStatus?.text = "تعذّر إرسال الصورة، أُدرج الرابط"
            }
        } catch (e: Exception) {
            ic.commitText(url, 1)
        }
    }

    private fun cleanCache() {
        try {
            File(cacheDir, "gifs").listFiles()?.forEach {
                if (System.currentTimeMillis() - it.lastModified() > 24L * 3600 * 1000) it.delete()
            }
        } catch (e: Exception) {
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
