package com.meu.k2401launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.Locale

class MainActivity : Activity() {

    data class AppInfo(val label: String, val packageName: String, val icon: Drawable)

    private val prefs by lazy { getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE) }
    private val defaultNames = listOf("Android Auto", "Rádio FM", "Telefone / BT", "Navegação")

    private lateinit var slots: List<View>
    private lateinit var icons: List<ImageView>
    private lateinit var titles: List<TextView>
    private lateinit var subs: List<TextView>

    private lateinit var btnObdStatus: TextView
    private lateinit var valTemp: TextView
    private lateinit var subTemp: TextView
    private lateinit var valFuel: TextView
    private lateinit var subFuel: TextView
    private lateinit var valRpm: TextView
    private lateinit var subRpm: TextView
    private lateinit var valVolt: TextView
    private lateinit var subVolt: TextView

    private lateinit var obdManager: ObdManager
    private var lastObdData = ObdData()
    private var pendingActionAfterBtPerm: (() -> Unit)? = null

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

        btnObdStatus = findViewById(R.id.btnObdStatus)
        valTemp = findViewById(R.id.valTemp)
        subTemp = findViewById(R.id.subTemp)
        valFuel = findViewById(R.id.valFuel)
        subFuel = findViewById(R.id.subFuel)
        valRpm = findViewById(R.id.valRpm)
        subRpm = findViewById(R.id.subRpm)
        valVolt = findViewById(R.id.valVolt)
        subVolt = findViewById(R.id.subVolt)

        slots.forEachIndexed { index, view ->
            view.setOnClickListener {
                val savedPkg = prefs.getString("slot_$index", null)
                if (savedPkg != null) launchApp(savedPkg) else openAppPicker(forSlot = index)
            }
            view.setOnLongClickListener {
                openAppPicker(forSlot = index)
                true
            }
        }

        findViewById<View>(R.id.btnAllApps).setOnClickListener { openAppPicker(forSlot = null) }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }

        val btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        obdManager = ObdManager(btAdapter) { data ->
            lastObdData = data
            renderObdData(data)
        }
        obdManager.isEthanol = prefs.getBoolean("use_ethanol", false)

        btnObdStatus.setOnClickListener {
            ensureBluetoothPermission { showObdDevicePicker() }
        }

        // Toque no Consumo alterna Gasolina E27 / Etanol; segurar zera a média
        findViewById<View>(R.id.cardFuel).setOnClickListener {
            val next = !obdManager.isEthanol
            obdManager.isEthanol = next
            prefs.edit().putBoolean("use_ethanol", next).apply()
            renderObdData(lastObdData)
            Toast.makeText(
                this,
                if (next) "Combustível: Etanol" else "Combustível: Gasolina E27",
                Toast.LENGTH_SHORT
            ).show()
        }
        findViewById<View>(R.id.cardFuel).setOnLongClickListener {
            obdManager.resetTrip()
            Toast.makeText(this, "Média de consumo zerada", Toast.LENGTH_SHORT).show()
            true
        }

        refreshSlots()
        renderObdData(ObdData())

        val savedMac = prefs.getString("obd_mac", null)
        if (savedMac != null && hasBluetoothPermission()) {
            obdManager.connect(savedMac)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSlots()
    }

    override fun onDestroy() {
        super.onDestroy()
        obdManager.disconnect()
    }

    private fun renderObdData(d: ObdData) {
        btnObdStatus.text = "● ${d.statusText}"
        btnObdStatus.setTextColor(
            when {
                d.connected -> Color.parseColor("#4ADE80")
                d.statusText.contains("Conect", true) -> Color.parseColor("#FBBF24")
                else -> Color.parseColor("#8E95A5")
            }
        )

        // 1. Temperatura Motor
        val t = d.coolantTempC
        if (t != null) {
            valTemp.text = "$t °C"
            val (color, label) = when {
                t < 60 -> "#4DA6FF" to "Motor frio"
                t <= 97 -> "#FFFFFF" to "Normal"
                t <= 103 -> "#FBBF24" to "Atenção!"
                else -> "#F87171" to "SUPERAQUEC.!"
            }
            valTemp.setTextColor(Color.parseColor(color))
            subTemp.text = label
        } else {
            valTemp.text = "-- °C"
            valTemp.setTextColor(Color.WHITE)
            subTemp.text = "Arrefecimento"
        }

        // 2. Consumo
        val fuelTag = if (obdManager.isEthanol) "Etanol" else "Gas E27"
        val avgTxt = d.avgKmL?.let { String.format(Locale.US, "%.1f", it) } ?: "--"
        if (d.instantKmL != null) {
            valFuel.text = String.format(Locale.US, "%.1f km/L", d.instantKmL)
        } else if (d.litersPerHour != null && (d.speedKmh ?: 0) <= 2) {
            valFuel.text = String.format(Locale.US, "%.1f L/h", d.litersPerHour)
        } else {
            valFuel.text = "-- km/L"
        }
        subFuel.text = "$fuelTag • Méd $avgTxt"

        // 3. RPM + Marcha + Carga
        val rpmTxt = d.rpm?.toString() ?: "--"
        valRpm.text = "$rpmTxt • ${d.gear}"
        val loadTxt = d.loadPct?.let { "$it%" } ?: "--%"
        val spdTxt = d.speedKmh?.let { "$it km/h" } ?: "--"
        subRpm.text = "Carga $loadTxt • $spdTxt"

        // 4. Bateria / Alternador
        val v = d.voltage
        if (v != null) {
            valVolt.text = String.format(Locale.US, "%.1f V", v)
            val motorLigado = (d.rpm ?: 0) > 400
            val (vColor, vLabel) = when {
                v < 11.8 -> "#F87171" to "Bateria baixa!"
                motorLigado && v < 12.6 -> "#FBBF24" to "Alternador?"
                v >= 13.3 -> "#4ADE80" to "Carregando"
                else -> "#FFFFFF" to "Chave ligada"
            }
            valVolt.setTextColor(Color.parseColor(vColor))
            subVolt.text = vLabel
        } else {
            valVolt.text = "-- V"
            valVolt.setTextColor(Color.parseColor("#4ADE80"))
            subVolt.text = "Alternador"
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun ensureBluetoothPermission(action: () -> Unit) {
        if (hasBluetoothPermission()) {
            action()
        } else {
            pendingActionAfterBtPerm = action
            if (Build.VERSION.SDK_INT >= 31) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1001)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            pendingActionAfterBtPerm?.invoke()
        } else if (requestCode == 1001) {
            Toast.makeText(this, "Permissão Bluetooth necessária p/ OBD", Toast.LENGTH_SHORT).show()
        }
        pendingActionAfterBtPerm = null
    }

    @SuppressLint("MissingPermission")
    private fun showObdDevicePicker() {
        val btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            Toast.makeText(this, "Ligue o Bluetooth e pareie o ELM327 antes", Toast.LENGTH_LONG).show()
            return
        }

        val devices = btAdapter.bondedDevices?.toList().orEmpty()
        if (devices.isEmpty()) {
            Toast.makeText(
                this,
                "Nenhum aparelho pareado. Pareie o ELM327 em Ajustes > Bluetooth.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val labels = devices.map { "${it.name ?: "Dispositivo"} (${it.address})" }.toMutableList()
        labels.add("Desconectar OBD")

        AlertDialog.Builder(this)
            .setTitle("Selecionar Scanner ELM327")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == devices.size) {
                    prefs.edit().remove("obd_mac").apply()
                    obdManager.disconnect()
                } else {
                    val chosen = devices[which]
                    prefs.edit().putString("obd_mac", chosen.address).apply()
                    obdManager.connect(chosen.address)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
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
