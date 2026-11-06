package com.den.dinero

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- Datos (SQLite local, sin librerías extra) ----------

data class Mov(val id: Long, val delta: Long, val note: String, val ts: Long) // delta en centavos

class Db(ctx: Context) : SQLiteOpenHelper(ctx, "dinero.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE mov(id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "delta INTEGER NOT NULL, note TEXT NOT NULL, ts INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    fun add(delta: Long, note: String) {
        val v = ContentValues().apply {
            put("delta", delta); put("note", note); put("ts", System.currentTimeMillis())
        }
        writableDatabase.insert("mov", null, v)
    }

    fun remove(id: Long) { writableDatabase.delete("mov", "id=?", arrayOf(id.toString())) }

    fun clear() { writableDatabase.delete("mov", null, null) }

    fun all(): List<Mov> {
        val out = mutableListOf<Mov>()
        readableDatabase.rawQuery("SELECT id, delta, note, ts FROM mov ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) out += Mov(c.getLong(0), c.getLong(1), c.getString(2), c.getLong(3))
        }
        return out
    }
}

// ---------- Utilidades ----------

fun parseCents(s: String): Long? =
    s.trim().replace(',', '.').toBigDecimalOrNull()
        ?.movePointRight(2)?.setScale(0, RoundingMode.HALF_UP)?.toLong()

fun fmt(cents: Long): String = String.format(Locale.getDefault(), "%,.2f", cents / 100.0)

fun csv(movs: List<Mov>): String {
    val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    val sb = StringBuilder("\uFEFFsep=;\nFecha;Tipo;Concepto;Monto;Saldo\n")
    var bal = 0L
    for (m in movs.reversed()) {
        bal += m.delta
        val tipo = if (m.delta >= 0) "Ingreso" else "Egreso"
        val note = m.note.replace(";", ",").replace("\n", " ")
        sb.append("${df.format(Date(m.ts))};$tipo;$note;")
            .append(String.format(Locale.getDefault(), "%.2f", m.delta / 100.0)).append(';')
            .append(String.format(Locale.getDefault(), "%.2f", bal / 100.0)).append('\n')
    }
    return sb.toString()
}

// ---------- UI ----------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = Db(applicationContext)
        setContent {
            val green = Color(0xFF2E9E6B)
            val scheme = if (isSystemInDarkTheme())
                darkColorScheme(primary = green, background = Color(0xFF101614), surface = Color(0xFF18211D))
            else
                lightColorScheme(primary = green, background = Color(0xFFF4F8F6), surface = Color.White)
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.background) {
                    App(db)
                }
            }
        }
    }
}

@Composable
fun App(db: Db) {
    val ctx = LocalContext.current
    var movs by remember { mutableStateOf(db.all()) }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    val total = movs.sumOf { it.delta }

    fun refresh() { movs = db.all() }
    fun toast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show()

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openOutputStream(uri)!!.use { it.write(csv(movs).toByteArray(Charsets.UTF_8)) }
            }.onSuccess { toast("Exportado. Ábrelo con Excel") }
                .onFailure { toast("No se pudo exportar") }
        }
    }

    fun apply(sign: Int) {
        val c = parseCents(amount)
        if (c == null || c <= 0) { toast("Ingresa un monto válido"); return }
        db.add(sign * c, note.trim().ifEmpty { if (sign > 0) "Ingreso" else "Egreso" })
        amount = ""; note = ""; refresh()
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Total", color = Color.White.copy(alpha = .8f), fontSize = 14.sp)
                Text("$ ${fmt(total)}", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            }
        }

        OutlinedTextField(
            value = amount, onValueChange = { amount = it },
            label = { Text("Monto") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = note, onValueChange = { note = it },
            label = { Text("Concepto (opcional)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { apply(1) }, Modifier.weight(1f)) { Text("Agregar") }
            Button(
                onClick = { apply(-1) }, Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD9534F))
            ) { Text("Quitar") }
        }
        OutlinedButton(
            onClick = {
                val c = parseCents(amount)
                if (c == null || c < 0) { toast("Ingresa el total deseado en Monto"); return@OutlinedButton }
                val diff = c - total
                if (diff != 0L) db.add(diff, "Ajuste de total")
                amount = ""; note = ""; refresh()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Establecer como total") }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(movs, key = { it.id }) { m ->
                val df = remember { SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()) }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.note, fontWeight = FontWeight.Medium)
                            Text(df.format(Date(m.ts)), fontSize = 12.sp, color = Color.Gray)
                        }
                        Text(
                            (if (m.delta >= 0) "+" else "−") + fmt(kotlin.math.abs(m.delta)),
                            color = if (m.delta >= 0) Color(0xFF2E9E6B) else Color(0xFFD9534F),
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = { db.remove(m.id); refresh() }) { Text("✕") }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { exporter.launch("dinero_${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.csv") },
                Modifier.weight(1f), enabled = movs.isNotEmpty()
            ) { Text("Exportar a Excel") }
            OutlinedButton(onClick = { confirmReset = true }, Modifier.weight(1f), enabled = movs.isNotEmpty()) {
                Text("Borrar todo")
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("¿Borrar todo?") },
            text = { Text("Se eliminarán todos los movimientos y el total volverá a 0.") },
            confirmButton = { TextButton(onClick = { db.clear(); refresh(); confirmReset = false }) { Text("Borrar") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancelar") } }
        )
    }
}
