package com.example.dispensadoraguaiot

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val parser = BluetoothProtocolParser()
    private var estadoActual = DispensadorUiState.Conectado()
    private val firebaseManager = FirebaseIoTManager()

    private var btSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val BT_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // Token de seguridad ISO 27000 para autenticar comandos hacia el Arduino
    private val SECURITY_TOKEN = "AUTH:TI3042|"

    private var mantenerConexion = false
    private var conectandoActualmente = false
    private var dosisSeleccionada = 200
    private var bombaActiva = false
    private var ultimaSubidaFirebase = 0L
    private val historialLogs = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        solicitarPermisosSiFaltan()
        configurarNavegacionPestanas()
        configurarControlesDispensador()
        inicializarFirebase()
    }

    private fun inicializarFirebase() {
        val tvEstadoFirebase = findViewById<TextView>(R.id.tvEstadoFirebase)
        val tvUidSesion = findViewById<TextView>(R.id.tvUidSesion)

        firebaseManager.autenticarSesion { uid ->
            runOnUiThread {
                tvUidSesion.text = "• Sesión Firebase Auth: Activa (ID: $uid)"
            }
        }

        firebaseManager.escucharEstadoNube { conectado ->
            runOnUiThread {
                if (conectado) {
                    tvEstadoFirebase.text = "Firebase: Sincronizado (En línea)"
                    tvEstadoFirebase.setTextColor(Color.parseColor("#00E676"))
                } else {
                    tvEstadoFirebase.text = "Firebase: Modo Offline (Caché local)"
                    tvEstadoFirebase.setTextColor(Color.parseColor("#FFB300"))
                }
            }
        }

        firebaseManager.escucharHistorial { listaRegistros ->
            runOnUiThread {
                renderizarHistorial(listaRegistros)
            }
        }
    }

    private fun configurarNavegacionPestanas() {
        val tabMonitor = findViewById<MaterialButton>(R.id.tabMonitor)
        val tabHistorial = findViewById<MaterialButton>(R.id.tabHistorial)
        val tabDiagnostico = findViewById<MaterialButton>(R.id.tabDiagnostico)

        val layoutMonitor = findViewById<ScrollView>(R.id.layoutMonitor)
        val layoutHistorial = findViewById<ScrollView>(R.id.layoutHistorial)
        val layoutDiagnostico = findViewById<ScrollView>(R.id.layoutDiagnostico)

        fun seleccionarPestana(indice: Int) {
            layoutMonitor.visibility = if (indice == 0) View.VISIBLE else View.GONE
            layoutHistorial.visibility = if (indice == 1) View.VISIBLE else View.GONE
            layoutDiagnostico.visibility = if (indice == 2) View.VISIBLE else View.GONE

            val tabs = listOf(tabMonitor, tabHistorial, tabDiagnostico)
            tabs.forEachIndexed { i, boton ->
                if (i == indice) {
                    boton.setBackgroundColor(Color.parseColor("#5E35B1"))
                    boton.setTextColor(Color.WHITE)
                } else {
                    boton.setBackgroundColor(Color.parseColor("#E0E0E0"))
                    boton.setTextColor(Color.parseColor("#333333"))
                }
            }
        }

        tabMonitor.setOnClickListener { seleccionarPestana(0) }
        tabHistorial.setOnClickListener { seleccionarPestana(1) }
        tabDiagnostico.setOnClickListener { seleccionarPestana(2) }
    }

    private fun configurarControlesDispensador() {
        val btnReconectar = findViewById<Button>(R.id.btnReconectar)
        val btnDispensar = findViewById<Button>(R.id.btnDispensar)
        val btnDetener = findViewById<Button>(R.id.btnDetener)
        val btnTarar = findViewById<Button>(R.id.btnTarar)
        val btnPerfil = findViewById<Button>(R.id.btnPerfil)
        val chipGroupDosis = findViewById<ChipGroup>(R.id.chipGroupDosis)

        btnPerfil.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        btnReconectar.setOnClickListener {
            if (tienePermisos()) {
                mantenerConexion = true
                conectarHC05()
            } else {
                solicitarPermisosSiFaltan()
            }
        }

        chipGroupDosis.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            dosisSeleccionada = when (id) {
                R.id.chip150 -> 150
                R.id.chip250 -> 250
                else -> 200
            }
            findViewById<ProgressBar>(R.id.progressPeso).max = dosisSeleccionada
            findViewById<TextView>(R.id.tvPeso).text =
                "${estadoActual.pesoActualGramos} g / ${dosisSeleccionada}.0 g"
        }

        btnTarar.setOnClickListener {
            enviarComandoSeguro("TARE")
            Toast.makeText(this, "Balanza tarada a 0.0 g", Toast.LENGTH_SHORT).show()
        }

        btnDispensar.setOnClickListener {
            if (estadoActual.temperaturaAguaCelsius < 15.0f) {
                Toast.makeText(this, "Alerta: Temperatura insuficiente (< 15 °C)", Toast.LENGTH_SHORT).show()
                agregarLogSerial("BLOQUEO -> Temp < 15°C")
            } else {
                enviarComandoSeguro("SET:$dosisSeleccionada")
                bombaActiva = true
                findViewById<TextView>(R.id.tvEstadoBomba).text =
                    "Actuador (Relé): Dispensando ($dosisSeleccionada g)..."

                firebaseManager.guardarDispensado(dosisSeleccionada, estadoActual.temperaturaAguaCelsius)
                agregarLogSerial("CLOUD -> Registro guardado en Firebase")
                Toast.makeText(this, "Dispensando $dosisSeleccionada g...", Toast.LENGTH_SHORT).show()
            }
        }

        btnDetener.setOnClickListener {
            enviarComandoSeguro("STOP")
            bombaActiva = false
            findViewById<TextView>(R.id.tvEstadoBomba).text = "Actuador (Relé): Detenido"
            Toast.makeText(this, "Dispensado detenido", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderizarHistorial(registros: List<RegistroDispensado>) {
        val container = findViewById<LinearLayout>(R.id.containerHistorial)
        val tvResumen = findViewById<TextView>(R.id.tvResumenHistorial)

        container.removeAllViews()
        val totalGramos = registros.sumOf { it.dosisGramos }
        tvResumen.text = "Total dispensado: $totalGramos g (${registros.size} ciclos)"

        if (registros.isEmpty()) {
            val vacio = TextView(this).apply {
                text = "Aún no hay registros en Firebase. Presiona 'Iniciar Dispensado' para generar el primero."
                setTextColor(Color.parseColor("#757575"))
                textSize = 13f
                setPadding(8, 16, 8, 16)
            }
            container.addView(vacio)
            return
        }

        for (item in registros) {
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 16 }
                radius = 24f
                cardElevation = 3f
                setCardBackgroundColor(Color.WHITE)
            }

            val texto = TextView(this).apply {
                text = "Dosis: ${item.dosisGramos} g  |  Temp: ${item.temperatura} °C\nFecha: ${item.fecha}  •  ${item.estado}"
                setTextColor(Color.parseColor("#212121"))
                textSize = 13f
                setPadding(32, 24, 32, 24)
            }

            card.addView(texto)
            container.addView(card)
        }
    }

    private fun agregarLogSerial(linea: String) {
        val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        historialLogs.addFirst("[$hora] $linea")
        if (historialLogs.size > 12) historialLogs.removeLast()

        runOnUiThread {
            findViewById<TextView>(R.id.tvConsolaSerial).text = historialLogs.joinToString("\n")
        }
    }

    private fun tienePermisos(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun solicitarPermisosSiFaltan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !tienePermisos()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
                101
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun conectarHC05() {
        if (conectandoActualmente) return
        try {
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val btAdapter = btManager.adapter

            if (btAdapter == null || !btAdapter.isEnabled) {
                Toast.makeText(this, "Por favor, enciende el Bluetooth de tu celular", Toast.LENGTH_SHORT).show()
                return
            }

            val dispositivo = btAdapter.bondedDevices.find { it.name == "HC-05" }

            if (dispositivo != null) {
                conectandoActualmente = true
                val tvEstado = findViewById<TextView>(R.id.tvEstadoConexion)
                tvEstado.text = "BT: Conectando a HC-05..."
                tvEstado.setTextColor(Color.parseColor("#FFB300"))

                thread {
                    try {
                        btSocket?.close()
                        btSocket = dispositivo.createRfcommSocketToServiceRecord(BT_UUID)
                        btSocket?.connect()
                        outputStream = btSocket?.outputStream
                        conectandoActualmente = false

                        runOnUiThread {
                            tvEstado.text = "BT: Conectado (HC-05)"
                            tvEstado.setTextColor(Color.parseColor("#00E676"))
                        }
                        agregarLogSerial("CONEXIÓN -> Enlace seguro abierto con HC-05")
                        escucharDatosSeriales(btSocket?.inputStream)

                    } catch (e: Exception) {
                        conectandoActualmente = false
                        runOnUiThread {
                            tvEstado.text = if (mantenerConexion) "BT: Reintentando enlace..." else "BT: Error de conexión"
                            tvEstado.setTextColor(Color.parseColor("#FF5252"))
                        }
                        if (mantenerConexion) {
                            agregarLogSerial("RECONEXIÓN -> Reintentando en 3s...")
                            Thread.sleep(3000)
                            runOnUiThread { conectarHC05() }
                        }
                    }
                }
            } else {
                Toast.makeText(this, "HC-05 no encontrado. Empareja el Bluetooth en Ajustes primero.", Toast.LENGTH_LONG).show()
            }
        } catch (e: SecurityException) {
            conectandoActualmente = false
            Toast.makeText(this, "Falta otorgar permiso de Dispositivos Cercanos", Toast.LENGTH_SHORT).show()
            solicitarPermisosSiFaltan()
        } catch (e: Exception) {
            conectandoActualmente = false
            Toast.makeText(this, "Error inesperado al conectar", Toast.LENGTH_SHORT).show()
        }
    }

    // Verifica integridad de datos (ISO 27000) comparando el Checksum recibido desde Arduino
    private fun validarIntegridadTrama(trama: String): Boolean {
        if (!trama.contains("CHK:")) return true
        return try {
            val partes = trama.removePrefix("BT_DATA:").split(";")
            var peso = 0
            var temp = 0
            var chkRecibido = -1

            for (parte in partes) {
                when {
                    parte.startsWith("WEIGHT:") -> peso = parte.substringAfter("WEIGHT:").toFloat().toInt()
                    parte.startsWith("TEMP:") -> temp = parte.substringAfter("TEMP:").toFloat().toInt()
                    parte.startsWith("CHK:") -> chkRecibido = parte.substringAfter("CHK:").toInt()
                }
            }
            val chkEsperado = (peso + temp) % 97
            chkRecibido == chkEsperado
        } catch (_: Exception) {
            false
        }
    }

    private fun escucharDatosSeriales(inputStream: InputStream?) {
        if (inputStream == null) return
        val buffer = ByteArray(1024)
        var bytes: Int

        while (true) {
            try {
                bytes = inputStream.read(buffer)
                val tramaEntrante = String(buffer, 0, bytes).trim()
                if (tramaEntrante.isEmpty()) continue

                // Tomar la última línea completa si llegaron varias juntas
                val linea = tramaEntrante.lines().lastOrNull { it.startsWith("BT_DATA:") } ?: tramaEntrante

                if (!validarIntegridadTrama(linea)) {
                    agregarLogSerial("ISO-ALERTA -> Trama corrupta descartada: $linea")
                    continue
                }

                estadoActual = parser.procesarTrama(linea, estadoActual)

                if (linea.contains("STATE:OFF")) {
                    bombaActiva = false
                } else if (linea.contains("STATE:ON")) {
                    bombaActiva = true
                }

                val ahora = System.currentTimeMillis()
                if (ahora - ultimaSubidaFirebase >= 1000) {
                    ultimaSubidaFirebase = ahora
                    firebaseManager.publicarTelemetria(
                        estadoActual.pesoActualGramos,
                        estadoActual.temperaturaAguaCelsius,
                        bombaActiva
                    )
                }

                agregarLogSerial("RX [OK] <- $linea")
                actualizarInterfaz()

            } catch (e: Exception) {
                runOnUiThread {
                    val tvEstado = findViewById<TextView>(R.id.tvEstadoConexion)
                    tvEstado.text = if (mantenerConexion) "BT: Reconectando..." else "BT: Conexión perdida"
                    tvEstado.setTextColor(Color.parseColor("#FFB300"))
                }
                if (mantenerConexion) {
                    agregarLogSerial("ALERTA -> Señal perdida. Reconectando en 3s...")
                    Thread.sleep(3000)
                    runOnUiThread { conectarHC05() }
                }
                break
            }
        }
    }

    private fun actualizarInterfaz() {
        runOnUiThread {
            findViewById<TextView>(R.id.tvTemperatura).text = "${estadoActual.temperaturaAguaCelsius} °C"

            val tvEstadoTemp = findViewById<TextView>(R.id.tvEstadoTemp)
            if (estadoActual.temperaturaAguaCelsius >= 15.0f) {
                tvEstadoTemp.text = "Óptima para dispensar (> 15 °C)"
                tvEstadoTemp.setTextColor(Color.parseColor("#2E7D32"))
            } else {
                tvEstadoTemp.text = "Calentando agua..."
                tvEstadoTemp.setTextColor(Color.parseColor("#F57F17"))
            }

            findViewById<TextView>(R.id.tvPeso).text =
                "${estadoActual.pesoActualGramos} g / ${dosisSeleccionada}.0 g"
            findViewById<ProgressBar>(R.id.progressPeso).progress =
                estadoActual.pesoActualGramos.toInt()

            findViewById<TextView>(R.id.tvEstadoBomba).text =
                if (bombaActiva) "Actuador (Relé): Dispensando agua..." else "Actuador (Relé): En espera"

            estadoActual.mensajeAlerta?.let {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun enviarComandoSeguro(comando: String) {
        val tramaSegura = "$SECURITY_TOKEN$comando\n"
        try {
            outputStream?.write(tramaSegura.toByteArray())
            agregarLogSerial("TX [AUTH] -> $SECURITY_TOKEN$comando")
        } catch (e: Exception) {
            Toast.makeText(this, "Error al enviar comando Bluetooth", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        mantenerConexion = false
        super.onDestroy()
        try {
            btSocket?.close()
        } catch (_: Exception) {
        }
    }
}