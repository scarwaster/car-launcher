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
    private val defaultNames = listOf("Navegação", "Música", "Rádio", "Bluetooth")

    private lateinit var slots: List<View>
    private lateinit var icons: List<ImageView>
    private lateinit var titles: List<TextView>
    private lateinit var subs: List<TextView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        slots = listOf(
            findViewById(R.id.slot0),
            findViewById(R.id.slot1),
            findViewById(R.id.slot2),
            findViewById(R.id.slot3)
        )
        icons = listOf(
            findViewById(R.id.icon0),
            findViewById(R.id.icon1),
            findViewById(R.id.icon2),
            findViewById(R.id.icon3)
        )
        titles = listOf(
            findViewById(R.id.title0),
            findViewById(R.id.title1),
            findViewById(R.id.title2),
            findViewById(R.id.title3)
        )
        subs = listOf(
            findViewById(R.id.sub0),
            findViewById(R.id.sub1),
            findViewById(R.id.sub2),
            findViewById(R.id.sub3)
        )

        slots.forEachIndexed { index, view ->
            view.setOnClickListener {
                val savedPkg = prefs.getString("slot_$index", null)
                if (savedPkg != null) {
                    launchApp(savedPkg)
                } else {
                    openAppPicker(forSlot = index)
                }
            }
            view.setOnLongClickListener {
                openAppPicker(forSlot = index)
                true
            }
        }

        findViewById<Button>(R.id.btnAllApps).setOnClickListener {
            openAppPicker(forSlot = null)
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }

        refreshSlots()
    }

    override fun onResume() {
        super.onResume()
        refreshSlots()
    }

    private fun refreshSlots() {
        for (i in 0..3) {
            val savedPkg = prefs.getString("slot_$i", null)
            if (savedPkg != null) {
                try {
                    val appInfo = packageManager.getApplicationInfo(savedPkg, 0)
                    icons[i].setImageDrawable(appInfo.loadIcon(packageManager))
                    icons[i].visibility = View.VISIBLE
                    titles[i].text = appInfo.loadLabel(packageManager)
                    subs[i].visibility = View.GONE
                    continue
                } catch (_: Exception) {
                    // App foi desinstalado; volta pro estado padrão
                }
            }
            icons[i].visibility = View.GONE
            titles[i].text = defaultNames[i]
            subs[i].visibility = View.VISIBLE
        }
    }

    private fun launchApp(pkg: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, "Não foi possível abrir o app", Toast.LENGTH_SHORT).show()
        }
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

    private fun openAppPicker(forSlot: Int?) {
        val apps = getInstalledApps()
        val grid = GridView(this).apply {
            numColumns = 3
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
            verticalSpacing = dp(10)
            horizontalSpacing = dp(10)
        }

        val title = if (forSlot != null) {
            "Escolher app para ${defaultNames[forSlot]}"
        } else {
            "Todos os Aplicativos"
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
            if (forSlot != null) {
                prefs.edit().putString("slot_$forSlot", chosen.packageName).apply()
                refreshSlots()
            } else {
                launchApp(chosen.packageName)
            }
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
