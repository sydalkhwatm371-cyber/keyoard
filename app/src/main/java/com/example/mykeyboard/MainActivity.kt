package com.example.mykeyboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (20 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        layout.addView(TextView(this).apply {
            text = "كيبوردي ⌨️"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
        })

        layout.addView(TextView(this).apply {
            text = "١) فعّل الكيبورد من الزر الأول.\n٢) اختره كلوحة المفاتيح الحالية من الزر الثاني.\n٣) جرّبه في الحقل أدناه.\n٤) من شريط الأزرار أعلى الكيبورد: GIF، ترجمة، زخرفة، وزر ⚙ للتحكم بالحجم والأماكن والفراغات."
            textSize = 16f
            setPadding(0, pad, 0, pad)
        })

        layout.addView(Button(this).apply {
            text = "١) تفعيل الكيبورد"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        })

        layout.addView(Button(this).apply {
            text = "٢) اختيار الكيبورد"
            setOnClickListener {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showInputMethodPicker()
            }
        })

        layout.addView(EditText(this).apply {
            hint = "اكتب هنا للتجربة"
        })

        setContentView(layout)
    }
}
