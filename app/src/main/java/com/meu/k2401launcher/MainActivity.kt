package com.meu.k2401launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {

    data class AppInfo(val label: String, val packageName: String, val icon: Drawable)

    private val prefs by lazy { getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnRadio = findViewById<View>(R.id.btnRadio)
        val btnAndroidAuto = findViewById<View>(R.id.btnAndroidAuto)
        val btnMenu = findViewById<View>(R.id.btnMenu)

        btnRadio.setOnClickListener {
            openOrPick(
                prefKey = "pkg_radio",
                title = "Escolher app de Rádio FM",
                knownPackages = listOf(
                    "com.navradio.navradio",
                    "com.navradio.basic",
                    "com.ts.main.radio",
                    "com.microntek.radio",
                    "com.tw.radio",
                    "com.syu.radio"
                )
            )
        }
        btnRadio.setOnLongClickListener {
            openAppPicker(prefKey = "pkg_radio", title = "Escolher app de Rádio FM")
            true
        }

        btnAndroidAuto.setOnClickListener {
            openOrPick(
                prefKey = "pkg_aa",
                title = "Escolher app do Android Auto (ZLink)",
                knownPackages = listOf(
                    "com.zjinnova.zlink",
                    "com.zlink.app",
                    "com.zjinnova.zlink5"
                )
            )
        }
        btnAndroidAuto.setOnLongClickListener {
            openAppPicker(prefKey = "pkg_aa", title = "Escolher app do Android Auto (ZLink)")
            true
        }

        btnMenu.setOnClickListener {
            AlertDialog.Builder(this)
                .setItems(arrayOf("Todos os Aplicativos", "Ajustes do Android")) { _, which ->
                    if (which == 0) {
                        openAppPicker(prefKey = null, title = "Todos os Aplicativos")
                    } else {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                }
                .show()
        }
    }

    private fun openOrPick(prefKey: String, title: String, knownPackages: List<String>) {
        val saved = prefs.getString(prefKey, null)
        if (saved != null && launchApp(saved)) return

        for (pkg in knownPackages) {
            if (launchApp(pkg)) {
                prefs.edit().putString(prefKey, pkg).apply()
                return
            }
        }
        openAppPicker(prefKey = prefKey, title = title)
    }

    private fun launchApp(pkg: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        startActivity(intent)
        return true
    }

    private fun getInstalledApps(): List<AppInfo> {
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { resolveInfo ->
                val pkg = resolveInfo.activityInfo.packageName
                if (pkg == packageName) return@mapNotNull null
                AppInfo(
                    label = resolveInfo.loadLabel(packageManager).toString(),
                    packageName = pkg,
                    icon = resolveInfo.loadIcon(packageManager)
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun openAppPicker(prefKey: String?, title: String) {
        val apps = getInstalledApps()
        val grid = GridView(this).apply {
            numColumns = 3
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
            verticalSpacing = dp(10)
            horizontalSpacing = dp(10)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(grid)
            .setNegativeButton("Fechar", null)
            .create()

        grid.adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(pos: Int) = apps[pos]
            override fun getItemId(pos: Int) = pos.toLong()

            override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                val app = apps[pos]
                val itemLayout = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                }
                val iv = ImageView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                    setImageDrawable(app.icon)
                }
                val tv = TextView(this@MainActivity).apply {
                    text = app.label
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(6), 0, 0)
                }
                itemLayout.addView(iv)
                itemLayout.addView(tv)
                return itemLayout
            }
        }

        grid.setOnItemClickListener { _, _, pos, _ ->
            val chosen = apps[pos]
            if (prefKey != null) {
                prefs.edit().putString(prefKey, chosen.packageName).apply()
            }
            launchApp(chosen.packageName)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Impede que o botão Voltar feche o launcher
    }
}
