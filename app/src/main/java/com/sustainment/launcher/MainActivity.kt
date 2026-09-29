package com.sustainment.launcher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.GridLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // ================= EDIT ONLY THIS LIST =================
    // TileType.URL  -> opens a website
    // TileType.APP  -> opens an installed app by its package name
    private val tiles = listOf(
        Tile("CMMS",             TileType.URL, "https://cmms.example.com"),
        Tile("Fleet Dashboard",  TileType.URL, "https://fleet.example.com"),
        Tile("Work Tickets",     TileType.URL, "https://tickets.example.com"),
        Tile("Manuals",          TileType.URL, "https://manuals.example.com"),
        Tile("Teams",            TileType.APP, "com.microsoft.teams"),
        Tile("Outlook",          TileType.APP, "com.microsoft.office.outlook"),
        Tile("Camera",           TileType.APP, "com.sec.android.app.camera"),
        Tile("My Files",         TileType.APP, "com.sec.android.app.myfiles")
    )
    private val columns = 2   // change to 3 for smaller tiles
    // =======================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val grid = findViewById<GridLayout>(R.id.grid)
        grid.columnCount = columns

        for (tile in tiles) {
            grid.addView(makeButton(tile.label) { launch(tile) })
        }

        findViewById<Button>(R.id.btnStock).setOnClickListener {
            openStockLauncher()
        }
    }

    private fun makeButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 22f
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(24, 24, 24, 24)
            }
            setPadding(24, 72, 24, 72)
        }
    }

    private fun launch(tile: Tile) {
        when (tile.type) {
            TileType.URL ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(tile.target)))
            TileType.APP -> {
                val intent = packageManager.getLaunchIntentForPackage(tile.target)
                if (intent != null) {
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "${tile.label} not installed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openStockLauncher() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(Intent.createChooser(intent, "Open Home With…"))
    }

    // Keep operators inside the launcher when they press Back
    override fun onBackPressed() {
        // intentionally does nothing
    }

    data class Tile(val label: String, val type: TileType, val target: String)
    enum class TileType { URL, APP }
}
