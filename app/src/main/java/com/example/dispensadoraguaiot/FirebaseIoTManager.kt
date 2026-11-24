package com.example.dispensadoraguaiot

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RegistroDispensado(
    val id: String = "",
    val fecha: String = "",
    val dosisGramos: Int = 0,
    val temperatura: Float = 0f,
    val estado: String = "Completado"
)

class FirebaseIoTManager {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    private val database: FirebaseDatabase = FirebaseDatabase.getInstance().apply {
        // Habilita caché local offline: guarda datos si cae internet y sincroniza al reconectar
        try {
            setPersistenceEnabled(true)
        } catch (_: Exception) {
        }
    }

    private val telemetriaRef = database.getReference("dispensador/telemetria")
    private val historialRef = database.getReference("dispensador/historial")
    private val conectadoRef = database.getReference(".info/connected")

    // 1. Autenticación silenciosa en Firebase para cumplir estándar de acceso seguro
    fun autenticarSesion(onResult: (String) -> Unit) {
        val usuarioActual = auth.currentUser
        if (usuarioActual != null) {
            onResult(usuarioActual.uid.take(8))
            return
        }
        auth.signInAnonymously()
            .addOnSuccessListener { result ->
                val uid = result.user?.uid?.take(8) ?: "Invitado"
                onResult(uid)
            }
            .addOnFailureListener {
                onResult("Local-Offline")
            }
    }

    // 2. Enviar en tiempo real los valores leídos desde el Arduino hacia Firebase
    fun publicarTelemetria(peso: Float, temperatura: Float, bombaActiva: Boolean) {
        val datos = mapOf(
            "peso_gramos" to peso,
            "temperatura_celsius" to temperatura,
            "bomba_activa" to bombaActiva,
            "timestamp" to System.currentTimeMillis()
        )
        telemetriaRef.setValue(datos)
    }

    // 3. Registrar cada dispensado realizado en el historial de Firebase
    fun guardarDispensado(dosisGramos: Int, temperatura: Float) {
        val id = historialRef.push().key ?: return
        val formato = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        val registro = RegistroDispensado(
            id = id,
            fecha = formato.format(Date()),
            dosisGramos = dosisGramos,
            temperatura = temperatura,
            estado = "Exitoso"
        )
        historialRef.child(id).setValue(registro)
    }

    // 4. Leer el historial en tiempo real desde Firebase para mostrarlo en la app
    fun escucharHistorial(onUpdate: (List<RegistroDispensado>) -> Unit) {
        historialRef.limitToLast(15).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val lista = mutableListOf<RegistroDispensado>()
                for (item in snapshot.children) {
                    item.getValue(RegistroDispensado::class.java)?.let { lista.add(it) }
                }
                onUpdate(lista.reversed())
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    // 5. Verificar estado de conexión con la nube de Firebase
    fun escucharEstadoNube(onStatusChange: (Boolean) -> Unit) {
        conectadoRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val conectado = snapshot.getValue(Boolean::class.java) ?: false
                onStatusChange(conectado)
            }

            override fun onCancelled(error: DatabaseError) {
                onStatusChange(false)
            }
        })
    }
}