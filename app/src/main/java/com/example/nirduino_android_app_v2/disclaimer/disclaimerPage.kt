package com.example.nirduino_android_app_v2.disclaimer

import android.content.Intent
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.nirduino_android_app_v2.MainActivity
import com.example.nirduino_android_app_v2.R

class disclaimerPage : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_disclaimer_page)

        // Edge-to-edge insets
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Views from your XML (ensure these IDs exist):
        val textView = findViewById<TextView>(R.id.disclaimerText)
        val acceptBtn = findViewById<Button>(R.id.acceptButton)

        // Load HTML from assets
        val html = assets.open("disclaimer.html").bufferedReader().use { it.readText() }
        val spanned = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
        textView.text = spanned
        textView.textSize=28f;
        textView.movementMethod = LinkMovementMethod.getInstance() // enables hyperlinks if any

        acceptBtn.setOnClickListener {
            // Persist acceptance if needed
            getSharedPreferences("app_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("disclaimerAccepted", true)
                .apply()

            // Navigate to MainActivity
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)

        }

    }
}
