package com.faris.pckeyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        root.addView(TextView(this).apply {
            text = "Faris PC Keyboard"
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = "1) اضغط «تفعيل لوحة المفاتيح» وفعّل Faris PC Keyboard.\n" +
                "2) اضغط «اختيار لوحة المفاتيح» واختر Faris PC Keyboard.\n" +
                "3) اكتب في الحقل بالأسفل للتجربة."
            textSize = 16f
            setPadding(0, pad, 0, pad)
        })
        root.addView(Button(this).apply {
            text = "تفعيل لوحة المفاتيح"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "اختيار لوحة المفاتيح"
            setOnClickListener {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        })
        root.addView(EditText(this).apply { hint = "اكتب هنا للتجربة / Type here to test" })
        setContentView(root)
    }
}
