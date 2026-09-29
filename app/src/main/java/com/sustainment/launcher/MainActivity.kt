package com.sustainment.launcher

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // ================= LAYOUT SETTINGS =================
    private val slotCount = 8   // number of tiles on the grid
    private val columns = 2     // change to 3 for smaller tiles
    // ==================================================

    private lateinit var prefs: SharedPreferences
    private lateinit var grid: GridLayout

    private var webOverlay: ViewGroup? = null
    private var currentWeb: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("tiles", MODE_PRIVATE)

        grid = findViewById(R.id.grid)
        grid.columnCount = columns
        renderTiles()

        findViewById<Button>(R.id.btnStock).setOnClickListener { openStockLauncher() }
    }

    // ---------------- Grid rendering ----------------

    private fun renderTiles() {
        grid.removeAllViews()
        for (i in 0 until slotCount) {
            grid.addView(makeButton(i, loadTile(i)))
        }
    }

    private fun makeButton(index: Int, tile: Tile): Button {
        val isEmpty = tile.type == TileType.EMPTY
        return Button(this).apply {
            text = if (isEmpty) "➕ Tap to set up" else tile.label
            textSize = if (isEmpty) 16f else 22f
            isAllCaps = false
            setOnClickListener {
                if (isEmpty) configureTile(index) else launch(tile)
            }
            setOnLongClickListener {
                showTileOptions(index, tile)
                true
            }
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(24, 24, 24, 24)
            }
            setPadding(24, 72, 24, 72)
        }
    }

    // ---------------- Persistence ----------------

    private fun loadTile(i: Int): Tile {
        val type = prefs.getString("tile_${i}_type", TileType.EMPTY.name) ?: TileType.EMPTY.name
        val label = prefs.getString("tile_${i}_label", "") ?: ""
        val target = prefs.getString("tile_${i}_target", "") ?: ""
        return Tile(label, TileType.valueOf(type), target)
    }

    private fun saveTile(i: Int, tile: Tile) {
        prefs.edit()
            .putString("tile_${i}_type", tile.type.name)
            .putString("tile_${i}_label", tile.label)
            .putString("tile_${i}_target", tile.target)
            .apply()
        renderTiles()
    }

    private fun clearTile(i: Int) {
        prefs.edit()
            .remove("tile_${i}_type")
            .remove("tile_${i}_label")
            .remove("tile_${i}_target")
            .apply()
        renderTiles()
    }

    // ---------------- Setup wizard ----------------

    private fun configureTile(index: Int) {
        AlertDialog.Builder(this)
            .setTitle("Set up tile ${index + 1}")
            .setItems(arrayOf("Website (URL)", "Installed app")) { _, which ->
                if (which == 0) configureUrl(index, null) else configureApp(index)
            }
            .show()
    }

    private fun configureUrl(index: Int, existing: Tile?) {
        val labelInput = EditText(this).apply {
            hint = "Label (e.g. CMMS)"
            setText(existing?.label ?: "")
        }
        val urlInput = EditText(this).apply {
            hint = "https://..."
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(if (existing?.type == TileType.URL) existing.target else "https://")
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(labelInput)
            addView(urlInput)
        }
        AlertDialog.Builder(this)
            .setTitle("Website tile")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                var url = urlInput.text.toString().trim()
                if (url.isNotEmpty()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://$url"
                    }
                    val label = labelInput.text.toString().trim().ifEmpty { url }
                    saveTile(index, Tile(label, TileType.URL, url))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun configureApp(index: Int) {
        val pm = packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(query, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }

        if (apps.isEmpty()) {
            Toast.makeText(this, "No launchable apps found", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = apps.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Pick an app")
            .setItems(labels) { _, which ->
                val (label, pkg) = apps[which]
                saveTile(index, Tile(label, TileType.APP, pkg))
            }
            .show()
    }

    private fun showTileOptions(index: Int, tile: Tile) {
        if (tile.type == TileType.EMPTY) {
            configureTile(index)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(tile.label.ifEmpty { "Tile ${index + 1}" })
            .setItems(arrayOf("Edit", "Clear")) { _, which ->
                when (which) {
                    0 -> when (tile.type) {
                        TileType.URL -> configureUrl(index, tile)
                        TileType.APP -> configureApp(index)
                        TileType.EMPTY -> configureTile(index)
                    }
                    1 -> clearTile(index)
                }
            }
            .show()
    }

    // ---------------- Launching ----------------

    private fun launch(tile: Tile) {
        when (tile.type) {
            TileType.URL -> openWebOverlay(tile.target)
            TileType.APP -> {
                val intent = packageManager.getLaunchIntentForPackage(tile.target)
                if (intent != null) {
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "${tile.label} not installed", Toast.LENGTH_SHORT).show()
                }
            }
            TileType.EMPTY -> { /* nothing to launch */ }
        }
    }

    // ---------------- Full-screen web overlay ----------------

    private fun openWebOverlay(url: String) {
        if (webOverlay != null) return

        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT

        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webViewClient = WebViewClient()       // keep navigation inside the launcher
            webChromeClient = WebChromeClient()
        }

        fun barButton(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, wc, 1f).apply { setMargins(8, 0, 8, 0) }
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0B1D2A.toInt())
            setPadding(8, 8, 8, 8)
            addView(barButton("‹ Back") { if (web.canGoBack()) web.goBack() })
            addView(barButton("Refresh") { web.reload() })
            addView(barButton("Close") { closeWebOverlay() })
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(mp, mp)
            addView(bar, LinearLayout.LayoutParams(mp, wc))
            addView(web, LinearLayout.LayoutParams(mp, 0, 1f))
        }

        val container = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(column)
        }

        addContentView(container, FrameLayout.LayoutParams(mp, mp))
        webOverlay = container
        currentWeb = web
        web.loadUrl(url)
    }

    private fun closeWebOverlay() {
        webOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        currentWeb?.destroy()
        webOverlay = null
        currentWeb = null
    }

    // ---------------- System behaviour ----------------

    private fun openStockLauncher() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(Intent.createChooser(intent, "Open Home With…"))
    }

    override fun onBackPressed() {
        val web = currentWeb
        if (webOverlay != null && web != null) {
            if (web.canGoBack()) web.goBack() else closeWebOverlay()
            return
        }
        // On the home grid: intentionally do nothing (keep operators in the launcher)
    }

    data class Tile(val label: String, val type: TileType, val target: String)
    enum class TileType { EMPTY, URL, APP }
}
