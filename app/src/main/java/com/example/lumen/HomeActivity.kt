package com.example.lumen

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_home)

        // Object Mode -> launches existing MainActivity (camera + object detector)
        findViewById<View>(R.id.btnObjectMode).setOnClickListener {
            val i = Intent(this, MainActivity::class.java)
            startActivity(i)
        }

        // Placeholders for future modes
        findViewById<View>(R.id.btnLiveTextMode).setOnClickListener {
            val i = Intent(this, LiveTextActivity::class.java)
            startActivity(i)
        }

        findViewById<View>(R.id.btnDocumentMode).setOnClickListener {
            val i = Intent(this, DocumentActivity::class.java)
            startActivity(i)
        }
        findViewById<View>(R.id.btnSceneMode).setOnClickListener {
            val i = Intent(this, SceneActivity::class.java)
            startActivity(i)
        }
    }
}
