package com.sustainment.launcher

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // ================= LAYOUT SETTINGS =================
    private val slotCount = 8   // number of tiles on the grid
    private val columns = 2     // change to 3 for smaller tiles
    private val defaultAccent = 0xFF23B5D3.toInt() // MiR cyan
    private val keepAliveMs = 120000L // fetch() keep-alive ping cadence (2 min)
    // ==================================================

    // Newline built from a char code so no backslash escape exists in source.
    private val nl = Char(10).toString()

    // Color-code tiles by system (maintenance zone, CMMS, docs, safety, etc.)
    private val accentPalette = listOf(
        "Toyota Red" to 0xFFEB0A1E.toInt(),
        "MiR Cyan" to 0xFF23B5D3.toInt(),
        "Signal Green" to 0xFF27AE60.toInt(),
        "Caution Amber" to 0xFFF5A623.toInt(),
        "Fleet Violet" to 0xFF8E7CFF.toInt(),
        "Steel Blue" to 0xFF5B7A99.toInt()
    )

    private val iconChoices = listOf(
        "— none —", "🤖", "🚚", "🔧", "🛠️", "⚙️", "📊", "📈", "📋",
        "🗺️", "🧭", "📡", "🏭", "📦", "🧰", "🚨", "🔋", "🌐", "🖥️", "📁"
    )

    private lateinit var prefs: SharedPreferences
    private lateinit var securePrefs: SharedPreferences
    private lateinit var grid: GridLayout

    private var webOverlay: ViewGroup? = null
    private var currentWeb: WebView? = null
    private var refreshRunnable: Runnable? = null

    private val clockHandler = Handler(Looper.getMainLooper())
    private val webHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("tiles", MODE_PRIVATE)
        securePrefs = initSecurePrefs()

        // Persist cookies across overlay open/close and app restarts.
        CookieManager.getInstance().setAcceptCookie(true)

        grid = findViewById(R.id.grid)
        grid.columnCount = columns
        renderTiles()

        findViewById<Button>(R.id.btnStock).setOnClickListener { openStockLauncher() }
        startClock()
    }

    // ---------------- Encrypted storage for credentials ----------------

    private fun initSecurePrefs(): SharedPreferences {
        return try {
            val key = MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                this,
                "tiles_secure",
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback so the app still runs if keystore init fails on a device.
            getSharedPreferences("tiles_secure_fallback", MODE_PRIVATE)
        }
    }

    // ---------------- Dimension helpers ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    // ---------------- Live clock ----------------

    private fun startClock() {
        val tv = findViewById<TextView>(R.id.clock)
        val fmt = SimpleDateFormat("EEE  MMM d   h:mm a", Locale.getDefault())
        val runnable = object : Runnable {
            override fun run() {
                tv.text = fmt.format(Date())
                clockHandler.postDelayed(this, 1000L)
            }
        }
        clockHandler.post(runnable)
    }

    override fun onDestroy() {
        clockHandler.removeCallbacksAndMessages(null)
        webHandler.removeCallbacksAndMessages(null)
        CookieManager.getInstance().flush()
        super.onDestroy()
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
            text = if (isEmpty) {
                "＋" + nl + "Tap to set up"
            } else buildString {
                if (tile.icon.isNotEmpty()) {
                    append(tile.icon)
                    append(nl)
                }
                append(tile.label)
            }
            textSize = if (isEmpty) 15f else 18f
            setTextColor(if (isEmpty) 0xFF9FB3C8.toInt() else 0xFFF5F7FA.toInt())
            isAllCaps = false
            gravity = Gravity.CENTER
            setLineSpacing(dpf(2f), 1f)
            background = if (isEmpty) emptyTileBackground() else filledTileBackground(tile.color)
            stateListAnimator = null
            elevation = if (isEmpty) 0f else dpf(3f)

            setOnClickListener {
                if (isEmpty) configureTile(index, null) else launch(tile)
            }
            setOnLongClickListener {
                showTileOptions(index, tile)
                true
            }
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(140)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(10), dp(10), dp(10), dp(10))
            }
            setPadding(dp(12), dp(16), dp(12), dp(16))
        }
    }

    private fun filledTileBackground(accent: Int): Drawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF17334C.toInt(), 0xFF0E2131.toInt())
        ).apply {
            cornerRadius = dpf(20f)
            setStroke(dp(2), accent)
        }

    private fun emptyTileBackground(): Drawable =
        GradientDrawable().apply {
            cornerRadius = dpf(20f)
            setColor(0x0FFFFFFF)
            setStroke(dp(2), 0xFF3A5670.toInt(), dpf(8f), dpf(6f))
        }

    // ---------------- Persistence ----------------

    private fun loadTile(i: Int): Tile {
        val type = prefs.getString("tile_${i}_type", TileType.EMPTY.name) ?: TileType.EMPTY.name
        val label = prefs.getString("tile_${i}_label", "") ?: ""
        val target = prefs.getString("tile_${i}_target", "") ?: ""
        val icon = prefs.getString("tile_${i}_icon", "") ?: ""
        val color = prefs.getInt("tile_${i}_color", defaultAccent)
        val refresh = prefs.getInt("tile_${i}_refresh", 0)
        val keep = prefs.getBoolean("tile_${i}_keep", false)
        val user = securePrefs.getString("tile_${i}_user", "") ?: ""
        val pass = securePrefs.getString("tile_${i}_pass", "") ?: ""
        return Tile(label, TileType.valueOf(type), target, icon, color, user, pass, refresh, keep)
    }

    private fun saveTile(i: Int, tile: Tile, render: Boolean = true) {
        prefs.edit()
            .putString("tile_${i}_type", tile.type.name)
            .putString("tile_${i}_label", tile.label)
            .putString("tile_${i}_target", tile.target)
            .putString("tile_${i}_icon", tile.icon)
            .putInt("tile_${i}_color", tile.color)
            .putInt("tile_${i}_refresh", tile.refreshSecs)
            .putBoolean("tile_${i}_keep", tile.keepAlive)
            .apply()
        securePrefs.edit()
            .putString("tile_${i}_user", tile.user)
            .putString("tile_${i}_pass", tile.pass)
            .apply()
        if (render) renderTiles()
    }

    private fun clearTile(i: Int) {
        prefs.edit()
            .remove("tile_${i}_type")
            .remove("tile_${i}_label")
            .remove("tile_${i}_target")
            .remove("tile_${i}_icon")
            .remove("tile_${i}_color")
            .remove("tile_${i}_refresh")
            .remove("tile_${i}_keep")
            .apply()
        securePrefs.edit()
            .remove("tile_${i}_user")
            .remove("tile_${i}_pass")
            .apply()
        renderTiles()
    }

    private fun swap(a: Int, b: Int) {
        val ta = loadTile(a)
        val tb = loadTile(b)
        saveTile(a, tb, render = false)
        saveTile(b, ta, render = false)
        renderTiles()
    }

    // ---------------- Long-press options ----------------

    private fun showTileOptions(index: Int, tile: Tile) {
        if (tile.type == TileType.EMPTY) {
            configureTile(index, null)
            return
        }
        val options = arrayOf(
            "✏️  Rename",
            "🎯  Change target (app or web)",
            "😀  Set icon",
            "🎨  Accent color",
            "↔️  Move / swap",
            "⧉  Duplicate",
            "🗑  Clear"
        )
        AlertDialog.Builder(this)
            .setTitle(tile.label.ifEmpty { "Tile ${index + 1}" })
            .setItems(options) { _, which ->
                when (which) {
                    0 -> renameTile(index, tile)
                    1 -> configureTile(index, tile)
                    2 -> setIcon(index, tile)
                    3 -> setAccent(index, tile)
                    4 -> moveTile(index)
                    5 -> duplicateTile(tile)
                    6 -> clearTile(index)
                }
            }
            .show()
    }

    private fun renameTile(index: Int, tile: Tile) {
        val input = EditText(this).apply {
            hint = "Tile name"
            setText(tile.label)
            setSelection(text.length)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename tile")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) saveTile(index, tile.copy(label = name))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setIcon(index: Int, tile: Tile) {
        AlertDialog.Builder(this)
            .setTitle("Choose an icon")
            .setItems(iconChoices.toTypedArray()) { _, which ->
                val chosen = if (which == 0) "" else iconChoices[which]
                saveTile(index, tile.copy(icon = chosen))
            }
            .show()
    }

    private fun setAccent(index: Int, tile: Tile) {
        val names = accentPalette.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Accent color")
            .setItems(names) { _, which ->
                saveTile(index, tile.copy(color = accentPalette[which].second))
            }
            .show()
    }

    private fun moveTile(index: Int) {
        val others = (0 until slotCount).filter { it != index }
        val labels = others.map { slot ->
            val t = loadTile(slot)
            "Slot ${slot + 1}: " + t.label.ifEmpty { "(empty)" }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Swap with…")
            .setItems(labels) { _, which -> swap(index, others[which]) }
            .show()
    }

    private fun duplicateTile(tile: Tile) {
        val empty = (0 until slotCount).firstOrNull { loadTile(it).type == TileType.EMPTY }
        if (empty == null) {
            Toast.makeText(this, "No empty slots to duplicate into", Toast.LENGTH_SHORT).show()
        } else {
            saveTile(empty, tile.copy())
        }
    }

    // ---------------- Assign target (app or URL) ----------------

    private fun configureTile(index: Int, existing: Tile?) {
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Set up tile ${index + 1}" else "Change target")
            .setItems(arrayOf("🌐  Website (URL)", "📱  Installed app")) { _, which ->
                if (which == 0) configureUrl(index, existing) else configureApp(index, existing)
            }
            .show()
    }

    private fun configureUrl(index: Int, existing: Tile?) {
        val isUrl = existing?.type == TileType.URL

        fun field(hintText: String, value: String, type: Int): EditText = EditText(this).apply {
            hint = hintText
            inputType = type
            setText(value)
        }

        val labelInput = field("Label (e.g. CMMS)", existing?.label ?: "", InputType.TYPE_CLASS_TEXT)
        val urlInput = field(
            "https://...",
            if (isUrl) existing!!.target else "https://",
            InputType.TYPE_TEXT_VARIATION_URI
        )
        val userInput = field(
            "Login username (optional)",
            if (isUrl) existing!!.user else "",
            InputType.TYPE_CLASS_TEXT
        )
        val passInput = field(
            "Login password (optional)",
            if (isUrl) existing!!.pass else "",
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        )
        val refreshInput = field(
            "Auto-refresh minutes (0 = off)",
            if (isUrl && existing!!.refreshSecs > 0) (existing.refreshSecs / 60).toString() else "",
            InputType.TYPE_CLASS_NUMBER
        )
        val keepBox = CheckBox(this).apply {
            text = "Keep session alive (background ping)"
            isChecked = isUrl && existing!!.keepAlive
        }

        fun sectionLabel(txt: String) = TextView(this).apply {
            text = txt
            setPadding(0, dp(14), 0, dp(2))
            setTextColor(0xFF23B5D3.toInt())
            textSize = 13f
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
            addView(labelInput)
            addView(urlInput)
            addView(sectionLabel("Auto-login (optional)"))
            addView(userInput)
            addView(passInput)
            addView(sectionLabel("Session"))
            addView(refreshInput)
            addView(keepBox)
        }

        val scroller = ScrollView(this).apply { addView(box) }

        AlertDialog.Builder(this)
            .setTitle("Website tile")
            .setView(scroller)
            .setPositiveButton("Save") { _, _ ->
                var url = urlInput.text.toString().trim()
                if (url.isNotEmpty()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://$url"
                    }
                    val label = labelInput.text.toString().trim().ifEmpty { url }
                    val minutes = refreshInput.text.toString().trim().toIntOrNull() ?: 0
                    saveTile(
                        index,
                        Tile(
                            label = label,
                            type = TileType.URL,
                            target = url,
                            icon = existing?.icon?.ifEmpty { "🌐" } ?: "🌐",
                            color = existing?.color ?: defaultAccent,
                            user = userInput.text.toString(),
                            pass = passInput.text.toString(),
                            refreshSecs = if (minutes > 0) minutes * 60 else 0,
                            keepAlive = keepBox.isChecked
                        )
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun configureApp(index: Int, existing: Tile?) {
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
                saveTile(
                    index,
                    Tile(
                        label = existing?.label?.ifEmpty { label } ?: label,
                        type = TileType.APP,
                        target = pkg,
                        icon = existing?.icon?.ifEmpty { "📱" } ?: "📱",
                        color = existing?.color ?: defaultAccent
                    )
                )
            }
            .show()
    }

    // ---------------- Launching ----------------

    private fun launch(tile: Tile) {
        when (tile.type) {
            TileType.URL -> openWebOverlay(tile)
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

    // ---------------- Auto-login + keep-alive injection ----------------

    private fun injectLogin(web: WebView, tile: Tile) {
        if (tile.user.isEmpty() && tile.pass.isEmpty()) return
        val u64 = Base64.encodeToString(tile.user.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val p64 = Base64.encodeToString(tile.pass.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val js = listOf(
            "(function(){",
            "try{",
            "if(window.__li){return;}",
            "function dec(b){try{return decodeURIComponent(escape(atob(b)));}catch(e){return atob(b);}}",
            "var U=dec('$u64');",
            "var P=dec('$p64');",
            "var pw=document.querySelector('input[type=password]');",
            "if(!pw){return;}",
            "var scope=pw.form||document;",
            "var user=scope.querySelector('input[type=text],input[type=email],input[type=tel],input:not([type])');",
            "function setVal(el,val){ if(!el)return; try{el.focus();}catch(e){} el.value=val; el.dispatchEvent(new Event('input',{bubbles:true})); el.dispatchEvent(new Event('change',{bubbles:true})); }",
            "if(U){ setVal(user,U); }",
            "if(P){ setVal(pw,P); }",
            "window.__li=true;",
            "var f=pw.form;",
            "if(f){ var btn=f.querySelector('button[type=submit],input[type=submit]')||f.querySelector('button'); if(btn){ btn.click(); } else { try{f.submit();}catch(e){} } }",
            "}catch(e){}",
            "})();"
        ).joinToString(nl)
        web.evaluateJavascript(js, null)
    }

    private fun injectKeepAlive(web: WebView) {
        val js = listOf(
            "(function(){",
            "if(window.__ka){return;}",
            "window.__ka=setInterval(function(){ try{ fetch(location.href,{credentials:'include',cache:'no-store'}); }catch(e){} }, $keepAliveMs);",
            "})();"
        ).joinToString(nl)
        web.evaluateJavascript(js, null)
    }

    // ---------------- Full-screen web overlay ----------------

    private fun openWebOverlay(tile: Tile) {
        if (webOverlay != null) return

        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT

        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    injectLogin(view, tile)
                    if (tile.keepAlive) injectKeepAlive(view)
                    // Retry once for forms that render slightly after page load.
                    webHandler.postDelayed({ injectLogin(view, tile) }, 1200L)
                }
            }
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        fun barButton(label: String, bg: Int, fg: Int, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(fg)
            background = GradientDrawable().apply {
                cornerRadius = dpf(12f)
                setColor(bg)
                setStroke(dp(1), 0x3323B5D3)
            }
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(0, wc, 1f)
                .apply { setMargins(dp(6), dp(6), dp(6), dp(6)) }
            setPadding(dp(8), dp(14), dp(8), dp(14))
            setOnClickListener { onClick() }
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0B1D2A.toInt())
            setPadding(dp(6), dp(4), dp(6), dp(4))
            addView(barButton("‹  Back", 0xFF17334C.toInt(), 0xFFF5F7FA.toInt()) {
                if (web.canGoBack()) web.goBack()
            })
            addView(barButton("⟳  Refresh", 0xFF17334C.toInt(), 0xFF23B5D3.toInt()) {
                web.reload()
            })
            addView(barButton("✕  Close", 0xFFEB0A1E.toInt(), 0xFFFFFFFF.toInt()) {
                closeWebOverlay()
            })
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
        web.loadUrl(tile.target)

        // Optional full-page auto-refresh.
        if (tile.refreshSecs > 0) {
            val r = object : Runnable {
                override fun run() {
                    currentWeb?.reload()
                    webHandler.postDelayed(this, tile.refreshSecs * 1000L)
                }
            }
            refreshRunnable = r
            webHandler.postDelayed(r, tile.refreshSecs * 1000L)
        }
    }

    private fun closeWebOverlay() {
        refreshRunnable?.let { webHandler.removeCallbacks(it) }
        refreshRunnable = null
        webHandler.removeCallbacksAndMessages(null)
        CookieManager.getInstance().flush()
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

    data class Tile(
        val label: String,
        val type: TileType,
        val target: String,
        val icon: String = "",
        val color: Int = 0xFF23B5D3.toInt(),
        val user: String = "",
        val pass: String = "",
        val refreshSecs: Int = 0,
        val keepAlive: Boolean = false
    )

    enum class TileType { EMPTY, URL, APP }
}
