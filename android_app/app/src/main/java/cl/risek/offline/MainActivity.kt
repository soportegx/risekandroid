package cl.risek.offline

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.text.NumberFormat
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private val Purple = Color(0xFFE30613)
private val SoftBg = Color(0xFFF5F6F8)
private val Ink = Color(0xFF1F2937)
private val Muted = Color(0xFF6B7280)
private val DangerBg = Color(0xFFFFE4E6)
private val SuccessBg = Color(0xFFECFDF5)
private const val MAX_NV_LINES = 17
private const val NV_PRICE_LIST = "01"

fun clp(value: Long): String = NumberFormat.getCurrencyInstance(Locale("es", "CL")).format(value)

fun stockText(value: Double): String {
    val whole = value.toLong()
    return if (value == whole.toDouble()) whole.toString() else String.format(Locale("es", "CL"), "%.2f", value)
}

fun stockConsultaText(value: Double): String = stockText(value.coerceAtLeast(0.0))

fun pesosChilenosText(value: Long): String = "${clp(value)} pesos chilenos"

fun fechaCl(fechaIso: String?): String {
    if (fechaIso.isNullOrBlank()) return "-"
    return runCatching {
        LocalDate.parse(fechaIso.take(10)).format(DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale("es", "CL")))
    }.getOrDefault(fechaIso)
}

fun fechaCortaCl(fechaIso: String?): String {
    if (fechaIso.isNullOrBlank()) return "-"
    return runCatching {
        LocalDate.parse(fechaIso.take(10)).format(DateTimeFormatter.ofPattern("dd-MM-yy", Locale("es", "CL")))
    }.getOrDefault(fechaIso.take(8))
}

fun clpCompact(value: Long): String = "$" + NumberFormat.getNumberInstance(Locale("es", "CL")).format(value)

fun fechaHoraCl(millis: Long): String = runCatching {
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm", Locale("es", "CL")))
}.getOrDefault("-")

fun datePickerMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
fun datePickerLocalDate(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()

fun cuentaSaldoDocumento(d: CuentaCorrienteEntity): Long {
    val tipo = d.ventaTipo.uppercase(Locale("es", "CL"))
    val diferencia = d.ventaTotalVenta - d.ventaPagoTotal
    return when (tipo) {
        "FE", "FA", "BO", "CH" -> diferencia.coerceAtLeast(0)
        "CE", "NC" -> -(diferencia.coerceAtLeast(0))
        else -> d.saldo
    }
}

fun cuentaDeudaTotal(rows: List<CuentaCorrienteEntity>): Long =
    rows.sumOf { cuentaSaldoDocumento(it).coerceAtLeast(0) }

fun cuentaNcTotal(rows: List<CuentaCorrienteEntity>): Long =
    rows.sumOf { cuentaSaldoDocumento(it).coerceAtMost(0) }

fun cuentaSaldoTotal(rows: List<CuentaCorrienteEntity>): Long =
    rows.sumOf { cuentaSaldoDocumento(it) }

fun documentosPendientes(rows: List<CuentaCorrienteEntity>): List<CuentaCorrienteEntity> =
    rows.filter {
        it.ventaTipo.uppercase(Locale("es", "CL")) in setOf("FE", "FA", "BO", "CH") &&
            cuentaSaldoDocumento(it) > 0
    }

fun facturasPendientes(rows: List<CuentaCorrienteEntity>): List<CuentaCorrienteEntity> =
    rows.filter {
        it.ventaTipo.uppercase(Locale("es", "CL")) in setOf("FE", "FA") &&
            cuentaSaldoDocumento(it) > 0
    }

fun documentosConSaldoPendiente(rows: List<CuentaCorrienteEntity>): List<CuentaCorrienteEntity> =
    rows.filter { cuentaSaldoDocumento(it) != 0L }

enum class VoiceProductAction {
    ADD_OR_SEARCH,
    DELETE
}

data class VoiceProductCommand(
    val query: String,
    val exactCode: Boolean,
    val uxe: String?,
    val action: VoiceProductAction = VoiceProductAction.ADD_OR_SEARCH,
    val cajas: String? = null,
    val byFamily: Boolean = false
)

data class ResumenClienteCandidate(
    val clienteRut: String,
    val clienteNombre: String?
)

fun parseVoiceProductCommand(text: String): VoiceProductCommand {
    val normalized = text.lowercase(Locale("es", "CL"))
        .replace("código", "codigo")
        .replace(",", ".")
        .trim()
    val qty = Regex("""(?:agrega|agregar)\s+(\d+(?:\.\d+)?)""")
        .find(normalized)
        ?.groupValues
        ?.getOrNull(1)
        ?: Regex("""(\d+(?:\.\d+)?)\s*(uxe|unidad|unidades|envase|envases)""")
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
    val cajas = Regex("""(?:agrega|agregar)\s+(\d+(?:\.\d+)?)\s*cajas?\b""")
        .find(normalized)
        ?.groupValues
        ?.getOrNull(1)
        ?: Regex("""(\d+(?:\.\d+)?)\s*cajas?\b""")
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
    val isDelete = Regex("""\b(elimina|eliminar|borra|borrar|quita|quitar)\b""").containsMatchIn(normalized)
    val code = Regex("""(?:codigo|cod|producto)\s+(\d+)""")
        .find(normalized)
        ?.groupValues
        ?.getOrNull(1)
    val family = Regex("""\bfamilia\s+(.+?)(?:\s+agrega|\s+agregar|$)""")
        .find(normalized)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
    val action = if (isDelete) VoiceProductAction.DELETE else VoiceProductAction.ADD_OR_SEARCH
    val uxe = if (cajas != null) null else qty
    if (!family.isNullOrBlank()) return VoiceProductCommand(family, false, uxe, action, cajas, byFamily = true)
    if (!code.isNullOrBlank()) return VoiceProductCommand(code, true, uxe, action, cajas)
    if (normalized.matches(Regex("""\d+"""))) return VoiceProductCommand(normalized, true, uxe, action, cajas)

    val cleanQuery = normalized
        .replace(Regex("""\b(agrega|agregar)\s+\d+(?:\.\d+)?\s*cajas?\b"""), " ")
        .replace(Regex("""\b(agrega|agregar)\s+\d+(?:\.\d+)?\b"""), " ")
        .replace(Regex("""\b(busca|buscar|agrega|agregar|elimina|eliminar|borra|borrar|quita|quitar|producto|codigo|cod)\b"""), " ")
        .replace(Regex("""\d+(?:\.\d+)?\s*cajas?\b"""), " ")
        .replace(Regex("""\d+(?:\.\d+)?\s*(uxe|unidad|unidades|envase|envases)"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return VoiceProductCommand(cleanQuery.ifBlank { normalized }, false, uxe, action, cajas)
}

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SyncWorker.schedule(this)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Purple,
                    background = SoftBg,
                    surface = Color.White,
                    onSurface = Ink,
                    secondary = Ink
                )
            ) { RisekRoot() }
        }
    }
}

class MainVm: ViewModel() {
    private val auth = ServiceLocator.authRepository
    private val catalog = ServiceLocator.catalogRepository
    private val nv = ServiceLocator.nvRepository
    val users = auth.users().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val nvs = nv.observeNv().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val allClientes = catalog.observeClientes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val familias = catalog.familias().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val cartola = catalog.cuentaCorriente().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var logged by mutableStateOf(false); private set
    var currentUser by mutableStateOf<SecUserEntity?>(null)
    var moduleChoicePending by mutableStateOf(false)
    var supervisorMode by mutableStateOf(false)
    var gerenteMode by mutableStateOf(false)
    var logisticaMode by mutableStateOf(false)
    var message by mutableStateOf("")
    var loginErrorPopup by mutableStateOf<String?>(null)
    var syncMessage by mutableStateOf("Sin descarga ejecutada")
    var isSyncing by mutableStateOf(false)
    var lastSyncAt by mutableStateOf(ServiceLocator.session.lastSuccessfulSyncAt())
    var apiOk by mutableStateOf<Boolean?>(null)
    var counts by mutableStateOf(LocalCounts())
    var userSyncStarted by mutableStateOf(false)
    var showSyncReminder by mutableStateOf(false)
    var syncDownloadDialogTitle by mutableStateOf<String?>(null)
    var syncDownloadDialogMessage by mutableStateOf("")
    var syncDownloadDialogIsError by mutableStateOf(false)

    var selectedCliente by mutableStateOf<ClienteEntity?>(null)
    var direcciones by mutableStateOf<List<ClienteDireccionEntity>>(emptyList())
    var selectedDireccion by mutableStateOf<String?>(null)
    var fechaReparto by mutableStateOf(ServiceLocator.session.lastFechaReparto() ?: LocalDate.now().plusDays(1).toString())
    var cart by mutableStateOf<List<NvLineEntity>>(emptyList())
    var clientes by mutableStateOf<List<ClienteEntity>>(emptyList())
    var productos by mutableStateOf<List<ProductoEntity>>(emptyList())
    var productListPrices by mutableStateOf<Map<String, Long>>(emptyMap())
    var selectedFamilia by mutableStateOf<FamiliaEntity?>(null)
    var productoSearchQuery by mutableStateOf("")
    var productoPage by mutableStateOf(0)
    var productoFamiliaTotal by mutableStateOf(0)
    private var productSearchSeq = 0
    val productoPageSize = 25
    var editOfflineId by mutableStateOf<String?>(null)
    var observacionNv by mutableStateOf("")
    var ultimasNvCliente by mutableStateOf<List<PedidoClienteDto>>(emptyList())
    var cuentaCorriente by mutableStateOf<CuentaCorrienteDto?>(null)
    var cuentaCorrienteOnlineRows by mutableStateOf<List<CuentaCorrienteEntity>>(emptyList())
    var cuentaCorrienteModoOnline by mutableStateOf(false)
    var cuentaCorrienteRutOnline by mutableStateOf<String?>(null)
    var pedidoDetalleHeader by mutableStateOf<NvHeaderEntity?>(null)
    var pedidoDetalleLines by mutableStateOf<List<NvLineEntity>>(emptyList())
    var resumenHeader by mutableStateOf<NvHeaderEntity?>(null)
    var resumenLines by mutableStateOf<List<NvLineEntity>>(emptyList())
    var ultimaVentaDetalle by mutableStateOf<UltimaVentaDetalleDto?>(null)
    var resumenClienteVoz by mutableStateOf<String?>(null)
    var resumenClienteChartRows by mutableStateOf<List<CuentaCorrienteEntity>>(emptyList())
    var resumenClienteCandidates by mutableStateOf<List<ResumenClienteCandidate>>(emptyList())
    var appUpdateInfo by mutableStateOf<AppVersionDto?>(null)
    var showAppUpdateDialog by mutableStateOf(false)
    var pdfPreviewTitle by mutableStateOf<String?>(null)
    var pdfPreviewFile by mutableStateOf<File?>(null)
    var pdfPreviewBitmap by mutableStateOf<Bitmap?>(null)
    private var updateCheckDone = false
    private var dismissedUpdateCode: Int? = null
    var latestAppVersion by mutableStateOf<AppVersionDto?>(null)
    var supervisorDashboard by mutableStateOf<SupervisorDashboardDto?>(null)
    var supervisorVendedorDetalle by mutableStateOf<SupervisorVendedorDetalleDto?>(null)
    var gerenteDashboard by mutableStateOf<GerenteDashboardDto?>(null)
    var logisticaDashboard by mutableStateOf<LogisticaDashboardDto?>(null)
    var logisticaFecha by mutableStateOf(LocalDate.now().toString())

    init { loadLocalCounts() }

    private fun nowText(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"))
    private fun showSyncDownloadDialog(title: String, body: String, isError: Boolean = false) {
        syncDownloadDialogTitle = title
        syncDownloadDialogMessage = body
        syncDownloadDialogIsError = isError
    }
    fun cerrarSyncDownloadDialog() {
        syncDownloadDialogTitle = null
        syncDownloadDialogMessage = ""
        syncDownloadDialogIsError = false
    }
    private fun evaluarAvisoSyncDiario() {
        val now = LocalDateTime.now()
        val today = now.toLocalDate().toString()
        val pendienteHoy = ServiceLocator.session.lastSuccessfulSyncDate() != today
        val noAvisadoHoy = ServiceLocator.session.lastSyncReminderDate() != today
        showSyncReminder = now.hour < 12 && pendienteHoy && noAvisadoHoy
        if (showSyncReminder) ServiceLocator.session.saveSyncReminderDate(today)
    }
    fun cerrarAvisoSyncDiario() {
        showSyncReminder = false
    }
    private fun roleText(user: SecUserEntity): String = "${user.secUserName} ${user.vendedorCodigo ?: ""}"
        .lowercase(Locale("es", "CL"))
        .replace("á", "a").replace("é", "e").replace("í", "i").replace("ó", "o").replace("ú", "u")
    private fun isGerenteUser(user: SecUserEntity): Boolean {
        val raw = roleText(user)
        return raw.contains("administrado") || raw.contains("administrador") || raw.contains("ricardo sepulveda")
    }
    private fun isSupervisorUser(user: SecUserEntity): Boolean {
        val raw = roleText(user)
        return isGerenteUser(user) || raw.contains("alejandro soto")
    }
    fun puedeVerGerencia(): Boolean = currentUser?.let { isGerenteUser(it) } == true
    fun puedeVerSupervisor(): Boolean = currentUser?.let { isSupervisorUser(it) } == true
    fun tieneVendedor(): Boolean = !currentUser?.vendedorCodigo.isNullOrBlank()
    private fun enterAfterLogin(user: SecUserEntity) {
        currentUser = user
        val soloRepartos = user.vendedorCodigo.isNullOrBlank()
        moduleChoicePending = !soloRepartos
        supervisorMode = false
        gerenteMode = false
        logisticaMode = soloRepartos
        logged = true
        showSyncReminder = false
        if (soloRepartos) message = "Usuario sin vendedor: acceso solo a Repartos"
    }
    fun seleccionarModuloVentas() {
        if (!tieneVendedor()) {
            message = "Usuario sin vendedor asociado: solo puede ingresar a Repartos"
            seleccionarModuloLogistica()
            return
        }
        supervisorMode = false
        gerenteMode = false
        logisticaMode = false
        moduleChoicePending = false
        evaluarAvisoSyncDiario()
        message = "Modo ventas offline"
    }
    fun seleccionarModuloLogistica() {
        logisticaMode = true
        supervisorMode = false
        gerenteMode = false
        moduleChoicePending = false
        showSyncReminder = false
        message = "RISEK Logistica"
    }
    fun seleccionarModuloSupervisor() {
        if (!puedeVerSupervisor()) return
        supervisorMode = true
        gerenteMode = false
        logisticaMode = false
        moduleChoicePending = false
        showSyncReminder = false
        message = "Panel supervisor"
    }
    fun seleccionarModuloGerente() {
        if (!puedeVerGerencia()) return
        gerenteMode = true
        supervisorMode = false
        logisticaMode = false
        moduleChoicePending = false
        showSyncReminder = false
        message = "Panel gerente"
    }
    fun volverSelectorSupervisor() {
        moduleChoicePending = true
        supervisorMode = false
        gerenteMode = false
        logisticaMode = false
    }
    fun salirLogin() {
        logged = false
        moduleChoicePending = false
        supervisorMode = false
        gerenteMode = false
        logisticaMode = false
        currentUser = null
        supervisorVendedorDetalle = null
        gerenteDashboard = null
        logisticaDashboard = null
        showSyncReminder = false
        updateCheckDone = false
        appUpdateInfo = null
        showAppUpdateDialog = false
        latestAppVersion = null
        ServiceLocator.session.clearLogin()
        message = "Sesion cerrada"
    }
    fun cargarSupervisorOnline() = viewModelScope.launch {
        isSyncing = true
        syncMessage = "Cargando panel supervisor online..."
        runCatching { withTimeout(20000) { nv.supervisorDashboard() } }
            .onSuccess {
                supervisorDashboard = it
                syncMessage = "Panel supervisor actualizado"
                message = "Panel supervisor actualizado"
            }
            .onFailure {
                supervisorDashboard = supervisorDashboard ?: SupervisorDashboardDto()
                syncMessage = "Panel supervisor no respondio a tiempo. Intente actualizar."
                message = "Panel supervisor no respondio a tiempo. Se muestra pantalla base."
            }
        isSyncing = false
    }
    fun cargarSupervisorVendedor(codigo: String) = viewModelScope.launch {
        if (codigo.isBlank()) return@launch
        isSyncing = true
        syncMessage = "Cargando resumen del vendedor..."
        runCatching { nv.supervisorVendedorResumen(codigo) }
            .onSuccess {
                supervisorVendedorDetalle = it
                syncMessage = "Resumen vendedor actualizado"
            }
            .onFailure {
                message = "Error cargando vendedor: ${(it as? Exception)?.let { e -> apiErrorMessage(e) } ?: it.message}"
                syncMessage = message
            }
        isSyncing = false
    }
    fun cerrarSupervisorVendedor() {
        supervisorVendedorDetalle = null
    }
    fun cargarGerenteOnline() = viewModelScope.launch {
        isSyncing = true
        syncMessage = "Cargando panel gerente online..."
        runCatching { withTimeout(20000) { nv.gerenteDashboard() } }
            .onSuccess {
                gerenteDashboard = it
                syncMessage = "Panel gerente actualizado"
                message = "Panel gerente actualizado"
            }
            .onFailure {
                gerenteDashboard = gerenteDashboard ?: GerenteDashboardDto()
                syncMessage = "Panel gerente no respondio a tiempo. Intente actualizar."
                message = "Panel gerente no respondio a tiempo. Se muestra pantalla base."
        }
        isSyncing = false
    }
    fun cargarLogisticaOnline(fecha: String? = null) = viewModelScope.launch {
        val fechaConsulta = fecha?.takeIf { it.isNotBlank() } ?: logisticaFecha
        logisticaFecha = fechaConsulta
        isSyncing = true
        syncMessage = "Cargando RISEK Logistica ${fechaCl(fechaConsulta)}..."
        runCatching { withTimeout(20000) { nv.logisticaDashboard(fechaConsulta) } }
            .onSuccess {
                logisticaDashboard = it
                syncMessage = "Logistica actualizada"
                message = "Logistica actualizada"
            }
            .onFailure {
                logisticaDashboard = logisticaDashboard ?: LogisticaDashboardDto()
                syncMessage = "Logistica no respondio a tiempo. Intente actualizar."
                message = "Logistica no respondio a tiempo. Se muestra pantalla base."
            }
        isSyncing = false
    }
    fun cambiarEstadoLogistica(doc: LogisticaDocumentoDto, estado: String, observacion: String? = null) = viewModelScope.launch {
        isSyncing = true
        syncMessage = "Actualizando entrega..."
        runCatching {
            withTimeout(15000) {
                nv.logisticaEstado(
                    LogisticaEstadoRequest(
                        ventaNumero = doc.ventaNumero,
                        ventaTipo = doc.ventaTipo,
                        localCodigo = doc.localCodigo,
                        estado = estado,
                        observacion = observacion?.take(200)
                    )
                )
            }
        }.onSuccess {
            message = it.message ?: "Estado actualizado"
            isSyncing = false
            cargarLogisticaOnline(logisticaFecha)
        }.onFailure {
            message = "No se pudo actualizar reparto: ${it.message}"
            syncMessage = message
            isSyncing = false
        }
    }
    fun enviarResumenVentasDia(email: String, mes: Int, ano: Int) = viewModelScope.launch {
        val clean = email.trim()
        if (!clean.contains("@") || !clean.contains(".")) {
            message = "Ingrese un correo valido"
            return@launch
        }
        if (mes !in 1..12 || ano !in 2018..LocalDate.now().year) {
            message = "Seleccione un mes y ano validos"
            return@launch
        }
        isSyncing = true
        syncMessage = "Generando y enviando reporte comercial PDF..."
        runCatching { withTimeout(60000) { nv.enviarResumenVentasDia(clean, mes, ano) } }
            .onSuccess {
                message = it.message ?: "Reporte enviado"
                syncMessage = message
            }
            .onFailure {
                message = "No se pudo enviar reporte: ${it.message}"
                syncMessage = message
            }
        isSyncing = false
    }

    private fun loadLocalCounts() = viewModelScope.launch {
        val c = catalog.counts(); val n = nv.counts(); val u = runCatching { auth.countUsers() }.getOrDefault(0)
        counts = LocalCounts(
            usuarios = u, clientes = c.clientes, direcciones = c.direcciones, productos = c.productos, familias = c.familias, precios = c.precios, rutas = c.rutas, cartola = c.cartola,
            nvPendientes = n.nvPendientes, nvError = n.nvError, nvSincronizadas = n.nvSincronizadas
        )
    }

    fun testConnection() = viewModelScope.launch {
        isSyncing = true
        runCatching { auth.testConnection() }
            .onSuccess { h -> apiOk = h.ok; syncMessage = if (h.ok) "API conectada: ${h.service ?: "risek-api"}" else "API respondió sin OK" }
            .onFailure { apiOk = false; syncMessage = "No se pudo conectar a la API: ${it.message}" }
        isSyncing = false
    }

    fun refreshUsers() = viewModelScope.launch {
        isSyncing = true
        runCatching { auth.refreshUsers() }
            .onSuccess { total -> message=""; syncMessage="Usuarios secuser descargados: $total"; loadLocalCounts() }
            .onFailure{ message=it.message ?: "Error usuarios"; syncMessage="Error descargando usuarios: ${it.message}" }
        isSyncing = false
    }

    fun descargarCuentaCorrienteCompleta() = viewModelScope.launch {
        isSyncing = true
        syncMessage = "Descargando cuenta corriente completa..."
        runCatching { catalog.syncCuentaCorrienteCompleta() }
            .onSuccess {
                apiOk = true
                syncMessage = "Cuenta corriente completa actualizada: $it documentos"
                loadLocalCounts()
            }
            .onFailure {
                apiOk = false
                syncMessage = "Error descargando cuenta corriente completa: ${it.message}"
            }
        isSyncing = false
    }

    fun syncUsersOnStart() = viewModelScope.launch {
        if (userSyncStarted) return@launch
        userSyncStarted = true
        message = ""
        runCatching { auth.refreshUsers() }
            .onSuccess { loadLocalCounts() }
            .onFailure { message = "No se pudieron sincronizar usuarios: ${it.message}. Revise API/red."; loadLocalCounts() }
    }

    fun cerrarLoginErrorPopup() {
        loginErrorPopup = null
    }

    fun loginSelected(user: SecUserEntity?, pass: String) = viewModelScope.launch {
        if (user == null) { message = "Seleccione un usuario"; return@launch }
        if (pass.isBlank()) { message = "Digite la clave"; return@launch }
        isSyncing = true
        message = "Validando usuario..."
        runCatching { auth.login(user, pass) }
            .onSuccess { r ->
                isSyncing = false
                if (r.ok) {
                    val loggedUser = user.copy(vendedorCodigo = r.vendedorCodigo ?: user.vendedorCodigo)
                    enterAfterLogin(loggedUser)
                    message = if (loggedUser.vendedorCodigo.isNullOrBlank()) "Sesion iniciada - Solo Repartos" else "Sesion iniciada - Vendedor ${loggedUser.vendedorCodigo}"
                }
                else {
                    message = r.message ?: "Usuario o clave incorrectos"
                    loginErrorPopup = "Contraseña incorrecta. Revise la clave ingresada e intente nuevamente."
                }
            }
            .onFailure {
                isSyncing = false
                message = "No se pudo validar usuario/clave contra API: ${it.message}"
            }
    }

    fun loginTyped(username: String, pass: String) = viewModelScope.launch {
        val userText = username.trim()
        if (userText.isBlank()) { message = "Digite el usuario"; return@launch }
        if (pass.isBlank()) { message = "Digite la clave"; return@launch }

        isSyncing = true
        message = "Validando usuario..."
        var localUsers = runCatching { auth.localUsers() }.getOrDefault(emptyList())

        // Regla de arranque: los usuarios de secuser deben existir antes de validar login.
        // Si no están en Room, se descargan automáticamente una vez desde la API.
        if (localUsers.isEmpty()) {
            runCatching { auth.refreshUsers() }
                .onSuccess { loadLocalCounts() }
                .onFailure {
                    isSyncing = false
                    message = "No se pudieron cargar usuarios secuser. Revise API/red: ${it.message}"
                    return@launch
                }
            localUsers = runCatching { auth.localUsers() }.getOrDefault(emptyList())
        }

        if (localUsers.isEmpty()) {
            isSyncing = false
            message = "No hay usuarios disponibles en secuser para iniciar sesión."
            return@launch
        }

        val found = localUsers.firstOrNull {
            it.secUserName.equals(userText, true) ||
            it.vendedorCodigo?.equals(userText, true) == true ||
            it.secUserId.toString() == userText
        }
        if (found == null) {
            isSyncing = false
            message = "Usuario no existe en secuser: $userText"
            return@launch
        }

        runCatching { auth.login(found, pass) }
            .onSuccess { r ->
                isSyncing = false
                if (r.ok) {
                    val loggedUser = found.copy(vendedorCodigo = r.vendedorCodigo ?: found.vendedorCodigo)
                    enterAfterLogin(loggedUser)
                    message = if (loggedUser.vendedorCodigo.isNullOrBlank()) "Sesion iniciada - Solo Repartos" else r.message ?: "Sesion iniciada"
                }
                else {
                    message = r.message ?: "Usuario o clave incorrectos"
                    loginErrorPopup = "Contraseña incorrecta. Revise la clave ingresada e intente nuevamente."
                }
            }
            .onFailure {
                isSyncing = false
                message = "No se pudo validar usuario/clave contra API: ${it.message}"
            }
    }

    fun fullSync(context: Context) = viewModelScope.launch {
        isSyncing = true; syncMessage = "Descargando usuarios y catálogo..."
        runCatching {
            val u = runCatching { auth.refreshUsers() }.getOrElse { 0 }
            val b = catalog.bootstrap()
            SyncWorker.enqueue(context)
            Pair(u,b)
        }.onSuccess { (u,b) ->
            lastSyncAt = nowText()
            val warningText = b.warnings.takeIf { it.isNotEmpty() }?.joinToString("\n") { "- $it" }.orEmpty()
            val resumen = "Usuarios $u\nClientes ${b.clientes}\nDirecciones ${b.direcciones}\nProductos ${b.productos}\nFamilias ${b.familias}\nPrecios ${b.precios}\nRutas ${b.rutas}\nCuenta corriente ${b.cartola}"
            val faltantes = buildList {
                if (b.clientes <= 0) add("clientes")
                if (b.direcciones <= 0) add("direcciones")
                if (b.productos <= 0) add("productos")
                if (b.precios <= 0) add("precios")
            }
            if (faltantes.isEmpty() && b.warnings.isEmpty()) {
                ServiceLocator.session.saveSuccessfulSync(LocalDate.now().toString(), lastSyncAt.orEmpty())
                showSyncReminder = false
                apiOk = true
                syncMessage = "Descarga OK: usuarios $u, clientes ${b.clientes}, direcciones ${b.direcciones}, productos ${b.productos}, familias ${b.familias}, precios ${b.precios}, rutas ${b.rutas}, cartola ${b.cartola}"
                showSyncDownloadDialog("Datos descargados", "La descarga finalizo correctamente.\n\n$resumen")
            } else if (faltantes.isEmpty()) {
                ServiceLocator.session.saveSuccessfulSync(LocalDate.now().toString(), lastSyncAt.orEmpty())
                showSyncReminder = false
                apiOk = true
                syncMessage = "Ventas operativas. Algunas tablas no se actualizaron."
                showSyncDownloadDialog("Ventas operativas", "Clientes, direcciones, productos y precios estan disponibles para vender.\n\nTablas no actualizadas:\n$warningText\n\n$resumen", true)
            } else {
                apiOk = false
                syncMessage = "Faltan datos criticos: ${faltantes.joinToString(", ")}. Reintente sincronizar."
                showSyncDownloadDialog("Faltan datos para vender", "No hay datos criticos suficientes en el telefono: ${faltantes.joinToString(", ")}.\n\n${warningText.takeIf { it.isNotBlank() } ?: "Reintente sincronizar con buena conexion."}\n\n$resumen", true)
            }
            loadLocalCounts()
        }.onFailure {
            apiOk=false
            syncMessage="Error en descarga: ${it.message}"
            showSyncDownloadDialog("Error en descarga", "No se pudieron actualizar datos.\n\nDetalle: ${it.message}\n\nSi el telefono ya tiene clientes, productos y precios, puede seguir vendiendo con ultima descarga buena.", true)
            loadLocalCounts()
        }
        isSyncing = false
    }

    fun syncClientesDesbloqueados() = viewModelScope.launch {
        isSyncing = true
        syncMessage = "Actualizando clientes desbloqueados..."
        runCatching { catalog.syncClientesDesbloqueados() }
            .onSuccess { (clientes, direcciones) ->
                apiOk = true
                syncMessage = "Clientes desbloqueados actualizados: $clientes clientes, $direcciones direcciones"
                loadLocalCounts()
            }
            .onFailure {
                apiOk = false
                syncMessage = "Error actualizando clientes desbloqueados: ${it.message}"
                loadLocalCounts()
            }
        isSyncing = false
    }

    fun syncClienteBloqueado(c: ClienteEntity) = viewModelScope.launch {
        isSyncing = true
        message = "Actualizando cliente ${c.clienteRut}..."
        syncMessage = "Actualizando cliente ${c.clienteRut}..."
        runCatching { catalog.syncClientePorRut(c.clienteRut) }
            .onSuccess { actualizado ->
                apiOk = true
                if (actualizado == null) {
                    message = "Cliente ${c.clienteRut} no encontrado en servidor."
                    syncMessage = message
                } else {
                    val seleccionado = selectedCliente?.clienteRut == c.clienteRut
                    clientes = clientes.map { if (it.clienteRut == actualizado.clienteRut) actualizado else it }
                    if (seleccionado || c.clienteEstado == "B") {
                        selectedCliente = actualizado
                        direcciones = catalog.direcciones(actualizado.clienteRut)
                        selectedDireccion = null
                    }
                    val habilitado = actualizado.clienteEstado != "B"
                    message = if (habilitado) "Cliente ${actualizado.clienteRut} actualizado y habilitado para vender." else "Cliente ${actualizado.clienteRut} sigue bloqueado."
                    syncMessage = message
                }
                loadLocalCounts()
            }
            .onFailure {
                apiOk = false
                message = "Error actualizando cliente: ${it.message}"
                syncMessage = message
            }
        isSyncing = false
    }

    fun actualizarApp(context: Context) = viewModelScope.launch {
        isSyncing = true
        syncMessage = "Buscando actualizacion de la app..."
        runCatching {
            val info = ServiceLocator.api.appVersion()
            if (!info.available || info.versionCode <= BuildConfig.VERSION_CODE || info.apkUrl.isNullOrBlank()) {
                "App al dia. Version instalada ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    "Active el permiso para instalar actualizaciones de RISEK y vuelva a presionar Actualizar App."
                } else {
                    syncMessage = "Descargando actualizacion ${info.versionName ?: info.versionCode.toString()}..."
                    val apkFile = withContext(Dispatchers.IO) {
                        val apkUrl = if (info.apkUrl.startsWith("http", ignoreCase = true)) {
                            info.apkUrl
                        } else {
                            Config.BASE_URL.trimEnd('/') + "/" + info.apkUrl.trimStart('/')
                        }
                        val updateDir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
                        val file = File(updateDir, "apprisek-${info.versionCode}.apk")
                        val connection = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 20_000
                            readTimeout = 90_000
                            requestMethod = "GET"
                            doInput = true
                        }
                        try {
                            val code = connection.responseCode
                            if (code !in 200..299) throw RuntimeException("HTTP $code descargando APK")
                            connection.inputStream.use { input ->
                                file.outputStream().use { output -> input.copyTo(output) }
                            }
                        } catch (e: Exception) {
                            file.delete()
                            throw e
                        } finally {
                            connection.disconnect()
                        }
                        if (!file.exists() || file.length() <= 0L) throw RuntimeException("APK descargado vacio")
                        file
                    }
                    val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.provider", apkFile)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    "Actualizacion ${info.versionName ?: info.versionCode.toString()} descargada. Confirme la instalacion en Android."
                }
            }
        }.onSuccess {
            apiOk = true
            syncMessage = it
        }.onFailure {
            apiOk = false
            syncMessage = "Error actualizando app: ${apiErrorMessage(it as? Exception ?: Exception(it))}"
        }
        isSyncing = false
    }

    fun checkAppUpdateOnEntry() = viewModelScope.launch {
        if (updateCheckDone) return@launch
        updateCheckDone = true
        runCatching { ServiceLocator.api.appVersion() }
            .onSuccess { info ->
                latestAppVersion = info
                val hasUpdate = info.available && info.versionCode > BuildConfig.VERSION_CODE && !info.apkUrl.isNullOrBlank()
                if (hasUpdate && dismissedUpdateCode != info.versionCode) {
                    appUpdateInfo = info
                    showAppUpdateDialog = true
                }
            }
    }

    fun dismissAppUpdate() {
        dismissedUpdateCode = appUpdateInfo?.versionCode
        showAppUpdateDialog = false
    }

    fun acceptAppUpdate(context: Context) {
        actualizarApp(context)
    }
    private suspend fun renderPdfPreview(file: File): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    if (renderer.pageCount <= 0) return@withContext null
                    renderer.openPage(0).use { page ->
                        val scale = 2
                        val bitmap = Bitmap.createBitmap(page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        }.getOrNull()
    }
    private suspend fun showPdfPreview(file: File, title: String) {
        pdfPreviewFile = file
        pdfPreviewTitle = title
        pdfPreviewBitmap = renderPdfPreview(file)
        message = "Vista previa PDF generada"
    }
    fun cerrarPdfPreview() {
        pdfPreviewTitle = null
        pdfPreviewFile = null
        pdfPreviewBitmap = null
    }
    fun abrirPdfPreviewExterno(context: Context) {
        val file = pdfPreviewFile ?: return
        abrirPdfFileExterno(context, file)
    }
    private fun abrirPdfFileExterno(context: Context, file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Abrir PDF"))
        }.onFailure { message = "No se pudo abrir PDF: ${it.message}" }
    }
    private suspend fun descargarPdfDesdeUrl(context: Context, url: String, fileName: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.getExternalFilesDir(null), "pdf").apply { mkdirs() }
        val safeName = fileName.replace(Regex("""[^A-Za-z0-9_.-]"""), "_")
        val file = File(dir, safeName)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 30000
            requestMethod = "GET"
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code ${conn.responseMessage}")
            conn.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            file
        } finally {
            conn.disconnect()
        }
    }

    fun sync(context: Context) = viewModelScope.launch {
        isSyncing = true
        runCatching { val sent = nv.syncPendingOnce(); SyncWorker.enqueue(context); sent }
            .onSuccess { syncMessage="NV enviadas al servidor: $it. Si alguna falla, el error queda visible en Pedidos y en Cola NV."; loadLocalCounts() }
            .onFailure { syncMessage="Error sincronizando NV: ${it.message}"; loadLocalCounts() }
        isSyncing=false
    }

    fun searchClientes(q:String) = viewModelScope.launch { if(q.length>=2) clientes = catalog.searchClientes(q) }
    fun selectCliente(c: ClienteEntity) = viewModelScope.launch {
        selectedCliente = c; selectedDireccion = null; cart = emptyList(); clientes = emptyList(); ultimasNvCliente = emptyList(); cuentaCorriente = null
        direcciones = catalog.direcciones(c.clienteRut)
        // Mostrar todas las direcciones; el usuario debe elegir explícitamente.
        // if (direcciones.size == 1) selectedDireccion = direcciones.first().direccion
        message = if(c.clienteEstado == "B") "Cliente bloqueado: no se puede vender" else "Cliente seleccionado. Ahora seleccione dirección de reparto."
    }
    fun selectDireccion(d: String) { selectedDireccion = d; message = "Dirección seleccionada" }
    fun cambiarFechaReparto(value: String) { fechaReparto = value; ServiceLocator.session.saveLastFechaReparto(value) }
    fun cambiarObservacion(value: String) { observacionNv = value.take(200) }
    fun cargarUltimasNvCliente() = viewModelScope.launch {
        val c = selectedCliente ?: run { message = "Seleccione cliente"; return@launch }
        runCatching { nv.ultimasNvCliente(c.clienteRut) }
            .onSuccess { ultimasNvCliente = it; message = "Últimas NV cargadas: ${it.size}" }
            .onFailure { message = "No se pudieron cargar últimas NV: ${it.message}" }
    }
    fun cargarCuentaCorriente() = viewModelScope.launch {
        val c = selectedCliente ?: run { message = "Seleccione cliente"; return@launch }
        runCatching { nv.cuentaCorriente(c.clienteRut) }
            .onSuccess { cuentaCorriente = it; message = "Cuenta corriente actualizada" }
            .onFailure { message = "No se pudo cargar cuenta corriente: ${it.message}" }
    }
    fun cargarCuentaCorrienteOnline(rut: String) = viewModelScope.launch {
        if (rut.isBlank()) { message = "Seleccione cliente"; return@launch }
        isSyncing = true
        syncMessage = "Consultando cuenta corriente online..."
        runCatching { withTimeout(20000) { catalog.cuentaCorrienteOnline(rut) } }
            .onSuccess {
                cuentaCorrienteOnlineRows = it
                cuentaCorrienteRutOnline = rut
                message = "Cuenta corriente online: ${it.size} documentos"
                syncMessage = message
            }
            .onFailure {
                cuentaCorrienteOnlineRows = emptyList()
                message = "Error cuenta corriente online: ${(it as? Exception)?.let { e -> apiErrorMessage(e) } ?: it.message}"
                syncMessage = message
            }
        isSyncing = false
    }
    fun cargarCuentaCorrienteOffline(rut: String) = viewModelScope.launch {
        if (rut.isBlank()) { message = "Seleccione cliente"; return@launch }
        runCatching { catalog.cuentaCorrienteOffline(rut) }
            .onSuccess {
                cuentaCorrienteOnlineRows = it
                cuentaCorrienteRutOnline = rut
                message = "Cuenta corriente offline: ${it.size} documentos con folio"
            }
            .onFailure { message = "Error cuenta corriente offline: ${it.message}" }
    }
    fun alternarModoCuentaCorriente() {
        cuentaCorrienteModoOnline = !cuentaCorrienteModoOnline
        cuentaCorrienteOnlineRows = emptyList()
        cuentaCorrienteRutOnline = null
    }
    fun limpiarCuentaCorrienteOnline() {
        cuentaCorrienteOnlineRows = emptyList()
        cuentaCorrienteRutOnline = null
    }
    fun searchProductos(q: String, exactCode: Boolean, familiaCodigo: String? = selectedFamilia?.familiaCodigo) = viewModelScope.launch {
        val seq = ++productSearchSeq
        val query = q.trim()
        productoSearchQuery = query
        val found = when {
            !familiaCodigo.isNullOrBlank() && query.isNotBlank() -> catalog.searchProductos(query, exactCode, familiaCodigo)
            !familiaCodigo.isNullOrBlank() -> catalog.productosPorFamilia(familiaCodigo, productoPageSize, 0)
            (exactCode && query.isNotBlank()) || (!exactCode && query.length >= 2) -> catalog.searchProductos(query, exactCode)
            else -> emptyList()
        }
        if (seq != productSearchSeq) return@launch
        productos = found
        productoFamiliaTotal = if (familiaCodigo.isNullOrBlank()) 0 else catalog.countProductosPorFamilia(familiaCodigo, query)
        productListPrices = listPricesFor(found)
    }
    fun selectFamilia(f: FamiliaEntity?) = viewModelScope.launch {
        selectedFamilia = f
        productoPage = 0
        val query = productoSearchQuery
        productoFamiliaTotal = if (f == null) 0 else catalog.countProductosPorFamilia(f.familiaCodigo, query)
        productos = if (f == null) emptyList() else catalog.productosPorFamilia(f.familiaCodigo, productoPageSize, 0, query)
        productListPrices = listPricesFor(productos)
        message = if (f == null) "Seleccione una familia" else "Familia seleccionada: ${f.familiaDescripcion ?: f.familiaCodigo}. Mostrando ${productos.size} de $productoFamiliaTotal productos."
    }
    fun nextProductoPage() = viewModelScope.launch {
        val f = selectedFamilia ?: return@launch
        val next = productoPage + 1
        val query = productoSearchQuery
        val pageItems = catalog.productosPorFamilia(f.familiaCodigo, productoPageSize, next * productoPageSize, query)
        if (pageItems.isNotEmpty()) {
            productoPage = next
            productos = pageItems
            productListPrices = listPricesFor(productos)
        } else message = "No hay mas productos en esta familia"
    }
    fun prevProductoPage() = viewModelScope.launch {
        val f = selectedFamilia ?: return@launch
        val prev = (productoPage - 1).coerceAtLeast(0)
        val query = productoSearchQuery
        productoPage = prev
        productos = catalog.productosPorFamilia(f.familiaCodigo, productoPageSize, prev * productoPageSize, query)
        productListPrices = listPricesFor(productos)
    }
    private suspend fun listPricesFor(items: List<ProductoEntity>): Map<String, Long> =
        items.associate { p -> p.productoCodigo to (catalog.precio(p.productoCodigo, NV_PRICE_LIST)?.listaVenta ?: 0L) }
    private suspend fun addProductInternal(p: ProductoEntity, uxeText: String, descuentoText: String = "0", cajasText: String = "0"): Boolean {
        val c = selectedCliente ?: run { message="Seleccione cliente antes de agregar productos"; return false }
        if (c.clienteEstado == "B") { message="Cliente bloqueado: venta no permitida"; return false }
        if (selectedDireccion.isNullOrBlank()) { message="Seleccione dirección de reparto antes de agregar productos"; return false }
        val alreadyInCart = cart.any { it.productoCodigo == p.productoCodigo }
        if (!alreadyInCart && cart.size >= MAX_NV_LINES) {
            message="Máximo $MAX_NV_LINES líneas por NV. Para agregar más productos debe crear otra NV."
            return false
        }
        val uxe = uxeText.replace(',','.').toDoubleOrNull() ?: 0.0
        val cajas = cajasText.replace(',','.').toDoubleOrNull() ?: 0.0
        val descuento = descuentoText.replace(',','.').toDoubleOrNull() ?: 0.0
        val lista = NV_PRICE_LIST
        val precioLista = catalog.precio(p.productoCodigo, lista)
        if (precioLista == null || precioLista.listaVenta <= 0L || precioLista.listaNeto <= 0L) {
            message = "Producto ${p.productoCodigo} sin precio para lista $lista. Sincronice precios o revise la lista del cliente."
            return false
        }
        return runCatching { buildLine(p, precioLista.listaNeto, precioLista.listaVenta, precioLista.listaIla, uxe, descuento, cajas) }
            .onSuccess { line -> cart = (cart.filterNot { it.productoCodigo == line.productoCodigo } + line); message="Producto agregado: ${p.productoDescripcion ?: p.productoCodigo} · UXE ${stockText(line.uxe)} · Cant ${stockText(line.cantidad)}" }
            .onFailure { message = it.message ?: "No se pudo agregar producto" }
            .isSuccess
    }
    fun addProduct(p: ProductoEntity, uxeText: String, descuentoText: String = "0", cajasText: String = "0") = viewModelScope.launch {
        addProductInternal(p, uxeText, descuentoText, cajasText)
    }
    fun processVoiceProduct(command: VoiceProductCommand, descuentoText: String = "0", onAutoHandled: () -> Unit = {}, onShowResults: () -> Unit = {}) = viewModelScope.launch {
        if (command.action == VoiceProductAction.DELETE) {
            removeProductByVoice(command.query, command.exactCode)
            productos = emptyList()
            onAutoHandled()
            return@launch
        }
        selectedFamilia = null
        productoPage = 0
        val query = command.query.trim()
        productos = if (command.byFamily && query.length >= 2) {
            val match = familias.value.firstOrNull {
                normalizeSearchText(it.familiaDescripcion ?: "").contains(normalizeSearchText(query)) ||
                    normalizeSearchText(it.familiaCodigo).contains(normalizeSearchText(query))
            }
            if (match == null) {
                emptyList()
            } else {
                selectedFamilia = match
                catalog.productosPorFamilia(match.familiaCodigo, 50, 0)
            }
        } else if((command.exactCode && query.isNotBlank()) || (!command.exactCode && query.length>=2)) catalog.searchProductos(query, command.exactCode) else emptyList()
        productListPrices = listPricesFor(productos)
        if (command.exactCode && productos.size == 1) {
            val added = addProductInternal(productos.first(), command.uxe ?: "1", descuentoText, command.cajas ?: "0")
            if (added) {
                productos = emptyList()
                onAutoHandled()
            } else {
                onShowResults()
            }
        } else {
            onShowResults()
        }
    }
    fun removeProduct(code: String) {
        cart = cart.filterNot { it.productoCodigo == code }
    }
    fun removeProductByVoice(query: String, exactCode: Boolean) {
        val clean = query.trim().lowercase(Locale("es", "CL"))
        val line = if (exactCode) {
            cart.firstOrNull { it.productoCodigo.lowercase(Locale("es", "CL")) == clean }
        } else {
            cart.firstOrNull {
                it.productoCodigo.lowercase(Locale("es", "CL")) == clean ||
                    (it.descripcion ?: "").lowercase(Locale("es", "CL")).contains(clean)
            }
        }
        if (line == null) {
            message = "No encontre el producto $query en la NV"
        } else {
            cart = cart.filterNot { it.productoCodigo == line.productoCodigo }
            message = "Producto eliminado: ${line.descripcion ?: line.productoCodigo}"
        }
    }
    fun createNv(context: Context, onSuccessNav: () -> Unit = {}) = viewModelScope.launch {
        val c = selectedCliente ?: run { message="Seleccione cliente"; return@launch }
        runCatching { nv.createOrUpdateLocal(c, selectedDireccion, cart, fechaReparto, observacionNv, editOfflineId) }
            .onSuccess { id -> message="NV guardada localmente: ${id.take(28)}. Enviando al servidor..."; cart=emptyList(); clientes=emptyList(); productos=emptyList(); selectedFamilia=null; productoSearchQuery=""; productoPage=0; productoFamiliaTotal=0; direcciones=emptyList(); selectedCliente=null; selectedDireccion=null; editOfflineId=null; observacionNv=""; ultimasNvCliente=emptyList(); cuentaCorriente=null; sync(context); loadLocalCounts(); onSuccessNav() }
            .onFailure { message=it.message ?: "Error creando NV" }
    }
    fun editNv(h: NvHeaderEntity) = viewModelScope.launch {
        if (h.facturado == "S") { message="NV facturada: no se puede modificar"; return@launch }
        val cliente = catalog.clientePorRut(h.clienteRut)
            ?: ClienteEntity(h.clienteRut, h.clienteNombre, h.direccion, null, null, null, null, null, null, h.vendedorCodigo)
        selectedCliente = cliente
        direcciones = catalog.direcciones(h.clienteRut)
        selectedDireccion = h.direccion?.takeIf { it.isNotBlank() }
            ?: direcciones.firstOrNull()?.direccion
            ?: cliente.clienteDireccion
        fechaReparto = h.fechaReparto; observacionNv = h.observacion ?: ""; editOfflineId = h.offlineId
        cart = nv.lines(h.offlineId)
        message = if (h.syncStatus == "SINCRONIZADO") "Editando NV sincronizada. Lista ${cliente.listaCodigo ?: "-"}; al guardar se actualizará en servidor." else "Editando NV local ${h.offlineId.take(18)}"
    }

    fun abrirDetallePedido(h: NvHeaderEntity) = viewModelScope.launch {
        pedidoDetalleHeader = h
        pedidoDetalleLines = nv.lines(h.offlineId)
        message = "Detalle NV cargado"
    }

    fun cerrarDetallePedido() {
        pedidoDetalleHeader = null
        pedidoDetalleLines = emptyList()
    }

    fun abrirResumenPedido(h: NvHeaderEntity) = viewModelScope.launch {
        resumenHeader = h
        resumenLines = nv.lines(h.offlineId)
        message = "Resumen NV cargado"
    }

    fun cerrarResumenPedido() {
        resumenHeader = null
        resumenLines = emptyList()
    }

    fun eliminarNv(h: NvHeaderEntity, context: Context) = viewModelScope.launch {
        runCatching { nv.requestDeleteNv(h) }
            .onSuccess { eliminadaAhora ->
                if (eliminadaAhora) {
                    message = if (h.syncStatus == "SINCRONIZADO") "NV eliminada en servidor y app" else "NV eliminada localmente"
                } else {
                    message = "Sin conexion: eliminacion pendiente para Sync"
                    SyncWorker.enqueue(context)
                }
                cerrarResumenPedido(); cerrarDetallePedido(); loadLocalCounts()
            }
            .onFailure { message = it.message ?: "No se pudo eliminar la NV" }
    }


    fun nuevaNvDesdeCero() {
        selectedCliente = null
        direcciones = emptyList()
        selectedDireccion = null
        cart = emptyList()
        clientes = emptyList()
        productos = emptyList()
        selectedFamilia = null
        productoSearchQuery = ""
        productoPage = 0
        productoFamiliaTotal = 0
        editOfflineId = null
        observacionNv = ""
        ultimasNvCliente = emptyList()
        cuentaCorriente = null
        message = "Nueva NV desde cero. Seleccione fecha de reparto, cliente, dirección y productos."
    }

    fun abrirPdf(context: Context, h: NvHeaderEntity) = viewModelScope.launch {
        runCatching {
            val lines = nv.lines(h.offlineId)
            val file = PdfGenerator.createNvPdf(context, h, lines)
            showPdfPreview(file, "Nota de venta")
        }.onFailure { message = "No se pudo abrir PDF: ${it.message}" }
    }
    fun abrirCuentaCorrientePdf(context: Context, cliente: CuentaCorrienteEntity, rows: List<CuentaCorrienteEntity>) = viewModelScope.launch {
        runCatching {
            val file = PdfGenerator.createCuentaCorrientePdf(context, cliente, rows)
            showPdfPreview(file, "Cuenta corriente")
        }
            .onSuccess { message = "PDF cuenta corriente generado" }
            .onFailure { message = "No se pudo abrir PDF cuenta corriente: ${it.message}" }
    }
    fun abrirDocumentosPendientesPdf(context: Context, cliente: CuentaCorrienteEntity, rows: List<CuentaCorrienteEntity>) = viewModelScope.launch {
        val pendientes = documentosPendientes(rows)
        if (pendientes.isEmpty()) {
            message = "El cliente no tiene documentos pendientes"
            return@launch
        }
        runCatching {
            val file = PdfGenerator.createCuentaCorrientePdf(
                context = context,
                cliente = cliente,
                rows = pendientes,
                reportTitle = "DOCUMENTOS PENDIENTES",
                pendingOnly = true,
                filePrefix = "Documentos_Pendientes"
            )
            showPdfPreview(file, "Documentos pendientes")
        }
            .onSuccess { message = "PDF de documentos pendientes generado" }
            .onFailure { message = "No se pudo abrir PDF de pendientes: ${it.message}" }
    }
    fun compartirCuentaCorrienteWhatsApp(context: Context, cliente: CuentaCorrienteEntity, rows: List<CuentaCorrienteEntity>) = viewModelScope.launch {
        runCatching {
            val file = PdfGenerator.createCuentaCorrientePdf(context, cliente, rows)
            val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", file)
            val saldo = cuentaSaldoTotal(rows)
            val vendedor = currentUser?.secUserName?.takeIf { it.isNotBlank() }
                ?: currentUser?.vendedorCodigo?.takeIf { it.isNotBlank() }
                ?: "su ejecutivo comercial"
            val clienteNombre = cliente.clienteNombre?.takeIf { it.isNotBlank() } ?: "Cliente"
            val texto = "Estimado/a $clienteNombre,\n\n" +
                "Junto con saludar, enviamos adjunta la cartola actualizada de su cuenta corriente en RISEK.\n\n" +
                "Saldo informado: ${clp(saldo)}.\n\n" +
                "Por favor revise el documento adjunto. Ante cualquier consulta o diferencia, puede responder este mensaje para coordinar la revision correspondiente.\n\n" +
                "Atentamente,\n$vendedor\nRISEK - Fuerza de Ventas"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, texto)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setPackage("com.whatsapp")
            }
            runCatching { context.startActivity(intent) }.getOrElse {
                val fallback = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, texto)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(fallback, "Enviar cartola"))
            }
            file
        }
            .onSuccess { message = "Cartola lista para enviar por WhatsApp" }
            .onFailure { message = "No se pudo enviar cartola por WhatsApp: ${it.message}" }
    }
    fun compartirFacturasPendientesWhatsApp(context: Context, cliente: CuentaCorrienteEntity, rows: List<CuentaCorrienteEntity>) = viewModelScope.launch {
        val pendientes = facturasPendientes(rows)
        if (pendientes.isEmpty()) {
            message = "El cliente no tiene facturas pendientes para enviar"
            return@launch
        }
        runCatching {
            val file = PdfGenerator.createCuentaCorrientePdf(
                context = context,
                cliente = cliente,
                rows = pendientes,
                reportTitle = "FACTURAS PENDIENTES",
                pendingOnly = true,
                filePrefix = "Facturas_Pendientes"
            )
            val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", file)
            val saldo = pendientes.sumOf { cuentaSaldoDocumento(it) }
            val vendedor = currentUser?.secUserName?.takeIf { it.isNotBlank() }
                ?: currentUser?.vendedorCodigo?.takeIf { it.isNotBlank() }
                ?: "su ejecutivo comercial"
            val clienteNombre = cliente.clienteNombre?.takeIf { it.isNotBlank() } ?: "Cliente"
            val texto = "Estimado/a $clienteNombre,\n\n" +
                "Junto con saludar, enviamos adjunto el detalle actualizado de sus facturas pendientes en RISEK.\n\n" +
                "Saldo pendiente informado: ${clp(saldo)}.\n\n" +
                "Agradecemos revisar el documento. Ante cualquier consulta o diferencia, puede responder este mensaje para coordinar su revision.\n\n" +
                "Atentamente,\n$vendedor\nRISEK - Fuerza de Ventas"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, texto)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setPackage("com.whatsapp")
            }
            runCatching { context.startActivity(intent) }.getOrElse {
                val fallback = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, texto)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(fallback, "Enviar facturas pendientes"))
            }
        }
            .onSuccess { message = "Facturas pendientes listas para enviar por WhatsApp" }
            .onFailure { message = "No se pudieron enviar facturas pendientes: ${it.message}" }
    }
    fun abrirDtePdfReal(context: Context, doc: CuentaCorrienteEntity) = viewModelScope.launch {
        val url = Config.BASE_URL.trimEnd('/') +
            "/dte/pdf/${doc.ventaTipo}/${doc.ventaNumero}?cliente_rut=${Uri.encode(doc.clienteRut)}"
        runCatching {
            val file = descargarPdfDesdeUrl(context, url, "DTE_${doc.ventaTipo}_${doc.ventaNumero}.pdf")
            abrirPdfFileExterno(context, file)
            message = "PDF real descargado: ${doc.ventaTipo} ${doc.ventaNumero}"
        }.onFailure {
            message = "No se pudo abrir PDF real: ${it.message}"
        }
    }
    fun cargarUltimaVentaDetalle(cliente: CuentaCorrienteEntity) = viewModelScope.launch {
        runCatching { nv.ultimaVentaDetalle(cliente.clienteRut) }
            .onSuccess {
                ultimaVentaDetalle = it
                message = if (it.lines.isEmpty()) "Cliente sin ultima venta con detalle" else "Ultima venta cargada"
            }
            .onFailure { message = "No se pudo cargar ultima venta: ${it.message}" }
    }
    fun cargarDocumentoVentaDetalle(doc: CuentaCorrienteEntity) = viewModelScope.launch {
        runCatching { nv.ventaDocumentoDetalle(doc) }
            .onSuccess {
                ultimaVentaDetalle = it
                message = if (it.lines.isEmpty()) "Documento sin detalle disponible" else "Detalle ${doc.ventaTipo} ${doc.ventaNumero} cargado"
            }
            .onFailure { message = "No se pudo cargar detalle documento: ${it.message}" }
    }
    fun cerrarUltimaVentaDetalle() {
        ultimaVentaDetalle = null
    }
    fun copiarUltimaFacturaANuevaNv(clienteCta: CuentaCorrienteEntity, onReady: () -> Unit = {}) = viewModelScope.launch {
        isSyncing = true
        message = "Copiando ultima factura a una nueva NV..."
        runCatching {
            val cliente = catalog.clientePorRut(clienteCta.clienteRut)
                ?: throw IllegalStateException("Cliente no existe en la base local. Ejecute Sync > Descargar datos.")
            require(cliente.clienteEstado != "B") { "Cliente bloqueado: no se puede crear NV" }
            val lista = NV_PRICE_LIST
            val venta = nv.ultimaVentaDetalle(cliente.clienteRut)
            require(venta.ventaNumero != null && venta.lines.isNotEmpty()) { "No hay ultima venta con detalle para copiar" }
            val nuevasLineas = mutableListOf<NvLineEntity>()
            val omitidos = mutableListOf<String>()
            for (line in venta.lines.take(MAX_NV_LINES)) {
                val producto = catalog.searchProductos(line.productoCodigo, true).firstOrNull()
                if (producto == null) {
                    omitidos += line.productoCodigo
                    continue
                }
                val precioActual = catalog.precio(producto.productoCodigo, lista)
                if (precioActual == null || precioActual.listaVenta <= 0L || precioActual.listaNeto <= 0L) {
                    omitidos += "${line.productoCodigo} (sin precio lista $lista)"
                    continue
                }
                val uxe = (line.uxe ?: line.cantidad ?: 1.0).takeIf { it > 0.0 } ?: 1.0
                nuevasLineas += buildLine(producto, precioActual.listaNeto, precioActual.listaVenta, precioActual.listaIla, uxe, 0.0)
            }
            require(nuevasLineas.isNotEmpty()) { "No se pudo copiar: productos sin precio vigente o no encontrados" }
            selectedCliente = cliente
            direcciones = catalog.direcciones(cliente.clienteRut)
            selectedDireccion = direcciones.firstOrNull()?.direccion
            cart = nuevasLineas
            clientes = emptyList()
            productos = emptyList()
            selectedFamilia = null
            productoSearchQuery = ""
            productoPage = 0
            productoFamiliaTotal = 0
            editOfflineId = null
            observacionNv = "Copia de ${venta.ventaTipo ?: "venta"} ${venta.ventaNumero} del ${fechaCl(venta.ventaFecha)}".take(200)
            cuentaCorriente = null
            ultimasNvCliente = emptyList()
            ultimaVentaDetalle = null
            omitidos
        }.onSuccess { omitidos ->
            val extra = if (omitidos.isNotEmpty()) " Productos omitidos: ${omitidos.joinToString(", ")}." else ""
            val dirMsg = if (selectedDireccion.isNullOrBlank()) " Seleccione direccion de reparto." else " Revise direccion antes de guardar."
            message = "NV nueva creada desde ultima factura con precios actuales.$dirMsg$extra"
            onReady()
        }.onFailure {
            message = it.message ?: "No se pudo copiar la ultima factura"
        }
        isSyncing = false
    }
    fun buscarClientesResumenPorVoz(spoken: String) = viewModelScope.launch {
        val query = spoken.lowercase(Locale("es", "CL"))
            .replace(Regex("""\b(busca|buscar|cliente|resumen|cuenta|corriente|deuda|ventas?)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (query.length < 2) {
            message = "No entendi el cliente"
            return@launch
        }
        val found = catalog.searchClientes(query)
        val docCandidates = cartola.value
            .filter { it.clienteRut.contains(query, true) || (it.clienteNombre ?: "").contains(query, true) }
            .groupBy { it.clienteRut }
            .map { (_, rows) -> ResumenClienteCandidate(rows.first().clienteRut, rows.first().clienteNombre) }
        val candidates = (found.map { ResumenClienteCandidate(it.clienteRut, it.clienteNombre) } + docCandidates)
            .distinctBy { it.clienteRut }
            .take(20)
        if (candidates.isEmpty()) {
            val text = "No encontre cliente para $query"
            resumenClienteVoz = text
            message = text
            return@launch
        }
        resumenClienteCandidates = candidates
        resumenClienteVoz = null
        message = "Seleccione cliente para generar resumen"
    }
    fun generarResumenCliente(candidate: ResumenClienteCandidate, speak: (String) -> Unit = {}) = viewModelScope.launch {
        val clienteRut = candidate.clienteRut
        val clienteNombre = candidate.clienteNombre ?: clienteRut
        val docs = cartola.value.filter { it.clienteRut == clienteRut }
        val deuda = cuentaDeudaTotal(docs)
        val nc = cuentaNcTotal(docs)
        val saldo = deuda + nc
        val ultimaFactura = docs
            .filter { it.ventaTipo.equals("FE", true) || it.ventaTipo.equals("FA", true) }
            .sortedWith(compareByDescending<CuentaCorrienteEntity> { it.ventaFecha ?: "" }.thenByDescending { it.ventaNumero })
            .firstOrNull()
        val facturaText = if (ultimaFactura == null) {
            "No hay factura FE o FA en la cuenta corriente local."
        } else {
            "Ultima factura ${ultimaFactura.ventaTipo} ${ultimaFactura.ventaNumero}, fecha ${fechaCl(ultimaFactura.ventaFecha)}, monto ${pesosChilenosText(ultimaFactura.ventaTotalVenta)}."
        }
        val text = "$clienteNombre. Deuda pendiente ${pesosChilenosText(deuda)}, notas de credito ${pesosChilenosText(nc)}, saldo final ${pesosChilenosText(saldo)} en ${docs.size} documentos. $facturaText"
        resumenClienteVoz = text
        resumenClienteChartRows = docs
        resumenClienteCandidates = emptyList()
        message = "Resumen de cliente generado"
        speak(text)
    }
    fun cerrarResumenClienteVoz() {
        resumenClienteVoz = null
        resumenClienteChartRows = emptyList()
    }
    fun cerrarResumenClienteCandidates() {
        resumenClienteCandidates = emptyList()
    }
}

@Composable fun RisekRoot(vm: MainVm = viewModel()) {
    val ctx = LocalContext.current
    LaunchedEffect(vm.logged) {
        if (vm.logged) vm.checkAppUpdateOnEntry()
    }
    when {
        !vm.logged -> LoginScreen2(vm)
        vm.moduleChoicePending -> ModuleSelectorScreen(vm)
        vm.logisticaMode -> LogisticaScreen(vm)
        vm.gerenteMode -> GerenteScreen(vm)
        vm.supervisorMode -> SupervisorScreen(vm)
        else -> MainScreen2(vm)
    }
    AppUpdateDialog(vm, ctx)
    SyncDownloadResultDialog(vm)
    PdfPreviewDialog(vm, ctx)
}

@Composable fun PdfPreviewDialog(vm: MainVm, context: Context) {
    val title = vm.pdfPreviewTitle ?: return
    val bitmap = vm.pdfPreviewBitmap
    AlertDialog(
        onDismissRequest = { vm.cerrarPdfPreview() },
        title = { Text("Vista previa PDF - $title", fontWeight=FontWeight.Bold) },
        text = {
            if (bitmap == null) {
                Column(verticalArrangement=Arrangement.spacedBy(8.dp), horizontalAlignment=Alignment.CenterHorizontally, modifier=Modifier.fillMaxWidth()) {
                    CircularProgressIndicator(color=Purple)
                    Text("Preparando vista previa...", color=Muted)
                }
            } else {
                Box(Modifier.fillMaxWidth().heightIn(max=520.dp)) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Vista previa PDF",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick={ vm.abrirPdfPreviewExterno(context) }, colors=ButtonDefaults.buttonColors(containerColor=Purple)) {
                Icon(Icons.Default.OpenInNew, null)
                Spacer(Modifier.width(8.dp))
                Text("Abrir / descargar", fontWeight=FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick={ vm.cerrarPdfPreview() }) { Text("Cerrar", color=Muted) }
        }
    )
}

@Composable fun SyncDownloadResultDialog(vm: MainVm) {
    val title = vm.syncDownloadDialogTitle ?: return
    val color = if (vm.syncDownloadDialogIsError) Purple else Color(0xFF16A34A)
    val icon = if (vm.syncDownloadDialogIsError) Icons.Default.Error else Icons.Default.CheckCircle
    AlertDialog(
        onDismissRequest = { vm.cerrarSyncDownloadDialog() },
        icon = {
            Surface(shape=CircleShape, color=color.copy(alpha=.12f), modifier=Modifier.size(52.dp)) {
                Box(contentAlignment=Alignment.Center) { Icon(icon, null, tint=color, modifier=Modifier.size(30.dp)) }
            }
        },
        title = { Text(title, fontWeight=FontWeight.Bold) },
        text = { Text(vm.syncDownloadDialogMessage, color=Ink) },
        confirmButton = {
            Button(onClick={ vm.cerrarSyncDownloadDialog() }, colors=ButtonDefaults.buttonColors(containerColor=color)) {
                Text("Entendido", fontWeight=FontWeight.Bold)
            }
        }
    )
}

@Composable fun AppUpdateDialog(vm: MainVm, context: Context) {
    val info = vm.appUpdateInfo ?: return
    if (!vm.showAppUpdateDialog) return
    AlertDialog(
        onDismissRequest = {},
        icon = {
            Surface(shape=CircleShape, color=Purple.copy(alpha=.12f), modifier=Modifier.size(52.dp)) {
                Box(contentAlignment=Alignment.Center) { Icon(Icons.Default.SystemUpdate, null, tint=Purple, modifier=Modifier.size(30.dp)) }
            }
        },
        title = { Text("Nueva version disponible", fontWeight=FontWeight.Bold) },
        text = {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Version instalada: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color=Muted)
                Text("Version nueva: ${info.versionName ?: info.versionCode.toString()} (${info.versionCode})", color=Ink, fontWeight=FontWeight.SemiBold)
                if (!info.notes.isNullOrBlank()) {
                    Text(info.notes, color=Ink)
                }
                Text("Actualizacion obligatoria. Debe instalarla para continuar usando RISEK Ventas.", color=Muted, style=MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick={ vm.acceptAppUpdate(context) }, colors=ButtonDefaults.buttonColors(containerColor=Purple)) {
                Icon(Icons.Default.Download, null)
                Spacer(Modifier.width(8.dp))
                Text("Actualizar ahora", fontWeight=FontWeight.Bold)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LoginScreen(vm: MainVm) {
    val ctx = LocalContext.current
    val users by vm.users.collectAsState()
    LaunchedEffect(Unit) { vm.syncUsersOnStart() }
    var selectedUser by remember { mutableStateOf<SecUserEntity?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var pass by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(SoftBg).padding(22.dp), verticalArrangement=Arrangement.Center) {
        Card(shape=RoundedCornerShape(12.dp), modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(22.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Image(
                    painter = painterResource(id = R.drawable.logo_risek),
                    contentDescription = "Logo RISEK",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .padding(top = 8.dp, bottom = 10.dp)
                )
                Text("Acceso móvil offline", color=Color.Gray, modifier=Modifier.align(Alignment.CenterHorizontally))

                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = selectedUser?.let { "${it.secUserName} · Vend: ${it.vendedorCodigo ?: "sin vendedor"}" } ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("USUARIO") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        singleLine = true
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        if (users.isEmpty()) {
                            DropdownMenuItem(text = { Text("No hay usuarios cargados") }, onClick = { expanded = false })
                        } else {
                            users.forEach { u ->
                                DropdownMenuItem(
                                    text = { Text("${u.secUserName} · Vend: ${u.vendedorCodigo ?: "sin vendedor"}") },
                                    onClick = { selectedUser = u; expanded = false }
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(pass,{pass=it}, label={Text("Clave")}, modifier=Modifier.fillMaxWidth(), visualTransformation=PasswordVisualTransformation(), singleLine=true)
                Button(onClick={vm.loginSelected(selectedUser, pass)}, modifier=Modifier.fillMaxWidth(), enabled=!vm.isSyncing) { Text(if(vm.isSyncing) "Procesando..." else "Ingresar") }
                if (vm.isSyncing) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={vm.testConnection()}, modifier=Modifier.weight(1f)) { Text("Probar API") }
                    OutlinedButton(onClick={vm.refreshUsers()}, modifier=Modifier.weight(1f)) { Text("Actualizar usuarios") }
                }
                Text(vm.message, color=if(vm.message.contains("incorrect", true) || vm.message.contains("no existe", true) || vm.message.contains("error", true) || vm.message.contains("no se", true)) Color.Red else Ink, fontWeight=if(vm.message.contains("incorrect", true) || vm.message.contains("no existe", true)) FontWeight.Bold else FontWeight.Normal)
                
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LoginScreen2(vm: MainVm) {
    val users by vm.users.collectAsState()
    LaunchedEffect(Unit) { vm.syncUsersOnStart() }
    var selectedUser by remember { mutableStateOf<SecUserEntity?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var pass by remember { mutableStateOf("") }
    val isError = vm.message.contains("incorrect", true) || vm.message.contains("no existe", true) || vm.message.contains("error", true) || vm.message.contains("no se", true)
    val loginErrorPopup = vm.loginErrorPopup

    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.login_sales_bg_soft),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.20f),
                        Color.White.copy(alpha = 0.06f),
                        Color.White.copy(alpha = 0.16f)
                    )
                )
            )
        )
        Column(
            Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(Modifier.height(56.dp))

                Surface(shape = RoundedCornerShape(18.dp), color = Purple, shadowElevation = 8.dp, modifier = Modifier.size(86.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Inventory2, null, tint = Color.White, modifier = Modifier.size(52.dp))
                    }
                }
                Image(
                    painter = painterResource(id = R.drawable.logo_risek_transparent),
                    contentDescription = "RISEK",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.width(230.dp).height(92.dp)
                )
                Text("RISEK VENTAS", color = Color(0xFF30343B), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text("Ventas en terreno", color = Purple, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.88f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
                ) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = selectedUser?.let { "${it.secUserName}" } ?: "",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Usuario") },
                                leadingIcon = { Icon(Icons.Default.Person, null, tint = Purple) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Ink,
                                    unfocusedTextColor = Ink,
                                    focusedContainerColor = Color.White.copy(alpha = 0.78f),
                                    unfocusedContainerColor = Color.White.copy(alpha = 0.66f),
                                    focusedBorderColor = Purple,
                                    unfocusedBorderColor = Color(0xFFD1D5DB),
                                    focusedLabelColor = Purple,
                                    unfocusedLabelColor = Muted,
                                    cursorColor = Purple
                                )
                            )
                            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                if (users.isEmpty()) {
                                    DropdownMenuItem(text = { Text("No hay usuarios cargados") }, onClick = { expanded = false })
                                } else {
                                    users.forEach { u ->
                                        DropdownMenuItem(
                                            text = { Text("${u.secUserName} - Vend: ${u.vendedorCodigo ?: "sin vendedor"}") },
                                            onClick = { selectedUser = u; expanded = false }
                                        )
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = pass,
                            onValueChange = { pass = it },
                            label = { Text("Clave") },
                            leadingIcon = { Icon(Icons.Default.Lock, null, tint = Purple) },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Ink,
                                unfocusedTextColor = Ink,
                                focusedContainerColor = Color.White.copy(alpha = 0.78f),
                                unfocusedContainerColor = Color.White.copy(alpha = 0.66f),
                                focusedBorderColor = Purple,
                                unfocusedBorderColor = Color(0xFFD1D5DB),
                                focusedLabelColor = Purple,
                                unfocusedLabelColor = Muted,
                                cursorColor = Purple
                            )
                        )

                        Button(
                            onClick={vm.loginSelected(selectedUser, pass)},
                            modifier=Modifier.fillMaxWidth().height(52.dp),
                            enabled=!vm.isSyncing,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Purple, contentColor = Color.White)
                        ) {
                            Text(if(vm.isSyncing) "PROCESANDO..." else "INGRESAR", fontWeight = FontWeight.Bold)
                        }

                        if (vm.isSyncing) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Purple)
                        OutlinedButton(
                            onClick={vm.refreshUsers()},
                            modifier=Modifier.fillMaxWidth().height(50.dp),
                            shape=RoundedCornerShape(8.dp),
                            colors=ButtonDefaults.outlinedButtonColors(contentColor = Purple)
                        ) {
                            Icon(Icons.Default.Groups, null, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Sincroniza Usuario", fontWeight = FontWeight.SemiBold)
                        }
                        if (vm.message.isNotBlank()) {
                            Text(vm.message, color=if(isError) Color(0xFFB91C1C) else Muted, fontWeight=if(isError) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudOff, null, tint = Color(0xFF30343B))
                    Spacer(Modifier.width(8.dp))
                    Text(if (vm.apiOk == false) "Sin conexion" else "Modo offline disponible", color = Color(0xFF30343B), fontWeight = FontWeight.SemiBold)
                }
                Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = Color(0xFF30343B).copy(alpha = 0.82f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (loginErrorPopup != null) {
        AlertDialog(
            onDismissRequest = { vm.cerrarLoginErrorPopup() },
            title = { Text("Contraseña incorrecta", fontWeight = FontWeight.Bold) },
            text = { Text(loginErrorPopup) },
            confirmButton = {
                Button(onClick = { vm.cerrarLoginErrorPopup() }, colors = ButtonDefaults.buttonColors(containerColor = Purple)) {
                    Text("Aceptar")
                }
            }
        )
    }
}

@Composable fun ModuleSelectorScreen(vm: MainVm) {
    Box(Modifier.fillMaxSize().background(SoftBg)) {
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement=Arrangement.Center, horizontalAlignment=Alignment.CenterHorizontally) {
            Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(8.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) {
                Column(Modifier.padding(18.dp), verticalArrangement=Arrangement.spacedBy(14.dp), horizontalAlignment=Alignment.CenterHorizontally) {
                    Surface(shape=CircleShape, color=Purple.copy(alpha=0.12f), modifier=Modifier.size(58.dp)) {
                        Box(contentAlignment=Alignment.Center) { Icon(Icons.Default.AdminPanelSettings, null, tint=Purple, modifier=Modifier.size(30.dp)) }
                    }
                    Text("Seleccionar módulo", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold, color=Ink)
                    Text(vm.currentUser?.secUserName ?: "Supervisor", color=Muted)
                    if (vm.tieneVendedor()) {
                        Button(onClick={vm.seleccionarModuloVentas()}, modifier=Modifier.fillMaxWidth().height(52.dp), shape=RoundedCornerShape(6.dp), colors=ButtonDefaults.buttonColors(containerColor=Purple)) {
                            Icon(Icons.Default.Storefront, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Entrar como vendedor", fontWeight=FontWeight.Bold)
                        }
                    } else {
                        Text("Usuario sin vendedor asociado: acceso solo al modulo de repartos.", color=Muted, fontWeight=FontWeight.SemiBold)
                    }
                    OutlinedButton(onClick={vm.seleccionarModuloLogistica()}, modifier=Modifier.fillMaxWidth().height(52.dp), shape=RoundedCornerShape(6.dp)) {
                        Icon(Icons.Default.LocalShipping, null, tint=Purple)
                        Spacer(Modifier.width(8.dp))
                        Text("RISEK Logistica", fontWeight=FontWeight.Bold, color=Purple)
                    }
                    if (vm.puedeVerSupervisor()) {
                        OutlinedButton(onClick={vm.seleccionarModuloSupervisor()}, modifier=Modifier.fillMaxWidth().height(52.dp), shape=RoundedCornerShape(6.dp)) {
                            Icon(Icons.Default.Insights, null, tint=Purple)
                            Spacer(Modifier.width(8.dp))
                            Text("Panel Supervisor", fontWeight=FontWeight.Bold, color=Purple)
                        }
                    }
                    if (vm.puedeVerGerencia()) {
                        Button(onClick={vm.seleccionarModuloGerente()}, modifier=Modifier.fillMaxWidth().height(54.dp), shape=RoundedCornerShape(6.dp), colors=ButtonDefaults.buttonColors(containerColor=Purple, contentColor=Color.White)) {
                            Icon(Icons.Default.QueryStats, null, tint=Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("Panel Gerente", fontWeight=FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable fun LogisticaScreen(vm: MainVm) {
    var selectedFecha by remember { mutableStateOf(vm.logisticaFecha) }
    LaunchedEffect(selectedFecha) { vm.cargarLogisticaOnline(selectedFecha) }
    val dashboard = vm.logisticaDashboard
    val rutas = dashboard?.rutas ?: emptyList()
    var selectedRuta by remember(selectedFecha, dashboard?.fecha) { mutableStateOf<String?>(null) }
    val docsAll = dashboard?.documentos ?: emptyList()
    val docs = selectedRuta?.let { ruta -> docsAll.filter { it.rutaId == ruta } } ?: emptyList()
    val ctx = LocalContext.current
    fun moveDate(days: Long) {
        val base = runCatching { LocalDate.parse(selectedFecha) }.getOrDefault(LocalDate.now())
        selectedFecha = base.plusDays(days).toString()
    }

    Column(Modifier.fillMaxSize().background(SoftBg)) {
        Surface(color=Purple, tonalElevation=4.dp) {
            Row(Modifier.fillMaxWidth().height(66.dp).padding(horizontal=14.dp), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Default.LocalShipping, null, tint=Color.White, modifier=Modifier.size(28.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("RISEK Logistica", color=Color.White, fontWeight=FontWeight.Bold)
                        Text("Seleccione fecha y ruta", color=Color.White.copy(alpha=.82f), style=MaterialTheme.typography.labelSmall)
                    }
                }
                Row(verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={ vm.cargarLogisticaOnline(selectedFecha) }, enabled=!vm.isSyncing) { Icon(Icons.Default.Refresh, null, tint=Color.White) }
                    IconButton(onClick={ vm.salirLogin() }) { Icon(Icons.Default.Logout, null, tint=Color.White) }
                }
            }
        }
        LazyColumn(Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick={ vm.seleccionarModuloVentas() }, modifier=Modifier.weight(1f).height(46.dp), shape=RoundedCornerShape(8.dp), colors=ButtonDefaults.buttonColors(containerColor=Purple)) {
                        Icon(Icons.Default.Storefront, null, tint=Color.White, modifier=Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Ventas", color=Color.White, fontWeight=FontWeight.Bold)
                    }
                    OutlinedButton(onClick={ vm.volverSelectorSupervisor() }, modifier=Modifier.weight(1f).height(46.dp), shape=RoundedCornerShape(8.dp), colors=ButtonDefaults.outlinedButtonColors(contentColor=Purple)) {
                        Icon(Icons.Default.Apps, null, tint=Purple, modifier=Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Modulos", fontWeight=FontWeight.Bold)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(8.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) {
                    Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                            Text("Fecha de reparto", color=Ink, fontWeight=FontWeight.Bold)
                            AssistChip(onClick={ vm.cargarLogisticaOnline(selectedFecha) }, label={ Text(if(vm.isSyncing) "Actualizando" else "Online") }, leadingIcon={ Icon(Icons.Default.Refresh, null, modifier=Modifier.size(16.dp)) })
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
                            OutlinedIconButton(onClick={ moveDate(-1) }) { Icon(Icons.Default.ChevronLeft, null) }
                            Surface(Modifier.weight(1f), shape=RoundedCornerShape(8.dp), color=Color(0xFFF7F8FA)) {
                                Text(fechaCl(selectedFecha), color=Ink, fontWeight=FontWeight.Bold, modifier=Modifier.padding(horizontal=12.dp, vertical=11.dp))
                            }
                            OutlinedIconButton(onClick={ moveDate(1) }) { Icon(Icons.Default.ChevronRight, null) }
                        }
                        Text("Ruta de reparto", color=Ink, fontWeight=FontWeight.Bold)
                        if (rutas.isEmpty()) Text("Sin rutas para mostrar.", color=Muted)
                        rutas.take(12).forEach { ruta ->
                            Surface(
                                Modifier.fillMaxWidth().clickable { selectedRuta = ruta.rutaId },
                                shape=RoundedCornerShape(8.dp),
                                color=if(selectedRuta == ruta.rutaId) Purple.copy(alpha=.10f) else Color(0xFFF7F8FA)
                            ) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(ruta.rutaNombre ?: "Ruta ${ruta.rutaId ?: "-"}", color=Ink, fontWeight=FontWeight.SemiBold, maxLines=1)
                                    Text("Docs ${ruta.documentos} - Entregados ${ruta.entregados} - Pendientes ${ruta.pendientes}", color=Muted, style=MaterialTheme.typography.labelSmall)
                                }
                                if (selectedRuta == ruta.rutaId) Icon(Icons.Default.CheckCircle, null, tint=Purple)
                            }
                            }
                        }
                    }
                }
            }
            item { Text(if(selectedRuta == null) "Seleccione una ruta para ver documentos" else "Documentos a entregar (${docs.size})", color=Ink, fontWeight=FontWeight.Bold) }
            if (docs.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(8.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) {
                        Text(if(selectedRuta == null) "Antes de salir al reparto seleccione fecha y ruta." else "No hay documentos de reparto para la fecha y ruta seleccionadas.", color=Muted, modifier=Modifier.padding(14.dp))
                    }
                }
            } else {
                items(docs, key={ "${it.ventaTipo}-${it.localCodigo}-${it.ventaNumero}" }) { doc ->
                    LogisticaDocumentoCard(doc, vm, ctx)
                }
            }
        }
    }
}

@Composable fun LogisticaDocumentoCard(doc: LogisticaDocumentoDto, vm: MainVm, ctx: Context) {
    var showRechazo by remember { mutableStateOf(false) }
    var observacion by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(8.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) {
        Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("${doc.ventaTipo} ${doc.ventaNumero}", color=Ink, fontWeight=FontWeight.Bold)
                    Text(doc.clienteNombre ?: "-", color=Ink, fontWeight=FontWeight.SemiBold, maxLines=1)
                    Text(doc.clienteRut ?: "-", color=Muted, style=MaterialTheme.typography.labelSmall)
                }
                LogisticaEstadoChip(doc.estado)
            }
            Text(doc.direccion ?: "Sin direccion", color=Ink, maxLines=2)
            Text("${doc.comuna ?: ""} - ${doc.rutaNombre ?: "Sin ruta"}", color=Muted, style=MaterialTheme.typography.labelSmall)
            if (!doc.observacion.isNullOrBlank()) {
                Text("Obs: ${doc.observacion}", color=Color(0xFFB91C1C), style=MaterialTheme.typography.labelSmall, fontWeight=FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                Text(clp(doc.total), color=Purple, fontWeight=FontWeight.Bold)
                Text("Entrega ${fechaCl(doc.fechaReparto)}", color=Muted, style=MaterialTheme.typography.labelSmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={ openLogisticaMap(ctx, doc.clienteGeo, doc.direccion, doc.comuna) }, modifier=Modifier.weight(1f), shape=RoundedCornerShape(6.dp)) {
                    Icon(Icons.Default.LocationOn, null, tint=Purple, modifier=Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Mapa", color=Purple)
                }
                Button(onClick={ vm.cambiarEstadoLogistica(doc, "ENTREGADO") }, modifier=Modifier.weight(1f), shape=RoundedCornerShape(6.dp), colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF16A34A))) {
                    Text("Entregado", color=Color.White)
                }
            }
            OutlinedButton(onClick={ observacion = ""; showRechazo = true }, modifier=Modifier.fillMaxWidth(), shape=RoundedCornerShape(6.dp), colors=ButtonDefaults.outlinedButtonColors(contentColor=Color(0xFFB91C1C))) {
                Text("Rechazado / No entregado", color=Color(0xFFB91C1C), fontWeight=FontWeight.Bold)
            }
        }
    }
    if (showRechazo) {
        AlertDialog(
            onDismissRequest={ showRechazo = false },
            title={ Text("Observacion de rechazo", fontWeight=FontWeight.Bold, color=Ink) },
            text={
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Indique por que no se entrego el documento ${doc.ventaTipo} ${doc.ventaNumero}.", color=Muted)
                    OutlinedTextField(
                        value=observacion,
                        onValueChange={ observacion = it.take(200) },
                        label={ Text("Observacion obligatoria") },
                        modifier=Modifier.fillMaxWidth(),
                        minLines=3
                    )
                }
            },
            confirmButton={
                Button(
                    onClick={
                        vm.cambiarEstadoLogistica(doc, "NO_ENTREGADO", observacion.trim())
                        showRechazo = false
                    },
                    enabled=observacion.trim().length >= 3,
                    colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFB91C1C), contentColor=Color.White)
                ) { Text("Guardar rechazo") }
            },
            dismissButton={ TextButton(onClick={ showRechazo = false }) { Text("Cancelar") } },
            containerColor=Color.White,
            shape=RoundedCornerShape(8.dp)
        )
    }
}

@Composable fun LogisticaEstadoChip(estado: String) {
    val color = when (estado) {
        "ENTREGADO" -> Color(0xFF16A34A)
        "EN_RUTA" -> Color(0xFF2563EB)
        "NO_ENTREGADO" -> Color(0xFFB91C1C)
        else -> Muted
    }
    Surface(shape=RoundedCornerShape(6.dp), color=color.copy(alpha=.12f)) {
        Text(if(estado == "NO_ENTREGADO") "RECHAZADO" else estado.replace("_", " "), color=color, fontWeight=FontWeight.Bold, style=MaterialTheme.typography.labelSmall, modifier=Modifier.padding(horizontal=8.dp, vertical=5.dp))
    }
}

@Composable fun GerenteScreen(vm: MainVm) {
    LaunchedEffect(Unit) {
        if (vm.gerenteDashboard == null) vm.cargarGerenteOnline()
    }
    val dashboard = vm.gerenteDashboard
    val summary = dashboard?.summary ?: GerenteSummaryDto()
    val ventas = summary.ventasMes.takeIf { it > 0 } ?: summary.ventas30
    val ventasDia = summary.ventasDia
    val deuda = summary.deudaTotal
    val clientesMes = summary.clientesMes.takeIf { it > 0 } ?: summary.clientes30
    val mesLabel = gerenteMesLabel(summary.mesActual)
    val listState = rememberLazyListState()
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().background(SoftBg)) {
        Surface(color=Color(0xFF111827), tonalElevation=4.dp) {
            Row(Modifier.fillMaxWidth().height(66.dp).padding(horizontal=14.dp), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Surface(shape=RoundedCornerShape(8.dp), color=Purple, modifier=Modifier.size(38.dp)) {
                        Box(contentAlignment=Alignment.Center) { Text("R", color=Color.White, fontWeight=FontWeight.Black) }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Panel Gerente", color=Color.White, fontWeight=FontWeight.Bold)
                        Text("Finanzas y decisiones", color=Color.White.copy(alpha=.76f), style=MaterialTheme.typography.labelSmall)
                    }
                }
                Row(verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={ vm.cargarGerenteOnline() }, enabled=!vm.isSyncing) { Icon(Icons.Default.Refresh, null, tint=Color.White) }
                    IconButton(onClick={ vm.salirLogin() }) { Icon(Icons.Default.Logout, null, tint=Color.White) }
                }
            }
        }
        LazyColumn(Modifier.padding(14.dp), state=listState, verticalArrangement=Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick={ vm.seleccionarModuloVentas() }, modifier=Modifier.weight(1f).height(46.dp), shape=RoundedCornerShape(8.dp), colors=ButtonDefaults.buttonColors(containerColor=Purple)) {
                        Icon(Icons.Default.Storefront, null, tint=Color.White, modifier=Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Ventas Offline", color=Color.White, fontWeight=FontWeight.Bold)
                    }
                    OutlinedButton(onClick={ vm.volverSelectorSupervisor() }, modifier=Modifier.weight(1f).height(46.dp), shape=RoundedCornerShape(8.dp), colors=ButtonDefaults.outlinedButtonColors(contentColor=Color(0xFF111827))) {
                        Icon(Icons.Default.Apps, null, tint=Color(0xFF111827), modifier=Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Modulos", fontWeight=FontWeight.Bold)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(8.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) {
                    Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                            Text("Resumen ejecutivo", fontWeight=FontWeight.Bold, color=Ink)
                            AssistChip(onClick={ vm.cargarGerenteOnline() }, label={ Text(if(vm.isSyncing) "Actualizando" else mesLabel) }, leadingIcon={ Icon(Icons.Default.CloudDone, null, modifier=Modifier.size(16.dp)) })
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            SupervisorKpi("Ventas del mes", clp(ventas), Icons.Default.Payments, Modifier.weight(1.35f), ventas > 0)
                            SupervisorKpi("Ventas del dia", clp(ventasDia), Icons.Default.Today, Modifier.weight(1.35f), ventasDia > 0)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            SupervisorKpi("Deuda total", clp(deuda), Icons.Default.AccountBalanceWallet, Modifier.weight(1.35f), deuda > 0)
                            SupervisorKpi("Local 01", clp(summary.ventasLocal01), Icons.Default.Storefront, Modifier.weight(1.35f), summary.ventasLocal01 > 0)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            SupervisorKpi("Local 02", clp(summary.ventasLocal02), Icons.Default.Store, Modifier.weight(1.35f), summary.ventasLocal02 > 0)
                            SupervisorKpi("Clientes mes", clientesMes.toString(), Icons.Default.Groups, Modifier.weight(1f), clientesMes > 0)
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(8.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) {
                    Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        if (dashboard != null) GerenteLocalBarsChart("Ventas mensuales por local 01 / 02", dashboard.ventasMensualesLocales) else Text("Cargando datos online...", color=Muted)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(8.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) {
                    Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        if (dashboard != null) GerenteLocalBarsChart("Ventas del mes actual por local - $mesLabel", dashboard.ventasMesLocales) else Text("Cargando datos del mes...", color=Muted)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(8.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) {
                    Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        if (dashboard != null) GerenteSectorChart(dashboard.sectores) else Text("Cargando sectores...", color=Muted)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(8.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) {
                    Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        GerenteYearCompareChart(dashboard?.ventasAnioComparativo.orEmpty())
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(8.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) {
                    Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(9.dp)) {
                        Text("Mapa ventas del dia (NV)", fontWeight=FontWeight.Bold, color=Ink)
                        val puntos = dashboard?.nvHoyMapa.orEmpty()
                        if (puntos.isEmpty()) {
                            Text("Sin NV georreferenciadas hoy.", color=Muted)
                        } else {
                            puntos.take(10).forEach { nv ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("NV ${nv.ventaNumero} - ${nv.clienteNombre ?: nv.clienteRut ?: "-"}", color=Ink, fontWeight=FontWeight.SemiBold, maxLines=1)
                                        Text(clp(nv.total), color=Muted, style=MaterialTheme.typography.labelSmall)
                                    }
                                    IconButton(onClick={ openClientMap(context, nv.clienteGeo) }) { Icon(Icons.Default.LocationOn, contentDescription="Ver mapa", tint=Purple) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun gerenteMesLabel(value: String?): String {
    val parts = value?.split("-")
    val month = parts?.getOrNull(1)?.toIntOrNull() ?: LocalDate.now().monthValue
    val year = parts?.getOrNull(0) ?: LocalDate.now().year.toString()
    val names = listOf("enero","febrero","marzo","abril","mayo","junio","julio","agosto","septiembre","octubre","noviembre","diciembre")
    return "${names.getOrElse(month - 1) { "" }} $year".trim()
}

data class GerenteVoiceResult(val answer: String, val sectionIndex: Int)

fun gerenteVoiceResult(spoken: String, dashboard: GerenteDashboardDto?, summary: GerenteSummaryDto, mesLabel: String): GerenteVoiceResult {
    val text = normalizeSearchText(spoken)
    val ventas = summary.ventasMes.takeIf { it > 0 } ?: summary.ventas30
    val cobranza = summary.cobranzaMes.takeIf { it > 0 } ?: summary.cobranza30
    val facturas = summary.facturasMes.takeIf { it > 0 } ?: summary.facturas30
    val nc = summary.ncMes.takeIf { it > 0 } ?: summary.nc30
    if (text.contains("deuda") && (text.contains("cliente") || text.contains("mayor") || text.contains("mas"))) {
        val top = dashboard?.deudas.orEmpty().take(5)
        val detalle = if (top.isEmpty()) "No hay clientes con deuda para mostrar." else top.joinToString(". ") {
            "${it.clienteNombre ?: it.clienteRut ?: "Cliente"} ${pesosChilenosText(it.saldo)}"
        }
        return GerenteVoiceResult("Clientes con mas deuda. $detalle", 6)
    }
    if (text.contains("deuda")) {
        return GerenteVoiceResult("La deuda total es ${pesosChilenosText(summary.deudaTotal)}, con ${summary.clientesDeuda} clientes con saldo pendiente.", 2)
    }
    if (text.contains("nota") || text.contains("credito") || text.contains("credito")) {
        return GerenteVoiceResult("Las notas de credito del mes son $nc documentos.", 2)
    }
    if (text.contains("7") || text.contains("siete") || text.contains("ultimos")) {
        val total7 = dashboard?.trend.orEmpty().sumOf { it.total }
        return GerenteVoiceResult("Ventas de los ultimos 7 dias: ${pesosChilenosText(total7)}. Abri el grafico correspondiente.", 3)
    }
    if (text.contains("vendedor") || text.contains("vendio") || text.contains("vendio")) {
        val vendedores = dashboard?.vendedores.orEmpty()
        val vendedorQuery = text
            .replace("cuanto", " ")
            .replace("cuánto", " ")
            .replace("vendio", " ")
            .replace("vendió", " ")
            .replace("vendedor", " ")
            .replace("ventas", " ")
            .replace("por", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val vendedor = vendedores.firstOrNull {
            val nombre = normalizeSearchText(it.vendedorNombre ?: "")
            val codigo = normalizeSearchText(it.vendedorCodigo ?: "")
            vendedorQuery.isNotBlank() && (nombre.contains(vendedorQuery) || codigo.contains(vendedorQuery))
        }
        if (vendedor != null) {
            return GerenteVoiceResult("${vendedor.vendedorNombre ?: vendedor.vendedorCodigo} vendio ${pesosChilenosText(vendedor.total)} con ${vendedor.facturas} facturas, ${vendedor.documentos} documentos y ${vendedor.clientes} clientes.", 5)
        }
        val top = vendedores.take(5).joinToString(". ") { "${it.vendedorNombre ?: it.vendedorCodigo ?: "Vendedor"} ${pesosChilenosText(it.total)}" }
        return GerenteVoiceResult(if (top.isBlank()) "No hay ventas por vendedor para mostrar." else "Ventas por vendedor. $top", 5)
    }
    if (text.contains("mes") || text.contains("venta")) {
        return GerenteVoiceResult("Ventas del mes de $mesLabel: ${pesosChilenosText(ventas)}, con $facturas facturas y cobranza de ${pesosChilenosText(cobranza)}.", 2)
    }
    return GerenteVoiceResult("Puede preguntar ventas del mes, deuda total, ventas por vendedor, cuanto vendio un vendedor, clientes con mas deuda, notas de credito del mes o ventas ultimos 7 dias. Todos los montos se informan en pesos chilenos.", 1)
}

@Composable fun GerenteSalesChart(title: String, trend: List<SupervisorTrendDto>) {
    val values = trend.map { it.total }
    val maxValue = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(title, color=Ink, fontWeight=FontWeight.Bold)
        if (trend.isEmpty()) {
            Text("Sin ventas para mostrar.", color=Muted)
        } else {
            Canvas(Modifier.fillMaxWidth().height(92.dp)) {
                val safeCount = values.size.coerceAtLeast(1)
                val gap = if (safeCount > 12) 3.dp.toPx() else 7.dp.toPx()
                val barWidth = (size.width - gap * (safeCount - 1)) / safeCount
                values.forEachIndexed { index, value ->
                    val h = (value.toFloat() / maxValue.toFloat()) * size.height
                    val left = index * (barWidth + gap)
                    drawRect(
                        color=if(value > 0) Purple else Color(0xFFE5E7EB),
                        topLeft=androidx.compose.ui.geometry.Offset(left, size.height - h),
                        size=androidx.compose.ui.geometry.Size(barWidth, h.coerceAtLeast(4f))
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                trend.forEachIndexed { index, item ->
                    if (trend.size <= 10 || index == 0 || index == trend.lastIndex || index % 5 == 0) {
                        Text((item.fecha ?: "").takeLast(5), color=Muted, style=MaterialTheme.typography.labelSmall, maxLines=1)
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }
                }
            }
        }
    }
}

@Composable fun GerenteLocalBarsChart(title: String, rows: List<GerenteLocalTrendDto>) {
    val data = rows.takeLast(12)
    val maxValue = data.flatMap { listOf(it.total01, it.total02) }.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title, color=Ink, fontWeight=FontWeight.Bold)
        if (data.isEmpty()) {
            Text("Sin ventas para mostrar.", color=Muted)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.End) {
                AssistChip(onClick={}, label={ Text("Local 01") }, leadingIcon={ Box(Modifier.size(10.dp).background(Purple, RoundedCornerShape(2.dp))) })
                Spacer(Modifier.width(6.dp))
                AssistChip(onClick={}, label={ Text("Local 02") }, leadingIcon={ Box(Modifier.size(10.dp).background(Color(0xFF111827), RoundedCornerShape(2.dp))) })
            }
            Row(Modifier.fillMaxWidth().height(138.dp), horizontalArrangement=Arrangement.spacedBy(5.dp), verticalAlignment=Alignment.Bottom) {
                data.forEach { item ->
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.Bottom) {
                        Row(Modifier.weight(1f), horizontalArrangement=Arrangement.spacedBy(2.dp), verticalAlignment=Alignment.Bottom) {
                            GerenteMiniBar(item.total01, maxValue, Purple)
                            GerenteMiniBar(item.total02, maxValue, Color(0xFF111827))
                        }
                        Text((item.fecha ?: "").takeLast(5), color=Muted, style=MaterialTheme.typography.labelSmall, maxLines=1)
                    }
                }
            }
        }
    }
}

@Composable fun GerenteMiniBar(value: Long, maxValue: Long, color: Color) {
    val pct = (value.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
    Box(Modifier.width(26.dp).fillMaxHeight(), contentAlignment=Alignment.BottomCenter) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(pct.coerceAtLeast(if(value > 0) .06f else .02f))
                .background(if(value > 0) color else Color(0xFFE5E7EB), RoundedCornerShape(topStart=4.dp, topEnd=4.dp)),
            contentAlignment=Alignment.TopCenter
        ) {
            if (value > 0) Text(clpCompact(value), color=Color.White, style=MaterialTheme.typography.labelSmall, maxLines=1)
        }
    }
}

@Composable fun GerenteSectorChart(rows: List<GerenteSectorDto>) {
    val data = rows.take(8)
    val maxValue = data.maxOfOrNull { it.total }?.coerceAtLeast(1L) ?: 1L
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Sectores con mayor venta", fontWeight=FontWeight.Bold, color=Ink)
        if (data.isEmpty()) {
            Text("Sin sectores para mostrar.", color=Muted)
        } else {
            data.forEach { sector ->
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                        Text(sector.sector ?: "Sin sector", color=Ink, fontWeight=FontWeight.SemiBold, maxLines=1, modifier=Modifier.weight(1f))
                        Text(clp(sector.total), color=Purple, fontWeight=FontWeight.Bold, maxLines=1)
                    }
                    LinearProgressIndicator(
                        progress={ sector.total.toFloat() / maxValue.toFloat() },
                        modifier=Modifier.fillMaxWidth().height(8.dp),
                        color=Purple,
                        trackColor=Color(0xFFE5E7EB)
                    )
                    Text("Clientes ${sector.clientes}", color=Muted, style=MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable fun GerenteYearCompareChart(rows: List<GerenteYearCompareDto>) {
    val data = rows.take(12)
    val maxValue = data.flatMap { listOf(it.actual, it.anterior) }.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Ventas del año vs año anterior", fontWeight=FontWeight.Bold, color=Ink)
        if (data.isEmpty()) {
            Text("Sin comparativo para mostrar.", color=Muted)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.End) {
                AssistChip(onClick={}, label={ Text("Actual") }, leadingIcon={ Box(Modifier.size(10.dp).background(Purple, RoundedCornerShape(2.dp))) })
                Spacer(Modifier.width(6.dp))
                AssistChip(onClick={}, label={ Text("Anterior") }, leadingIcon={ Box(Modifier.size(10.dp).background(Color(0xFF6B7280), RoundedCornerShape(2.dp))) })
            }
            Row(Modifier.fillMaxWidth().height(138.dp), horizontalArrangement=Arrangement.spacedBy(5.dp), verticalAlignment=Alignment.Bottom) {
                data.forEach { item ->
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.Bottom) {
                        Row(Modifier.weight(1f), horizontalArrangement=Arrangement.spacedBy(2.dp), verticalAlignment=Alignment.Bottom) {
                            GerenteMiniBar(item.actual, maxValue, Purple)
                            GerenteMiniBar(item.anterior, maxValue, Color(0xFF6B7280))
                        }
                        Text(item.mesLabel ?: item.mes.toString(), color=Muted, style=MaterialTheme.typography.labelSmall, maxLines=1)
                    }
                }
            }
        }
    }
}

@Composable fun GerenteMiniFinance(ventas: Long, cobranza: Long, deuda: Long) {
    val maxValue = maxOf(ventas, cobranza, deuda, 1L)
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("Finanzas", color=Ink, fontWeight=FontWeight.SemiBold, style=MaterialTheme.typography.bodySmall)
        listOf("Ventas" to ventas, "Cobranza" to cobranza, "Deuda" to deuda).forEach { (label, value) ->
            Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(label, color=Muted, style=MaterialTheme.typography.labelSmall, modifier=Modifier.width(62.dp))
                LinearProgressIndicator(
                    progress={ value.toFloat() / maxValue.toFloat() },
                    modifier=Modifier.weight(1f).height(8.dp),
                    color=if(label == "Deuda") Color(0xFFB91C1C) else Purple,
                    trackColor=Color(0xFFE5E7EB)
                )
                Text(clp(value), color=Ink, style=MaterialTheme.typography.labelSmall, fontWeight=FontWeight.Bold, modifier=Modifier.width(92.dp))
            }
        }
    }
}

@Composable fun SupervisorScreen(vm: MainVm) {
    val nvs by vm.nvs.collectAsState()
    val clientes by vm.allClientes.collectAsState()
    LaunchedEffect(Unit) { vm.cargarSupervisorOnline() }
    val dashboard = vm.supervisorDashboard
    val today = LocalDate.now().toString()
    val ventasHoy = nvs.filter { it.fecha.take(10) == today }
    val clientesHoy = ventasHoy.map { it.clienteRut }.distinct().size
    val pendientes = nvs.count { it.syncStatus == "PENDIENTE" || it.syncStatus == "ERROR" }
    val summary = dashboard?.summary
    val porVendedor = nvs.groupBy { it.vendedorCodigo ?: "-" }
        .map { (v, rows) -> v to rows }
        .sortedByDescending { it.second.sumOf { row -> row.total } }
        .take(6)
    val clienteRuta = clientes.associateBy { it.clienteRut }
    val porRuta = nvs.groupBy { h -> clienteRuta[h.clienteRut]?.rutaId?.toString() ?: "Sin ruta" }
        .map { (ruta, rows) -> ruta to rows }
        .sortedByDescending { it.second.sumOf { row -> row.total } }
        .take(6)
    val facturasHoy = summary?.facturasHoy?.takeIf { it > 0 } ?: summary?.nvHoy ?: ventasHoy.size
    val documentosHoy = summary?.documentosHoy ?: facturasHoy
    val ncHoy = summary?.ncHoy ?: 0
    val ventaGestion = summary?.venta30?.takeIf { it > 0 } ?: summary?.ventaTotal ?: ventasHoy.sumOf { it.total }
    val facturasGestion = summary?.facturas30?.takeIf { it > 0 } ?: facturasHoy
    val clientesGestion = summary?.clientes30?.takeIf { it > 0 } ?: summary?.clientes ?: clientesHoy
    val ticketPromedio = summary?.ticketPromedio?.takeIf { it > 0 } ?: (ventaGestion / maxOf(facturasGestion, 1).toLong())

    Column(Modifier.fillMaxSize().background(SoftBg)) {
        Surface(color=Purple, tonalElevation=4.dp) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal=12.dp), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Default.AdminPanelSettings, null, tint=Color.White)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Panel Supervisor", color=Color.White, fontWeight=FontWeight.Bold)
                        Text("Supervisor", color=Color.White.copy(alpha=.82f), style=MaterialTheme.typography.labelSmall)
                    }
                }
                Row(verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={ vm.cargarSupervisorOnline() }, enabled=!vm.isSyncing) { Icon(Icons.Default.Refresh, null, tint=Color.White) }
                }
            }
        }
        LazyColumn(Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick={ vm.seleccionarModuloVentas() }, modifier=Modifier.weight(1f).height(46.dp), shape=RoundedCornerShape(8.dp), colors=ButtonDefaults.buttonColors(containerColor=Purple)) {
                        Icon(Icons.Default.Storefront, null, tint=Color.White, modifier=Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Entrar vendedor", color=Color.White, fontWeight=FontWeight.Bold)
                    }
                    OutlinedButton(onClick={ vm.salirLogin() }, modifier=Modifier.weight(1f).height(46.dp), shape=RoundedCornerShape(8.dp), colors=ButtonDefaults.outlinedButtonColors(contentColor=Purple)) {
                        Icon(Icons.Default.Logout, null, tint=Purple, modifier=Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Salir login", fontWeight=FontWeight.Bold)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(8.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) {
                    Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                            Text("Resumen del Dia", fontWeight=FontWeight.Bold, color=Ink)
                            AssistChip(onClick={ vm.cargarSupervisorOnline() }, label={ Text(if(vm.isSyncing) "Actualizando" else "Actualizar") }, leadingIcon={ Icon(Icons.Default.Refresh, null, modifier=Modifier.size(16.dp)) })
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            SupervisorKpi("Venta 30 dias", clp(ventaGestion), Icons.Default.Payments, Modifier.weight(1.35f), ventaGestion > 0)
                            SupervisorKpi("Facturas 30d", facturasGestion.toString(), Icons.Default.ReceiptLong, Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            SupervisorKpi("Clientes 30d", clientesGestion.toString(), Icons.Default.Groups, Modifier.weight(1f))
                            SupervisorKpi("Ticket prom.", clp(ticketPromedio), Icons.Default.Analytics, Modifier.weight(1.35f))
                        }
                        if (dashboard != null) SupervisorOnlineTrendChart(dashboard.trend) else SupervisorTrendChart(nvs)
                        Text(if (dashboard != null) "Hoy: $documentosHoy documentos, $ncHoy NC. Datos online consolidados, sin NV." else "Mostrando datos locales mientras carga online", color=Muted, style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(8.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) {
                    Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("Gestion de Vendedores", fontWeight=FontWeight.Bold, color=Ink)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text("Vendedor", Modifier.weight(1.25f), color=Muted, style=MaterialTheme.typography.labelSmall, fontWeight=FontWeight.Bold)
                            Text("Fact.", Modifier.weight(.45f), color=Muted, style=MaterialTheme.typography.labelSmall, fontWeight=FontWeight.Bold)
                            Text("Total", Modifier.weight(1f), color=Muted, style=MaterialTheme.typography.labelSmall, fontWeight=FontWeight.Bold)
                            Text("Clientes", Modifier.weight(.65f), color=Muted, style=MaterialTheme.typography.labelSmall, fontWeight=FontWeight.Bold)
                        }
                        if (dashboard != null) {
                            dashboard.vendedores.take(8).forEach { v ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { vm.cargarSupervisorVendedor(v.vendedorCodigo ?: "") }.padding(vertical=5.dp),
                                    horizontalArrangement=Arrangement.spacedBy(6.dp),
                                    verticalAlignment=Alignment.CenterVertically
                                ) {
                                    Text(v.vendedorNombre ?: v.vendedorCodigo ?: "-", Modifier.weight(1.25f), color=Ink, fontWeight=FontWeight.SemiBold, maxLines=1)
                                    Text((v.facturas.takeIf { it > 0 } ?: v.nv).toString(), Modifier.weight(.45f), color=Ink)
                                    Text(clp(v.total), Modifier.weight(1f), color=Purple, fontWeight=FontWeight.Bold, maxLines=1)
                                    Row(Modifier.weight(.65f), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                                        Text(v.clientes.toString(), color=Ink)
                                        Icon(Icons.Default.ChevronRight, null, tint=Muted, modifier=Modifier.size(18.dp))
                                    }
                                }
                            }
                        } else porVendedor.forEach { (v, rows) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(6.dp), verticalAlignment=Alignment.CenterVertically) {
                                Text(v, Modifier.weight(1.25f), color=Ink, fontWeight=FontWeight.SemiBold, maxLines=1)
                                Text(rows.size.toString(), Modifier.weight(.45f), color=Ink)
                                Text(clp(rows.sumOf { it.total }), Modifier.weight(1f), color=Purple, fontWeight=FontWeight.Bold, maxLines=1)
                                Text(rows.map { it.clienteRut }.distinct().size.toString(), Modifier.weight(.65f), color=Ink)
                            }
                        }
                        if ((dashboard?.vendedores?.isEmpty() ?: porVendedor.isEmpty())) Text("Sin ventas para mostrar.", color=Muted)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(8.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) {
                    Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("Ventas por Ruta", fontWeight=FontWeight.Bold, color=Ink)
                        if (dashboard != null) {
                            val maxRuta = dashboard.rutas.maxOfOrNull { it.venta }?.coerceAtLeast(1L) ?: 1L
                            dashboard.rutas.take(8).forEach { ruta ->
                                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                                        Text(ruta.rutaNombre ?: "Ruta ${ruta.rutaId ?: "-"}", color=Ink, fontWeight=FontWeight.SemiBold, maxLines=1)
                                        Text(clp(ruta.venta), color=Purple, fontWeight=FontWeight.Bold)
                                    }
                                    LinearProgressIndicator(progress={ ruta.venta.toFloat() / maxRuta.toFloat() }, modifier=Modifier.fillMaxWidth().height(8.dp), color=Purple, trackColor=Color(0xFFE5E7EB))
                                    val rutaFacturas = ruta.facturas.takeIf { it > 0 } ?: ruta.nv
                                    Text("Cobertura ${ruta.clientes} clientes - Facturas $rutaFacturas - Docs ${ruta.documentos}", color=Muted, style=MaterialTheme.typography.labelSmall)
                                }
                            }
                        } else {
                            val maxRuta = porRuta.maxOfOrNull { it.second.sumOf { row -> row.total } }?.coerceAtLeast(1L) ?: 1L
                            porRuta.forEach { (ruta, rows) ->
                            val total = rows.sumOf { it.total }
                            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                                    Text("Ruta $ruta", color=Ink, fontWeight=FontWeight.SemiBold)
                                    Text(clp(total), color=Purple, fontWeight=FontWeight.Bold)
                                }
                                LinearProgressIndicator(progress={ total.toFloat() / maxRuta.toFloat() }, modifier=Modifier.fillMaxWidth().height(8.dp), color=Purple, trackColor=Color(0xFFE5E7EB))
                                Text("Cobertura ${rows.map { it.clienteRut }.distinct().size} clientes", color=Muted, style=MaterialTheme.typography.labelSmall)
                            }
                            }
                        }
                        if ((dashboard?.rutas?.isEmpty() ?: porRuta.isEmpty())) Text("Sin rutas con ventas para mostrar.", color=Muted)
                    }
                }
            }
            if (dashboard != null) {
                item {
                    Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(8.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) {
                        Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                                Text("Productos Mas Vendidos", fontWeight=FontWeight.Bold, color=Ink)
                                AssistChip(onClick={}, label={ Text("Ultimos 30 dias") }, leadingIcon={ Icon(Icons.Default.TrendingUp, null, modifier=Modifier.size(16.dp)) })
                            }
                            if (dashboard.productos.isEmpty() && dashboard.familias.isEmpty()) {
                                Text("Sin detalle de productos para mostrar.", color=Muted)
                            } else {
                                Text("Productos", color=Muted, style=MaterialTheme.typography.labelSmall, fontWeight=FontWeight.Bold)
                                dashboard.productos.take(6).forEach { producto ->
                                    SupervisorProductoRow(
                                        titulo = producto.productoDescripcion ?: producto.productoCodigo ?: "-",
                                        subtitulo = "${producto.productoCodigo ?: "-"} - ${producto.familiaDescripcion ?: "-"}",
                                        uxe = producto.uxeTotal,
                                        total = producto.total
                                    )
                                }
                                Divider(color=Color(0xFFE5E7EB))
                                Text("Familias", color=Muted, style=MaterialTheme.typography.labelSmall, fontWeight=FontWeight.Bold)
                                dashboard.familias.take(5).forEach { familia ->
                                    SupervisorProductoRow(
                                        titulo = familia.familiaDescripcion ?: familia.familiaCodigo ?: "-",
                                        subtitulo = "Familia ${familia.familiaCodigo ?: "-"}",
                                        uxe = familia.uxeTotal,
                                        total = familia.total
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        SupervisorVendedorDialog(vm)
    }
}

@Composable fun SupervisorVendedorDialog(vm: MainVm) {
    val detalle = vm.supervisorVendedorDetalle ?: return
    AlertDialog(
        onDismissRequest = { vm.cerrarSupervisorVendedor() },
        confirmButton = {
            TextButton(onClick={ vm.cerrarSupervisorVendedor() }) { Text("Cerrar", color=Purple, fontWeight=FontWeight.Bold) }
        },
        title = {
            Column {
                Text(detalle.vendedorNombre ?: detalle.vendedorCodigo ?: "Vendedor", fontWeight=FontWeight.Bold, color=Ink)
                Text("Gestion ultimos 30 dias", color=Muted, style=MaterialTheme.typography.labelSmall)
            }
        },
        text = {
            LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp), modifier=Modifier.heightIn(max=560.dp)) {
                item {
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            SupervisorKpi("Venta total", clp(detalle.summary.ventaTotal), Icons.Default.Payments, Modifier.weight(1.35f), detalle.summary.ventaTotal > 0)
                            SupervisorKpi("Ticket prom.", clp(detalle.summary.ticketPromedio), Icons.Default.Analytics, Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            SupervisorKpi("Facturas", detalle.summary.facturas.toString(), Icons.Default.ReceiptLong, Modifier.weight(1f))
                            SupervisorKpi("Boletas", detalle.summary.boletas.toString(), Icons.Default.Receipt, Modifier.weight(1f))
                            SupervisorKpi("NC", detalle.summary.notasCredito.toString(), Icons.Default.AssignmentReturn, Modifier.weight(1f), detalle.summary.notasCredito > 0)
                        }
                        SupervisorOnlineTrendChart(detalle.trend)
                    }
                }
                item {
                    Text("Clientes con mayor venta", color=Ink, fontWeight=FontWeight.Bold)
                }
                if (detalle.clientes.isEmpty()) {
                    item { Text("Sin clientes para mostrar.", color=Muted) }
                } else {
                    items(detalle.clientes) { cliente ->
                        Surface(Modifier.fillMaxWidth(), shape=RoundedCornerShape(8.dp), color=Color(0xFFF7F8FA)) {
                            Row(Modifier.padding(10.dp), horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(cliente.clienteNombre ?: cliente.clienteRut ?: "-", color=Ink, fontWeight=FontWeight.SemiBold, maxLines=1)
                                    Text("${cliente.clienteRut ?: "-"} - Ultima ${fechaCl(cliente.ultimaFecha)} - Docs ${cliente.documentos}", color=Muted, style=MaterialTheme.typography.labelSmall, maxLines=1)
                                }
                                Text(clp(cliente.total), color=Purple, fontWeight=FontWeight.Bold, maxLines=1)
                            }
                        }
                    }
                }
                item {
                    Text("Productos del vendedor", color=Ink, fontWeight=FontWeight.Bold)
                }
                if (detalle.productos.isEmpty()) {
                    item { Text("Sin productos para mostrar.", color=Muted) }
                } else {
                    items(detalle.productos) { producto ->
                        SupervisorProductoRow(
                            titulo = producto.productoDescripcion ?: producto.productoCodigo ?: "-",
                            subtitulo = producto.productoCodigo ?: "-",
                            uxe = producto.uxeTotal,
                            total = producto.total
                        )
                    }
                }
            }
        },
        shape = RoundedCornerShape(8.dp),
        containerColor = Color.White
    )
}

@Composable fun SupervisorProductoRow(titulo: String, subtitulo: String, uxe: Double, total: Long) {
    Surface(Modifier.fillMaxWidth(), shape=RoundedCornerShape(8.dp), color=Color(0xFFF7F8FA)) {
        Row(Modifier.padding(10.dp), horizontalArrangement=Arrangement.spacedBy(10.dp), verticalAlignment=Alignment.CenterVertically) {
            Surface(shape=RoundedCornerShape(7.dp), color=Color.White) {
                Icon(Icons.Default.Inventory2, null, tint=Purple, modifier=Modifier.padding(8.dp).size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(titulo, color=Ink, fontWeight=FontWeight.SemiBold, maxLines=1)
                Text(subtitulo, color=Muted, style=MaterialTheme.typography.labelSmall, maxLines=1)
            }
            Column(horizontalAlignment=Alignment.End) {
                Text("${stockText(uxe)} UXE", color=Ink, fontWeight=FontWeight.Bold, maxLines=1)
                Text(clp(total), color=Purple, style=MaterialTheme.typography.labelSmall, fontWeight=FontWeight.Bold, maxLines=1)
            }
        }
    }
}

@Composable fun SupervisorKpi(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, alert: Boolean = false) {
    Surface(modifier, shape=RoundedCornerShape(8.dp), color=Color(0xFFF7F8FA)) {
        Column(Modifier.padding(10.dp), verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, tint=if(alert) Color.Red else Purple, modifier=Modifier.size(20.dp))
            Text(label, color=Muted, style=MaterialTheme.typography.labelSmall)
            Text(value, color=if(alert) Color.Red else Ink, fontWeight=FontWeight.Bold, maxLines=1)
        }
    }
}

@Composable fun SupervisorTrendChart(nvs: List<NvHeaderEntity>) {
    val days = (6 downTo 0).map { LocalDate.now().minusDays(it.toLong()) }
    val values = days.map { d -> nvs.filter { it.fecha.take(10) == d.toString() }.sumOf { it.total } }
    val maxValue = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("Ventas ultimos 7 dias", color=Ink, fontWeight=FontWeight.SemiBold, style=MaterialTheme.typography.bodySmall)
        Canvas(Modifier.fillMaxWidth().height(88.dp)) {
            val gap = 7.dp.toPx()
            val barWidth = (size.width - gap * (values.size - 1)) / values.size
            values.forEachIndexed { index, value ->
                val h = (value.toFloat() / maxValue.toFloat()) * size.height
                val left = index * (barWidth + gap)
                drawRect(
                    color=if(value > 0) Purple else Color(0xFFE5E7EB),
                    topLeft=androidx.compose.ui.geometry.Offset(left, size.height - h),
                    size=androidx.compose.ui.geometry.Size(barWidth, h.coerceAtLeast(4f))
                )
            }
        }
    }
}

@Composable fun SupervisorOnlineTrendChart(trend: List<SupervisorTrendDto>) {
    val values = trend.map { it.total }
    val maxValue = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("Ventas ultimos 7 dias", color=Ink, fontWeight=FontWeight.SemiBold, style=MaterialTheme.typography.bodySmall)
        Canvas(Modifier.fillMaxWidth().height(88.dp)) {
            val safeCount = values.size.coerceAtLeast(1)
            val gap = 7.dp.toPx()
            val barWidth = (size.width - gap * (safeCount - 1)) / safeCount
            values.forEachIndexed { index, value ->
                val h = (value.toFloat() / maxValue.toFloat()) * size.height
                val left = index * (barWidth + gap)
                drawRect(
                    color=if(value > 0) Purple else Color(0xFFE5E7EB),
                    topLeft=androidx.compose.ui.geometry.Offset(left, size.height - h),
                    size=androidx.compose.ui.geometry.Size(barWidth, h.coerceAtLeast(4f))
                )
            }
        }
        if (trend.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                trend.forEach { item ->
                    Text((item.fecha ?: "").takeLast(5), color=Muted, style=MaterialTheme.typography.labelSmall, modifier=Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable fun MainScreen(vm: MainVm) {
    var tab by remember { mutableStateOf(0) }
    val labels = listOf("Pedidos","Nueva NV","Día","Consulta","Sync")
    Scaffold(bottomBar={ NavigationBar { labels.forEachIndexed { i,l -> NavigationBarItem(selected=tab==i, onClick={tab=i}, icon={Icon(listOf(Icons.Default.List, Icons.Default.AddShoppingCart, Icons.Default.Today, Icons.Default.Search, Icons.Default.Sync)[i], null)}, label={Text(l)}) } } }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().background(SoftBg)) {
            when(tab){0->Pedidos(vm, onEdit={ vm.editNv(it); tab=1 }, onNew={ vm.nuevaNvDesdeCero(); tab=1 }, onDetail={ vm.abrirDetallePedido(it) }, onResumen={ vm.abrirResumenPedido(it) });1->NuevaNv(vm);2->Dia(vm);3->Consulta(vm);4->Sync(vm)}
            PedidoDetalleDialog(vm)
            ResumenNvDialog(vm, onEdit={ vm.editNv(it); vm.cerrarResumenPedido(); tab=1 })
        }
    }
}

@Composable fun MainScreen2(vm: MainVm) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var showNvCartQuick by remember { mutableStateOf(false) }
    val labels = listOf("Pedidos", "Nueva NV", "Dia", "Cta Cte", "Consulta", "Sync")
    val icons = listOf(Icons.Default.List, Icons.Default.AddShoppingCart, Icons.Default.Today, Icons.Default.AccountBalanceWallet, Icons.Default.Search, Icons.Default.Sync)
    Scaffold(
        containerColor = SoftBg,
        bottomBar={ RisekBottomBar(labels = labels, icons = icons, selected = tab, onSelect = { tab = it }) }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().background(SoftBg)) {
            if (tab == 1) {
                MockupRedTopBar(labels[tab], cartCount = vm.cart.size, cartTotal = vm.cart.sumOf { it.totalLinea }, onCartClick = { showNvCartQuick = true }, latestVersion = vm.latestAppVersion)
            } else {
                BrandTopBar(labels[tab], vm)
            }
            when(tab){
                0 -> Pedidos2(vm, onEdit={ vm.editNv(it); tab=1 }, onNew={ vm.nuevaNvDesdeCero(); tab=1 }, onDetail={ vm.abrirDetallePedido(it) }, onResumen={ vm.abrirResumenPedido(it) })
                1 -> NuevaNv2(vm, onSaved = { tab = 0 })
                2 -> Dia(vm)
                3 -> CuentaCorriente2(vm, onNuevaNv = { tab = 1 })
                4 -> Consulta2(vm)
                5 -> Sync2(vm)
            }
            PedidoDetalleDialog(vm)
            ResumenNvDialog(vm, onEdit={ vm.editNv(it); vm.cerrarResumenPedido(); tab=1 })
            UltimaVentaDialog(vm)
            ResumenClienteVozDialog(vm)
            ResumenClienteCandidatesDialog(vm)
            if (showNvCartQuick) QuickNvCartDialog(vm = vm, onDismiss = { showNvCartQuick = false })
        }
    }
    if (vm.showSyncReminder) {
        AlertDialog(
            onDismissRequest = { vm.cerrarAvisoSyncDiario() },
            icon = { Icon(Icons.Default.CloudDownload, contentDescription = null, tint = Purple) },
            title = { Text("Sincronizacion recomendada", fontWeight = FontWeight.Bold) },
            text = {
                Text("Los datos no se han actualizado hoy. Sincronice clientes, productos, precios y cuenta corriente antes de continuar con sus ventas.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.cerrarAvisoSyncDiario()
                        tab = 5
                        vm.fullSync(ctx)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Purple)
                ) {
                    Text("Sincronizar ahora")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.cerrarAvisoSyncDiario() }) {
                    Text("Ahora no")
                }
            }
        )
    }
}

@Composable fun RisekBottomBar(
    labels: List<String>,
    icons: List<androidx.compose.ui.graphics.vector.ImageVector>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Surface(color = Color(0xFF141820), tonalElevation = 12.dp) {
        Row(
            Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            labels.forEachIndexed { i, label ->
                val active = selected == i
                val isPrimary = i == 1
                val color = when {
                    isPrimary -> Color.White
                    active -> Purple
                    else -> Color(0xFFCBD5E1)
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable { onSelect(i) }.padding(vertical = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (isPrimary) {
                        Surface(
                            shape = CircleShape,
                            color = Purple,
                            shadowElevation = if (active) 10.dp else 5.dp,
                            modifier = Modifier.size(if (active) 44.dp else 40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    icons[i],
                                    contentDescription = label,
                                    tint = Color.White,
                                    modifier = Modifier.size(23.dp)
                                )
                            }
                        }
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            if (active) {
                                Box(Modifier.size(34.dp).background(Purple.copy(alpha = 0.16f), CircleShape))
                            }
                            Icon(
                                icons[i],
                                contentDescription = label,
                                tint = color,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(if (isPrimary) 3.dp else 5.dp))
                    Text(
                        label,
                        color = if (isPrimary && active) Purple else color,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (active || isPrimary) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable fun BrandTopBar(title: String, vm: MainVm) {
    Surface(color = Purple, tonalElevation = 4.dp) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Menu, null, tint = Color.White)
                Spacer(Modifier.width(12.dp))
                Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val latest = vm.latestAppVersion
                val hasUpdate = latest != null && latest.versionCode > BuildConfig.VERSION_CODE
                Text(
                    text = if (hasUpdate) "v${BuildConfig.VERSION_NAME} → ${latest.versionName ?: latest.versionCode}" else "v${BuildConfig.VERSION_NAME}",
                    color = if (hasUpdate) Color.Yellow else Color.White.copy(alpha = 0.88f),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (hasUpdate) FontWeight.Bold else FontWeight.Normal
                )
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Default.FilterList, null, tint = Color.White)
            }
        }
    }
}

@Composable fun MockupRedTopBar(title: String, cartCount: Int = 0, cartTotal: Long = 0L, onCartClick: (() -> Unit)? = null, latestVersion: AppVersionDto? = null) {
    Surface(color = Purple, tonalElevation = 4.dp) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                Spacer(Modifier.width(12.dp))
                Text("Total ${clp(cartTotal)}", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val hasUpdate = latestVersion != null && latestVersion.versionCode > BuildConfig.VERSION_CODE
                Text(
                    text = if (hasUpdate) "v${BuildConfig.VERSION_NAME} → ${latestVersion?.versionName ?: latestVersion?.versionCode}" else "v${BuildConfig.VERSION_NAME}",
                    color = if (hasUpdate) Color.Yellow else Color.White.copy(alpha = 0.88f),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (hasUpdate) FontWeight.Bold else FontWeight.Normal
                )
                Spacer(Modifier.width(10.dp))
                if (onCartClick != null) {
                    BadgedBox(
                        badge = {
                            if (cartCount > 0) Badge { Text(cartCount.toString()) }
                        }
                    ) {
                        IconButton(onClick = onCartClick) {
                            Icon(Icons.Default.ShoppingCart, contentDescription = "Carrito NV", tint = Color.White)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Icon(Icons.Default.FilterList, null, tint = Color.White)
            }
        }
    }
}

@Composable fun Header(vm: MainVm) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(onClick={vm.fullSync(ctx)}, enabled=!vm.isSyncing, shape=RoundedCornerShape(10.dp)) {
            Icon(Icons.Default.Sync,null)
            Spacer(Modifier.width(8.dp))
            Text(if (vm.isSyncing) "Sincronizando..." else "Sincronizar")
        }
    }
}


@Composable fun FlujoNv(vm: MainVm) {
    val clienteOk = vm.selectedCliente != null
    val direccionOk = !vm.selectedDireccion.isNullOrBlank()
    val productosOk = vm.cart.isNotEmpty()
    val resumenOk = clienteOk && direccionOk && productosOk
    Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(10.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) {
        Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Flujo NV", fontWeight=FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                StepChip("1 Cliente", clienteOk)
                StepChip("2 Dirección", direccionOk)
                StepChip("3 Productos", productosOk)
                StepChip("4 Resumen", resumenOk)
            }
            Text("Líneas: ${vm.cart.size}/$MAX_NV_LINES · Bodega ${Config.BODEGA_CODIGO}", color=if(vm.cart.size >= MAX_NV_LINES) Color.Red else Color.Gray, fontWeight=FontWeight.SemiBold)
        }
    }
}

@Composable fun StepChip(text: String, ok: Boolean) {
    AssistChip(
        onClick = {},
        label = { Text(text) },
        leadingIcon = { Icon(if(ok) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null, tint=if(ok) Purple else Color.Gray) }
    )
}

@Composable fun QuickNvCartDialog(vm: MainVm, onDismiss: () -> Unit) {
    val neto = vm.cart.sumOf { it.netoLinea }
    val iva = vm.cart.sumOf { it.ivaLinea }
    val ila = vm.cart.sumOf { it.ilaLinea }
    val total = vm.cart.sumOf { it.totalLinea }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Carrito NV actual", fontWeight=FontWeight.Bold) },
        text = {
            if (vm.cart.isEmpty()) {
                Text("Sin productos agregados.", color=Muted)
            } else {
                LazyColumn(Modifier.heightIn(max=430.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    items(vm.cart) { l ->
                        Card(Modifier.fillMaxWidth(), colors=CardDefaults.cardColors(containerColor=Color(0xFFF7F8FA)), shape=RoundedCornerShape(6.dp)) {
                            Row(Modifier.padding(10.dp), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(l.descripcion ?: l.productoCodigo, fontWeight=FontWeight.SemiBold, maxLines=1)
                                    Text("${l.productoCodigo} - UXE ${stockText(l.uxe)} - Cant ${stockText(l.cantidad)} - Desc ${stockText(l.descuento)}%", color=Muted, style=MaterialTheme.typography.bodySmall)
                                }
                                Text(clp(l.totalLinea), color=Purple, fontWeight=FontWeight.Bold)
                            }
                        }
                    }
                    item {
                        Divider()
                        CountMoney("Neto", neto)
                        CountMoney("IVA", iva)
                        if (ila > 0) CountMoney("ILA", ila)
                        CountMoney("Total", total, true)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick=onDismiss, colors=ButtonDefaults.buttonColors(containerColor=Purple)) { Text("Volver") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun FechaRepartoPicker(vm: MainVm) {
    var show by remember { mutableStateOf(false) }
    val currentDate = runCatching { LocalDate.parse(vm.fechaReparto) }.getOrDefault(LocalDate.now().plusDays(1))
    val initialMillis = datePickerMillis(currentDate)
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Fecha de reparto", fontWeight=FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                Text(fechaCl(vm.fechaReparto), color=Ink, fontWeight=FontWeight.SemiBold)
                OutlinedButton(onClick = { show = true }) { Icon(Icons.Default.DateRange, null); Spacer(Modifier.width(8.dp)); Text("Elegir") }
            }
        }
    }
    if (show) {
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = datePickerState.selectedDateMillis ?: initialMillis
                    val selected = datePickerLocalDate(millis)
                    vm.cambiarFechaReparto(selected)
                    show = false
                }) { Text("Aceptar") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancelar") } }
        ) { DatePicker(state = datePickerState) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NuevaNv(vm: MainVm) {
    val ctx = LocalContext.current
    val familias by vm.familias.collectAsState()
    var qCliente by remember { mutableStateOf("") }; var qProd by remember { mutableStateOf("") }; var exactCode by remember { mutableStateOf(true) }
    var familiaExpanded by remember { mutableStateOf(false) }
    var uxe by remember { mutableStateOf("") }; var cajas by remember { mutableStateOf("") }; var descuento by remember { mutableStateOf("") }
    var showLineLimitDialog by remember { mutableStateOf(false) }
    LaunchedEffect(qProd, exactCode, vm.selectedFamilia) {
        if (!exactCode) kotlinx.coroutines.delay(350)
        vm.searchProductos(qProd, exactCode)
    }

    if (showLineLimitDialog) {
        AlertDialog(
            onDismissRequest = { showLineLimitDialog = false },
            confirmButton = { TextButton(onClick = { showLineLimitDialog = false }) { Text("Entendido") } },
            title = { Text("Tope de líneas alcanzado") },
            text = { Text("Cada NV puede tener máximo $MAX_NV_LINES líneas. Para agregar más productos debe guardar esta NV y crear una nueva.") }
        )
    }

    LazyColumn(Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                    Text(if(vm.editOfflineId == null) "Nueva Nota de Venta" else "Modificar NV local", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold)
                    OutlinedButton(onClick={vm.nuevaNvDesdeCero()}) { Icon(Icons.Default.Add,null); Spacer(Modifier.width(6.dp)); Text("Crear desde cero") }
                }
                OutlinedButton(onClick={vm.salirLogin()}, modifier=Modifier.fillMaxWidth(), shape=RoundedCornerShape(8.dp), colors=ButtonDefaults.outlinedButtonColors(contentColor=Purple)) {
                    Icon(Icons.Default.Logout, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Salir al Login", fontWeight=FontWeight.Bold)
                }
            }
        }
        item { FechaRepartoPicker(vm) }
        if (vm.selectedCliente == null) {
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) { OutlinedTextField(qCliente, { qCliente=it; vm.searchClientes(it) }, label={Text("Buscar cliente por nombre o RUT")}, modifier=Modifier.weight(1f), singleLine=true); Button(onClick={vm.searchClientes(qCliente)}) { Text("Buscar") } } }
            items(vm.clientes) { c ->
                Card(Modifier.fillMaxWidth().clickable{vm.selectCliente(c)}, colors=CardDefaults.cardColors(containerColor= if(c.clienteEstado=="B") DangerBg else Color.White)) {
                    Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) { Text(c.clienteNombre, fontWeight=FontWeight.Bold); Text("${c.clienteRut} · Ruta ${c.rutaId ?: "-"} · Lista ${c.listaCodigo ?: "-"}"); if(c.clienteEstado=="B") Text("CLIENTE BLOQUEADO - venta no permitida", color=Color.Red, fontWeight=FontWeight.Bold); OutlinedButton(onClick={vm.selectCliente(c)}, modifier=Modifier.fillMaxWidth()) { Text("Seleccionar cliente") } }
                }
            }
        }
        item { ClienteSeleccionado(vm) }
        item { Direcciones(vm) }
        item { ObservacionNv(vm) }
        item { UltimasNvCliente(vm) }
        item {
            Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("Productos", fontWeight=FontWeight.Bold)
                    ExposedDropdownMenuBox(expanded = familiaExpanded, onExpandedChange = { familiaExpanded = !familiaExpanded }) {
                        OutlinedTextField(
                            value = vm.selectedFamilia?.let { "${it.familiaCodigo} · ${it.familiaDescripcion ?: ""}" } ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Familia") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = familiaExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            singleLine = true
                        )
                        ExposedDropdownMenu(expanded = familiaExpanded, onDismissRequest = { familiaExpanded = false }) {
                            DropdownMenuItem(text = { Text("Todas / búsqueda manual") }, onClick = { vm.selectFamilia(null); familiaExpanded = false })
                            familias.forEach { f ->
                                DropdownMenuItem(
                                    text = { Text("${f.familiaCodigo} · ${f.familiaDescripcion ?: ""}") },
                                    onClick = { vm.selectFamilia(f); familiaExpanded = false }
                                )
                            }
                        }
                    }
                    Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Checkbox(checked=exactCode, onCheckedChange={ exactCode=it })
                        Text("Código exacto")
                    }
                    OutlinedTextField(qProd, { qProd=it }, label={Text(if(exactCode) "Código exacto" else "Descripción, código, familia código o familia descripción")}, modifier=Modifier.fillMaxWidth(), singleLine=true)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(uxe, { uxe=it }, label={Text("UXE")}, modifier=Modifier.weight(1f), singleLine=true)
                        OutlinedTextField(cajas, { cajas=it }, label={Text("Cajas")}, modifier=Modifier.weight(1f), singleLine=true)
                        OutlinedTextField(descuento, { descuento=it }, label={Text("Desc %")}, modifier=Modifier.weight(1f), singleLine=true)
                    }
                    if (vm.selectedFamilia != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                            OutlinedButton(onClick={vm.prevProductoPage()}, enabled=vm.productoPage > 0) { Text("Anterior") }
                            Text("Página ${vm.productoPage + 1} · ${vm.productos.size} productos", color=Color.Gray)
                            OutlinedButton(onClick={vm.nextProductoPage()}) { Text("Siguiente") }
                        }
                    }
                }
            }
        }
        items(vm.productos) { p ->
            val isNewLine = vm.cart.none { it.productoCodigo == p.productoCodigo }
            val blockedByLineLimit = isNewLine && vm.cart.size >= MAX_NV_LINES
            Card(
                Modifier.fillMaxWidth().clickable {
                    if (blockedByLineLimit) showLineLimitDialog = true else vm.addProduct(p, uxe, descuento, cajas)
                },
                colors = CardDefaults.cardColors(containerColor = if (blockedByLineLimit) Color(0xFFF3F4F6) else Color.White)
            ) {
                Row(Modifier.padding(12.dp), horizontalArrangement=Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { Text(p.productoDescripcion ?: p.productoCodigo, fontWeight=FontWeight.Bold); Text("${p.productoCodigo} · ${p.familiaCodigo ?: "-"} ${p.familiaDescripcion ?: ""} · UE ${p.productoUnidadEnvase ?: 0.0}"); if (blockedByLineLimit) Text("Tope $MAX_NV_LINES líneas alcanzado", color=Color.Red, fontWeight=FontWeight.Bold) }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Stock ${stockConsultaText(p.stockActual)}", color=if (p.stockActual > 0.0) Ink else Color.Red, fontWeight=FontWeight.SemiBold)
                        Text("Desc max ${stockText(p.productoDescuento ?: 0.0)}%", color=Muted, style=MaterialTheme.typography.bodySmall)
                        val precioLista = vm.productListPrices[p.productoCodigo] ?: 0L
                        Text(if (precioLista > 0L) clp(precioLista) else "Sin precio L01", color=Purple, fontWeight=FontWeight.Bold)
                        Text("Venta permitida", color=Color.Gray)
                    }
                }
            }
        }
        item { Cart(vm, onCreate={vm.createNv(ctx)}) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NuevaNv2(vm: MainVm, onSaved: () -> Unit = {}) {
    val ctx = LocalContext.current
    val familias by vm.familias.collectAsState()
    var qCliente by remember { mutableStateOf("") }
    var qProd by remember { mutableStateOf("") }
    var exactCode by remember { mutableStateOf(true) }
    var familiaExpanded by remember { mutableStateOf(false) }
    var uxe by remember { mutableStateOf("") }
    var cajas by remember { mutableStateOf("") }
    var descuento by remember { mutableStateOf("") }
    var showFecha by remember { mutableStateOf(false) }
    val currentDate = runCatching { LocalDate.parse(vm.fechaReparto) }.getOrDefault(LocalDate.now().plusDays(1))
    val initialMillis = datePickerMillis(currentDate)
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    val neto = vm.cart.sumOf { it.netoLinea }
    val iva = vm.cart.sumOf { it.ivaLinea }
    val ila = vm.cart.sumOf { it.ilaLinea }
    val total = vm.cart.sumOf { it.totalLinea }
    LaunchedEffect(qProd, exactCode, vm.selectedFamilia) {
        if (!exactCode) kotlinx.coroutines.delay(350)
        vm.searchProductos(qProd, exactCode)
    }
    fun clearProductEntry() {
        qProd = ""
        exactCode = true
        uxe = ""
        cajas = ""
        descuento = ""
        vm.selectFamilia(null)
    }
    LazyColumn(
        Modifier.fillMaxSize().background(Color(0xFFF7F8FA)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 18.dp)
    ) {
        item {
            Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(8.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) {
                Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Cliente", color=Muted, style=MaterialTheme.typography.labelMedium)
                    if (vm.selectedCliente == null) {
                        OutlinedTextField(
                            qCliente,
                            { qCliente=it; vm.searchClientes(it) },
                            label={Text("Buscar cliente por nombre o RUT")},
                            leadingIcon={Icon(Icons.Default.Search, null, tint=Muted)},
                            modifier=Modifier.fillMaxWidth(),
                            singleLine=true,
                            shape=RoundedCornerShape(6.dp)
                        )
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(vm.selectedCliente?.clienteNombre ?: "-", fontWeight=FontWeight.Bold)
                                Text("RUT: ${vm.selectedCliente?.clienteRut ?: "-"}", color=Muted)
                            }
                            if (vm.selectedCliente?.clienteEstado == "B") {
                                IconButton(onClick={ vm.selectedCliente?.let { vm.syncClienteBloqueado(it) } }, enabled=!vm.isSyncing) {
                                    Icon(Icons.Default.Sync, null, tint=Purple)
                                }
                            }
                        }
                        Text("Lista: ${vm.selectedCliente?.listaCodigo ?: "-"}", color=Muted)
                        if (vm.selectedCliente?.clienteEstado == "B") Text("CLIENTE BLOQUEADO", color=Color.Red, fontWeight=FontWeight.Bold)
                        OutlinedButton(onClick={vm.nuevaNvDesdeCero()}, modifier=Modifier.fillMaxWidth()) { Text("Cambiar cliente") }
                    }
                }
            }
        }
        if (vm.selectedCliente == null) {
            items(vm.clientes) { c ->
                Card(Modifier.fillMaxWidth().clickable{vm.selectCliente(c)}, shape=RoundedCornerShape(8.dp), colors=CardDefaults.cardColors(containerColor= if(c.clienteEstado=="B") DangerBg else Color.White)) {
                    Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                            Text(c.clienteNombre, fontWeight=FontWeight.Bold, modifier=Modifier.weight(1f))
                            if(c.clienteEstado=="B") {
                                IconButton(onClick={vm.syncClienteBloqueado(c)}, enabled=!vm.isSyncing) {
                                    Icon(Icons.Default.Sync, null, tint=Purple)
                                }
                            }
                        }
                        Text("${c.clienteRut} · Ruta ${c.rutaId ?: "-"} · Lista ${c.listaCodigo ?: "-"}", color=Muted)
                        if(c.clienteEstado=="B") Text("CLIENTE BLOQUEADO", color=Color.Red, fontWeight=FontWeight.Bold)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Card(Modifier.weight(1f), shape=RoundedCornerShape(8.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) {
                    Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text("Fecha", color=Muted, style=MaterialTheme.typography.labelMedium)
                        Text(fechaCl(LocalDate.now().toString()), fontWeight=FontWeight.Bold)
                    }
                }
                Card(Modifier.weight(1f), shape=RoundedCornerShape(8.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) {
                    Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text("Fecha Reparto", color=Muted, style=MaterialTheme.typography.labelMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                            Text(fechaCl(vm.fechaReparto), fontWeight=FontWeight.Bold)
                            IconButton(onClick = { showFecha = true }) { Icon(Icons.Default.DateRange, null, tint=Purple) }
                        }
                    }
                }
            }
        }
        item { Direcciones(vm) }
        item {
            Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(8.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) {
                Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Producto", fontWeight=FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
                        ExposedDropdownMenuBox(expanded = familiaExpanded, onExpandedChange = { familiaExpanded = !familiaExpanded }, modifier=Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = vm.selectedFamilia?.let { "${it.familiaCodigo} · ${it.familiaDescripcion ?: ""}" } ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Familia") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = familiaExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            singleLine = true,
                            shape=RoundedCornerShape(6.dp)
                        )
                        ExposedDropdownMenu(expanded = familiaExpanded, onDismissRequest = { familiaExpanded = false }) {
                            DropdownMenuItem(text = { Text("Todas / búsqueda manual") }, onClick = { vm.selectFamilia(null); familiaExpanded = false })
                            familias.forEach { f -> DropdownMenuItem(text = { Text("${f.familiaCodigo} · ${f.familiaDescripcion ?: ""}") }, onClick = { vm.selectFamilia(f); familiaExpanded = false }) }
                        }
                        }
                        OutlinedButton(onClick={ clearProductEntry() }, shape=RoundedCornerShape(6.dp), contentPadding=PaddingValues(horizontal=10.dp, vertical=14.dp)) {
                            Icon(Icons.Default.CleaningServices, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Limpiar")
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
                        OutlinedTextField(qProd, { qProd=it }, label={Text(if(exactCode) "Código exacto" else "Buscar producto")}, leadingIcon={Icon(Icons.Default.Search, null, tint=Muted)}, modifier=Modifier.weight(1f), singleLine=true, shape=RoundedCornerShape(6.dp))
                    }
                    Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(exactCode, { exactCode=it }); Text("Código exacto") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(uxe, { uxe=it }, label={Text("UXE")}, modifier=Modifier.weight(1f), singleLine=true, shape=RoundedCornerShape(6.dp))
                        OutlinedTextField(cajas, { cajas=it }, label={Text("Cajas")}, modifier=Modifier.weight(1f), singleLine=true, shape=RoundedCornerShape(6.dp))
                        OutlinedTextField(descuento, { descuento=it }, label={Text("Desc %")}, modifier=Modifier.weight(1f), singleLine=true, shape=RoundedCornerShape(6.dp))
                    }
                    if (vm.message.isNotBlank()) {
                        Text(
                            vm.message,
                            color=if (vm.message.contains("no ", true) || vm.message.contains("sin ", true) || vm.message.contains("bloqueado", true) || vm.message.contains("seleccione", true)) Color.Red else Muted,
                            style=MaterialTheme.typography.bodySmall,
                            fontWeight=if (vm.message.contains("agregado", true)) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                    if (vm.selectedFamilia != null) {
                        val desde = if (vm.productos.isEmpty()) 0 else vm.productoPage * vm.productoPageSize + 1
                        val hasta = (vm.productoPage * vm.productoPageSize + vm.productos.size).coerceAtMost(vm.productoFamiliaTotal)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFFF7F8FA),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "Mostrando $desde-$hasta de ${vm.productoFamiliaTotal} productos",
                                    color = Ink,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                                    OutlinedButton(onClick={vm.prevProductoPage()}, enabled=vm.productoPage > 0, shape=RoundedCornerShape(6.dp)) { Text("Anterior") }
                                    Text("Pagina ${vm.productoPage + 1}", color=Muted, style=MaterialTheme.typography.bodySmall)
                                    OutlinedButton(
                                        onClick={vm.nextProductoPage()},
                                        enabled=hasta < vm.productoFamiliaTotal,
                                        shape=RoundedCornerShape(6.dp)
                                    ) { Text("Siguiente") }
                                }
                            }
                        }
                    }
                }
            }
        }
        itemsIndexed(vm.productos) { index, p ->
            val currentFamily = "${p.familiaCodigo ?: ""}|${p.familiaDescripcion ?: ""}"
            val previousFamily = vm.productos.getOrNull(index - 1)?.let { "${it.familiaCodigo ?: ""}|${it.familiaDescripcion ?: ""}" }
            if (currentFamily != previousFamily) {
                Surface(
                    modifier=Modifier.fillMaxWidth(),
                    color=Color(0xFFFFEEF0),
                    shape=RoundedCornerShape(6.dp)
                ) {
                    Text(
                        "${p.familiaCodigo ?: "-"} ${p.familiaDescripcion ?: "Sin familia"}",
                        modifier=Modifier.padding(horizontal=10.dp, vertical=6.dp),
                        color=Purple,
                        fontWeight=FontWeight.Bold,
                        style=MaterialTheme.typography.bodySmall
                    )
                }
            }
            var lineUxe by remember(p.productoCodigo, uxe) { mutableStateOf(uxe) }
            var lineCajas by remember(p.productoCodigo, cajas) { mutableStateOf(cajas) }
            var lineDesc by remember(p.productoCodigo, descuento) { mutableStateOf(descuento) }
            val alreadyAdded = vm.cart.any { it.productoCodigo == p.productoCodigo }
            Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(8.dp), colors=CardDefaults.cardColors(containerColor=if (alreadyAdded) Color(0xFFE8F5E9) else Color.White)) {
                Column(Modifier.padding(10.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                Text(p.productoCodigo, fontWeight=FontWeight.Bold)
                                if (alreadyAdded) {
                                    Surface(color=Color(0xFFDCFCE7), shape=RoundedCornerShape(4.dp)) {
                                        Text("Agregado", color=Color(0xFF15803D), fontWeight=FontWeight.Bold, style=MaterialTheme.typography.labelSmall, modifier=Modifier.padding(horizontal=6.dp, vertical=2.dp))
                                    }
                                }
                            }
                            Text(p.productoDescripcion ?: "-", color=Ink)
                            Text("Stock: ${stockConsultaText(p.stockActual)} - UE ${stockText(p.productoUnidadEnvase ?: 0.0)}", color=if(p.stockActual > 0) Color(0xFF15803D) else Color.Red, style=MaterialTheme.typography.bodySmall)
                        }
                        val precioLista = vm.productListPrices[p.productoCodigo] ?: 0L
                        Text(if (precioLista > 0L) clp(precioLista) else "Sin precio L01", fontWeight=FontWeight.Bold, color=Purple)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(6.dp), verticalAlignment=Alignment.CenterVertically) {
                        OutlinedTextField(lineUxe, { lineUxe=it }, label={Text("UXE")}, modifier=Modifier.weight(.9f), singleLine=true, shape=RoundedCornerShape(6.dp))
                        OutlinedTextField(lineCajas, { lineCajas=it }, label={Text("Cajas")}, modifier=Modifier.weight(.9f), singleLine=true, shape=RoundedCornerShape(6.dp))
                        OutlinedTextField(lineDesc, { lineDesc=it }, label={Text("Desc max ${stockText(p.productoDescuento ?: 0.0)}%", style=MaterialTheme.typography.labelSmall)}, modifier=Modifier.weight(.9f), singleLine=true, shape=RoundedCornerShape(6.dp))
                        Button(
                            onClick={ vm.addProduct(p, lineUxe, lineDesc, lineCajas) },
                            shape=RoundedCornerShape(6.dp),
                            contentPadding=PaddingValues(horizontal=10.dp, vertical=12.dp),
                            colors=ButtonDefaults.buttonColors(containerColor=Purple)
                        ) {
                            Icon(Icons.Default.AddShoppingCart, null)
                            Spacer(Modifier.width(4.dp))
                            Text(if (alreadyAdded) "Actualizar" else "Agregar")
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(8.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) {
                Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    if (vm.cart.isEmpty()) Text("Sin productos agregados", color=Muted)
                    vm.cart.forEach { l ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(l.productoCodigo, fontWeight=FontWeight.Bold)
                                Text(l.descripcion ?: "-", color=Muted)
                                Text("UXE ${stockText(l.uxe)} - Cant ${stockText(l.cantidad)} - Neto unit. ${clp(l.precio)} - Desc ${stockText(l.descuento)}%", color=Ink, fontWeight=FontWeight.SemiBold)
                            }
                            Text(clp(l.totalLinea), color=Purple, fontWeight=FontWeight.Bold)
                            IconButton(onClick={vm.removeProduct(l.productoCodigo)}) { Icon(Icons.Default.Delete, null, tint=Color.Red) }
                        }
                    }
                    Divider()
                    CountMoney("Neto", neto)
                    CountMoney("IVA (19%)", iva)
                    if (ila > 0) CountMoney("ILA", ila)
                    CountMoney("Total", total, true)
                    Button(onClick={vm.createNv(ctx, onSaved)}, modifier=Modifier.fillMaxWidth().height(48.dp), shape=RoundedCornerShape(6.dp), colors=ButtonDefaults.buttonColors(containerColor=Purple)) {
                        Text("GUARDAR NV", fontWeight=FontWeight.Bold)
                    }
                }
            }
        }
    }
    if (showFecha) {
        DatePickerDialog(
            onDismissRequest = { showFecha = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = datePickerState.selectedDateMillis ?: initialMillis
                    val selected = datePickerLocalDate(millis)
                    vm.cambiarFechaReparto(selected)
                    showFecha = false
                }) { Text("Aceptar") }
            },
            dismissButton = { TextButton(onClick = { showFecha = false }) { Text("Cancelar") } }
        ) { DatePicker(state = datePickerState) }
    }
}

fun openClientMap(context: Context, geo: String?) {
    if (geo.isNullOrBlank()) return
    val clean = geo.trim().replace(";", ",")
    val uri = Uri.parse("geo:$clean?q=$clean")
    val intent = Intent(Intent.ACTION_VIEW, uri)
    context.startActivity(intent)
}

fun openLogisticaMap(context: Context, geo: String?, direccion: String?, comuna: String?) {
    val address = listOfNotNull(direccion, comuna, "Chile").filter { it.isNotBlank() }.joinToString(", ")
    val cleanGeo = geo?.trim()?.replace(";", ",").orEmpty()
    val looksLikeLatLng = Regex("""^-?\d+(\.\d+)?\s*,\s*-?\d+(\.\d+)?$""").matches(cleanGeo)
    val target = when {
        address.isNotBlank() -> address
        looksLikeLatLng -> cleanGeo
        else -> cleanGeo
    }
    if (target.isBlank()) return
    val encoded = Uri.encode(target)
    val uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$encoded")
    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
}

@Composable fun ClienteSeleccionado(vm: MainVm) {
    val c = vm.selectedCliente
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth(), colors=CardDefaults.cardColors(containerColor= if(c?.clienteEstado == "B") DangerBg else SuccessBg)) {
        Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Cliente seleccionado", fontWeight=FontWeight.Bold)
                    Text(c?.clienteNombre ?: "Sin seleccionar")
                    if (c != null) Text("RUT ${c.clienteRut} · Condición ${c.clienteVendedor ?: "-"}", color=Color.Gray)
                }
                c?.let { cliente ->
                    if (cliente.clienteEstado == "B") {
                        IconButton(onClick={ vm.syncClienteBloqueado(cliente) }, enabled=!vm.isSyncing) { Icon(Icons.Default.Sync, contentDescription="Actualizar cliente", tint=Purple) }
                    }
                }
                if (!c?.clienteGeo.isNullOrBlank()) {
                    IconButton(onClick={ openClientMap(ctx, c?.clienteGeo) }) { Icon(Icons.Default.LocationOn, contentDescription="Ver mapa", tint=Purple) }
                }
            }
            if(c?.clienteEstado == "B") Text("BLOQUEADO: no se permite guardar NV", color=Color.Red, fontWeight=FontWeight.Bold)
            if(c != null) OutlinedButton(onClick={vm.nuevaNvDesdeCero()}, modifier=Modifier.fillMaxWidth()) { Text("Cambiar cliente") }
        }
    }
}

@Composable fun Direcciones(vm: MainVm) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Dirección de reparto", fontWeight=FontWeight.Bold)
        if(vm.selectedCliente == null) Text("Seleccione cliente para cargar direcciones", color=Color.Gray)
        else if(vm.direcciones.isEmpty()) Text("Sin direcciones sincronizadas para este cliente. Ejecute Sync → Descargar datos.", color=Color.Red)
        else Text("${vm.direcciones.size} dirección(es) disponibles", color=Color.Gray)
        vm.direcciones.forEachIndexed { idx, d ->
            Row(Modifier.fillMaxWidth().clickable { vm.selectDireccion(d.direccion) }, verticalAlignment=Alignment.CenterVertically) {
                RadioButton(selected=vm.selectedDireccion == d.direccion, onClick={vm.selectDireccion(d.direccion)})
                Column(Modifier.weight(1f)) {
                    Text("Dirección ${idx + 1}", fontWeight=FontWeight.SemiBold)
                    Text(d.direccion + listOfNotNull(d.comuna, d.ciudadCodigo).joinToString(prefix=" · ", separator=" · "))
                }
            }
        }
    } }
}

@Composable fun ObservacionNv(vm: MainVm) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Observación NV", fontWeight=FontWeight.Bold)
            OutlinedTextField(
                value = vm.observacionNv,
                onValueChange = { vm.cambiarObservacion(it) },
                label = { Text("Observación comercial o de reparto") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )
        }
    }
}

@Composable fun UltimasNvCliente(vm: MainVm) {
    val c = vm.selectedCliente ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={vm.cargarUltimasNvCliente()}, modifier=Modifier.weight(1f)) {
                    Icon(Icons.Default.History, null); Spacer(Modifier.width(6.dp)); Text("Últimas 7 NV")
                }
                OutlinedButton(onClick={vm.cargarCuentaCorriente()}, modifier=Modifier.weight(1f)) {
                    Icon(Icons.Default.AccountBalanceWallet, null); Spacer(Modifier.width(6.dp)); Text("Cta. Cte")
                }
            }
            vm.cuentaCorriente?.let { cc ->
                Text("Saldo Cta. Cte: ${clp(cc.saldo)} · Docs: ${cc.documentos}", color=if(cc.saldo > 0) Color.Red else Ink, fontWeight=FontWeight.Bold)
            }
            if (vm.ultimasNvCliente.isNotEmpty()) {
                Text("Últimas NV online de ${c.clienteNombre}", fontWeight=FontWeight.Bold)
                vm.ultimasNvCliente.forEach { p ->
                    val fact = p.ventaFacturado == "S"
                    Column(Modifier.fillMaxWidth().background(if(fact) Color(0xFFE5E7EB) else Color.White).padding(8.dp)) {
                        Text("NV ${p.ventaNumero} · ${p.ventaFecha ?: "-"} · ${clp(p.ventaTotalVenta ?: 0)}", fontWeight=FontWeight.SemiBold)
                        Text("Neto ${clp(p.ventaNeto ?: 0)} · IVA ${clp(p.ventaIva ?: 0)} · Facturada ${p.ventaFacturado ?: "N"}", color=Color.Gray)
                        if (fact) Text("Facturada: solo lectura", color=Color.DarkGray, fontWeight=FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable fun Cart(vm: MainVm, onCreate:()->Unit) {
    Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(12.dp)) { Column(Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Default.ShoppingCart, null, tint=Purple)
                Spacer(Modifier.width(8.dp))
                Text("Resumen venta", fontWeight=FontWeight.Bold)
            }
            Text("${vm.cart.size}/$MAX_NV_LINES líneas · Bodega ${Config.BODEGA_CODIGO}", color=if(vm.cart.size >= MAX_NV_LINES) Color.Red else Color.Gray, fontWeight=FontWeight.SemiBold)
        }
        if (vm.cart.size >= MAX_NV_LINES) Text("Tope máximo alcanzado. No se pueden agregar más líneas a esta NV.", color=Color.Red, fontWeight=FontWeight.Bold)
        vm.cart.forEach { l ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(l.descripcion ?: l.productoCodigo, fontWeight=FontWeight.SemiBold); Text("UXE ${stockText(l.uxe)} · Cant ${stockText(l.cantidad)} · Bod ${l.bodegaCodigo} · Neto ${clp(l.netoLinea)} · IVA ${clp(l.ivaLinea)}${if (l.ilaLinea > 0) " · ILA ${clp(l.ilaLinea)}" else ""} · Total ${clp(l.totalLinea)}") }
                Text("UXE ${stockText(l.uxe)} - Cant ${stockText(l.cantidad)} - Neto unit. ${clp(l.precio)} - Desc ${stockText(l.descuento)}%", color=Ink, fontWeight=FontWeight.SemiBold)
                IconButton(onClick={vm.removeProduct(l.productoCodigo)}) { Icon(Icons.Default.Delete, null, tint=Color.Red) }
            }
        }
        val neto = vm.cart.sumOf{it.netoLinea}; val iva = vm.cart.sumOf{it.ivaLinea}; val ila = vm.cart.sumOf{it.ilaLinea}; val total = vm.cart.sumOf{it.totalLinea}
        Divider(); CountMoney("Neto", neto); CountMoney("IVA 19%", iva); if (ila > 0) CountMoney("ILA", ila); CountMoney("TOTAL", total, true)
        Button(onClick=onCreate, modifier=Modifier.fillMaxWidth()) { Text(if(vm.editOfflineId == null) "Guardar NV local y sincronizar" else "Guardar cambios NV") }
    } }
}

@Composable fun Pedidos(vm: MainVm, onEdit:(NvHeaderEntity)->Unit, onNew:()->Unit, onDetail:(NvHeaderEntity)->Unit, onResumen:(NvHeaderEntity)->Unit) {
    val ctx = LocalContext.current
    val nvs by vm.nvs.collectAsState()
    val limite = remember { LocalDate.now().minusDays(7).toString() }
    val ultimos = nvs.filter { it.fecha >= limite }.sortedByDescending { it.createdAt }
    LazyColumn(Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item{
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                Text("Pedidos últimos 7 días", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold)
                Button(onClick=onNew) { Icon(Icons.Default.AddShoppingCart,null); Spacer(Modifier.width(6.dp)); Text("Crear NV") }
            }
        }
        if (ultimos.isEmpty()) item { Text("No hay NV locales de los últimos 7 días. Cree una NV o sincronice datos.", color=Color.Gray) }
        items(ultimos){ h ->
            val bloqueada = h.facturado == "S"
            Card(colors=CardDefaults.cardColors(containerColor= if(h.facturado=="S") Color(0xFFE5E7EB) else Color.White), modifier=Modifier.fillMaxWidth().clickable{ onDetail(h) }) {
                Column(Modifier.padding(horizontal=12.dp, vertical=10.dp), verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement=Arrangement.SpaceBetween, modifier=Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                        Text(h.clienteNombre, fontWeight=FontWeight.Bold, modifier=Modifier.weight(1f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(clp(h.total), color=Purple, fontWeight=FontWeight.Bold)
                            if (h.facturado != "S") {
                                IconButton(onClick = { vm.eliminarNv(h, ctx) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Eliminar pedido", tint = Color.Red)
                                }
                            }
                        }
                    }
                    Text("Pedido ${fechaCl(h.fecha)} · Reparto ${fechaCl(h.fechaReparto)} · ${h.syncStatus}", color=Color.DarkGray)
                    Text("Dir: ${h.direccion ?: "-"}", color=Color.Gray)
                    Text("Neto ${clp(h.neto)} · IVA ${clp(h.iva)} · Bod ${h.bodegaCodigo} · Servidor ${h.ventaNumeroServidor ?: "pendiente"}", color=Color.Gray)
                    if(h.facturado == "S") Text("FACTURADO: no se puede modificar", color=Color.DarkGray, fontWeight=FontWeight.Bold)
                    if(!h.observacion.isNullOrBlank()) Text("Obs: ${h.observacion}", color=Color.Gray)
                    if(h.lastError != null) Text("Error sync: ${h.lastError}", color=Color.Red, fontWeight=FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick={onResumen(h)}, modifier=Modifier.weight(1f), contentPadding=PaddingValues(horizontal=8.dp, vertical=6.dp)) { Text("🧾 Resumen") }
                        OutlinedButton(onClick={onDetail(h)}, modifier=Modifier.weight(1f), contentPadding=PaddingValues(horizontal=8.dp, vertical=6.dp)) { Icon(Icons.Default.Visibility,null); Spacer(Modifier.width(4.dp)); Text("Ver") }
                        OutlinedButton(onClick={vm.abrirPdf(ctx, h)}, modifier=Modifier.weight(1f), contentPadding=PaddingValues(horizontal=8.dp, vertical=6.dp)) { Icon(Icons.Default.PictureAsPdf,null); Spacer(Modifier.width(4.dp)); Text("PDF") }
                    }
                    if(!bloqueada) OutlinedButton(onClick={onEdit(h)}, modifier=Modifier.fillMaxWidth(), contentPadding=PaddingValues(vertical=6.dp)) { Icon(Icons.Default.Edit,null); Spacer(Modifier.width(6.dp)); Text("Editar NV") }
                    if(h.facturado != "S") OutlinedButton(onClick={vm.eliminarNv(h, ctx)}, modifier=Modifier.fillMaxWidth(), contentPadding=PaddingValues(vertical=6.dp), colors=ButtonDefaults.outlinedButtonColors(contentColor=Color.Red)) { Icon(Icons.Default.Delete,null); Spacer(Modifier.width(6.dp)); Text(if(h.ventaNumeroServidor != null || h.syncStatus == "SINCRONIZADO") "Eliminar en servidor" else "Eliminar local") }
                }
            }
        }
    }
}


@Composable fun Pedidos2(vm: MainVm, onEdit:(NvHeaderEntity)->Unit, onNew:()->Unit, onDetail:(NvHeaderEntity)->Unit, onResumen:(NvHeaderEntity)->Unit) {
    val ctx = LocalContext.current
    val nvs by vm.nvs.collectAsState()
    var q by remember { mutableStateOf("") }
    val limite = remember { LocalDate.now().minusDays(7).toString() }
    val ultimos = nvs
        .filter { it.fecha >= limite }
        .filter { q.isBlank() || it.clienteNombre.contains(q, true) || it.clienteRut.contains(q, true) || (it.ventaNumeroServidor?.toString()?.contains(q) == true) }
        .sortedByDescending { it.createdAt }

    Box(Modifier.fillMaxSize().background(Color(0xFFF7F8FA))) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
            item {
                OutlinedTextField(
                    value = q,
                    onValueChange = { q = it },
                    label = { Text("Buscar cliente...") },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
                    trailingIcon = { Icon(Icons.Default.FilterList, null, tint = Purple) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(6.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFE5E7EB),
                        unfocusedBorderColor = Color(0xFFE5E7EB),
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        focusedLabelColor = Purple
                    )
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricCard("Pedidos", ultimos.size.toString(), Icons.Default.ReceiptLong, Modifier.weight(1f))
                    MetricCard("Pend.", ultimos.count { it.syncStatus != "SINCRONIZADO" }.toString(), Icons.Default.Schedule, Modifier.weight(1f))
                    MetricCard("Total", clp(ultimos.sumOf { it.total }), Icons.Default.Payments, Modifier.weight(1.25f))
                }
            }
            if (ultimos.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.ReceiptLong, null, tint = Muted)
                            Text("Sin pedidos recientes", fontWeight = FontWeight.Bold)
                            Text("Cree una NV o sincronice datos.", color = Muted)
                        }
                    }
                }
            }
            items(ultimos) { h ->
                val synced = h.syncStatus == "SINCRONIZADO"
                val facturado = h.facturado == "S"
                val statusBg = when {
                    facturado -> Color(0xFFE5E7EB)
                    synced -> Color(0xFFE8F5E9)
                    h.lastError != null -> Color(0xFFFFE4E6)
                    else -> Color(0xFFFFF7ED)
                }
                val statusText = when {
                    facturado -> "Facturado"
                    synced -> "Sincronizado"
                    h.lastError != null -> "Error"
                    else -> "Pendiente"
                }
                val statusColor = when {
                    facturado -> Color(0xFF374151)
                    synced -> Color(0xFF15803D)
                    h.lastError != null -> Color.Red
                    else -> Color(0xFFD97706)
                }

                Card(
                    Modifier.fillMaxWidth().clickable { onDetail(h) },
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(h.clienteRut + " - " + h.clienteNombre, fontWeight = FontWeight.Bold, color = Ink)
                                Text("RUT: ${h.clienteRut}", color = Muted, style = MaterialTheme.typography.bodySmall)
                                Text("Ultimo pedido: ${fechaCl(h.fecha)}", color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                            Surface(color = statusBg, shape = RoundedCornerShape(4.dp)) {
                                Text(statusText, color = statusColor, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Reparto ${fechaCl(h.fechaReparto)}", color = Muted, style = MaterialTheme.typography.bodySmall)
                            Text("Total: ${clp(h.total)}", color = Purple, fontWeight = FontWeight.Bold)
                        }
                        if (!h.lastError.isNullOrBlank()) Text("Error sync: ${h.lastError}", color = Color.Red, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick={onResumen(h)}, modifier=Modifier.weight(1f), contentPadding=PaddingValues(vertical=4.dp)) { Text("Resumen") }
                            OutlinedButton(onClick={onDetail(h)}, modifier=Modifier.weight(1f), contentPadding=PaddingValues(vertical=4.dp)) { Icon(Icons.Default.Visibility,null); Spacer(Modifier.width(4.dp)); Text("Ver") }
                            OutlinedButton(onClick={vm.abrirPdf(ctx, h)}, modifier=Modifier.weight(1f), contentPadding=PaddingValues(vertical=4.dp)) { Icon(Icons.Default.PictureAsPdf,null); Spacer(Modifier.width(4.dp)); Text("PDF") }
                        }
                        if (!facturado) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick={onEdit(h)}, modifier=Modifier.weight(1f), contentPadding=PaddingValues(vertical=4.dp)) { Icon(Icons.Default.Edit,null); Spacer(Modifier.width(4.dp)); Text("Editar") }
                                OutlinedButton(onClick={vm.eliminarNv(h, ctx)}, modifier=Modifier.weight(1f), contentPadding=PaddingValues(vertical=4.dp), colors=ButtonDefaults.outlinedButtonColors(contentColor=Color.Red)) { Icon(Icons.Default.Delete,null); Spacer(Modifier.width(4.dp)); Text("Eliminar") }
                            }
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = onNew,
            containerColor = Purple,
            contentColor = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        ) { Icon(Icons.Default.Add, null) }
    }
}



@Composable fun ResumenNvDialog(vm: MainVm, onEdit:(NvHeaderEntity)->Unit) {
    val h = vm.resumenHeader ?: return
    val ctx = LocalContext.current
    val lines = vm.resumenLines
    val ila = lines.sumOf { it.ilaLinea }
    val bloqueada = h.facturado == "S"
    val puedeEliminar = h.facturado != "S"

    AlertDialog(
        onDismissRequest = { vm.cerrarResumenPedido() },
        confirmButton = { TextButton(onClick = { vm.cerrarResumenPedido() }) { Text("Cerrar") } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🧾", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(8.dp))
                Text("Resumen NV", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Card(colors = CardDefaults.cardColors(containerColor = if (h.facturado == "S") Color(0xFFE5E7EB) else Color.White)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Cliente", fontWeight = FontWeight.Bold)
                        Text(h.clienteNombre)
                        Text("RUT ${h.clienteRut}", color = Color.Gray)
                        Text("Dirección", fontWeight = FontWeight.Bold)
                        Text(h.direccion ?: "-")
                        Text("Líneas: ${lines.size}/$MAX_NV_LINES")
                        CountMoney("Neto", h.neto)
                        CountMoney("IVA", h.iva)
                        if (ila > 0) CountMoney("ILA", ila)
                        CountMoney("Total", h.total, true)
                        Text("Observación: ${h.observacion ?: "-"}")
                        Text("Estado: ${h.syncStatus} · Facturado: ${h.facturado}", fontWeight = FontWeight.SemiBold, color = if (bloqueada) Color.DarkGray else Purple)
                        if (h.lastError != null) Text("Error: ${h.lastError}", color = Color.Red, fontWeight = FontWeight.Bold)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.abrirPdf(ctx, h) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(8.dp)); Text("Ver PDF")
                    }
                    OutlinedButton(onClick = { onEdit(h) }, enabled = !bloqueada, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Edit, null); Spacer(Modifier.width(8.dp)); Text(if (bloqueada) "Editar no disponible" else "Editar")
                    }
                    OutlinedButton(onClick = { vm.sync(ctx) }, enabled = h.syncStatus != "SINCRONIZADO", modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Sync, null); Spacer(Modifier.width(8.dp)); Text("Sincronizar")
                    }
                    OutlinedButton(onClick = { vm.eliminarNv(h, ctx) }, enabled = puedeEliminar, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Delete, null); Spacer(Modifier.width(8.dp)); Text(if (puedeEliminar) "Eliminar" else "Eliminar no disponible")
                    }
                }
                if (!puedeEliminar) Text("Regla: no se elimina si está facturada. Si ya fue enviada, queda eliminación pendiente y se procesa en Sync.", color = Color.Gray)
            }
        }
    )
}

@Composable fun PedidoDetalleDialog(vm: MainVm) {
    val h = vm.pedidoDetalleHeader ?: return
    val ctx = LocalContext.current
    val lines = vm.pedidoDetalleLines
    val ila = lines.sumOf { it.ilaLinea }
    val bloqueada = h.facturado == "S"

    AlertDialog(
        onDismissRequest = { vm.cerrarDetallePedido() },
        confirmButton = {
            TextButton(onClick = { vm.cerrarDetallePedido() }) { Text("Cerrar") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { vm.abrirPdf(ctx, h) }) { Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(6.dp)); Text("PDF") }
                if (h.facturado != "S") {
                    TextButton(onClick = { vm.eliminarNv(h, ctx); vm.cerrarDetallePedido() }) { Icon(Icons.Default.Delete, null, tint=Color.Red); Spacer(Modifier.width(6.dp)); Text("Eliminar", color=Color.Red) }
                }
            }
        },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Detalle NV", fontWeight = FontWeight.Bold)
                Text("${h.clienteNombre}", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            }
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = if (h.facturado == "S") Color(0xFFE5E7EB) else Color.White)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Cabecera", fontWeight = FontWeight.Bold)
                            Text("Cliente: ${h.clienteRut} · ${h.clienteNombre}")
                            Text("Dirección: ${h.direccion ?: "-"}")
                            Text("Pedido: ${fechaCl(h.fecha)} · Reparto: ${fechaCl(h.fechaReparto)}")
                            Text("Vendedor: ${h.vendedorCodigo ?: "-"} · Local ${h.localCodigo} · Bodega ${h.bodegaCodigo}")
                            Text("Estado: ${h.syncStatus} · Servidor: ${h.ventaNumeroServidor ?: "pendiente"}")
                            if (h.facturado == "S") Text("FACTURADA: solo lectura", color = Color.DarkGray, fontWeight = FontWeight.Bold)
                            if (h.lastError != null) Text("Error sync: ${h.lastError}", color = Color.Red, fontWeight = FontWeight.Bold)
                            if (!h.observacion.isNullOrBlank()) Text("Obs: ${h.observacion}")
                        }
                    }
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1F2))) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Totalizado", fontWeight = FontWeight.Bold)
                            CountMoney("Neto", h.neto)
                            CountMoney("IVA 19%", h.iva)
                            if (ila > 0) CountMoney("ILA", ila)
                            CountMoney("TOTAL", h.total, true)
                            Text("Líneas: ${lines.size}/$MAX_NV_LINES", color = if(lines.size > MAX_NV_LINES) Color.Red else Color.Gray)
                        }
                    }
                }
                item { Text("Productos", fontWeight = FontWeight.Bold) }
                if (lines.isEmpty()) {
                    item { Text("No hay líneas locales para esta NV. Revise sincronización/Room.", color = Color.Red) }
                } else {
                    items(lines) { l ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(l.descripcion ?: l.productoCodigo, fontWeight = FontWeight.SemiBold)
                                Text("Código ${l.productoCodigo} · Bodega ${l.bodegaCodigo}", color = Color.Gray)
                                Text("UXE ${stockText(l.uxe)} · Cant ${stockText(l.cantidad)} · Neto unit. ${clp(l.precio)} · Desc ${stockText(l.descuento)}%")
                                Text("Neto ${clp(l.netoLinea)} · IVA ${clp(l.ivaLinea)}${if (l.ilaLinea > 0) " · ILA ${clp(l.ilaLinea)}" else ""} · Total ${clp(l.totalLinea)}", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                item {
                    Text(
                        if (bloqueada) "Esta NV está bloqueada para modificación." else "Esta NV aún puede modificarse desde Pedidos > Modificar.",
                        color = if (bloqueada) Color.DarkGray else Purple,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    )
}

@Composable fun DiaLegacy(vm: MainVm) {
    val nvs by vm.nvs.collectAsState()
    val hoy = LocalDate.now()
    val inicioMes = hoy.withDayOfMonth(1)
    fun enRango(desde: LocalDate): List<NvHeaderEntity> = nvs.filter { runCatching { LocalDate.parse(it.fecha) }.getOrNull()?.let { f -> !f.isBefore(desde) && !f.isAfter(hoy) } == true }
    val dia = enRango(hoy)
    val mes = enRango(inicioMes)
    val diaOrdenado = dia.sortedByDescending { it.createdAt }
    val totalDia = diaOrdenado.sumOf { it.total }
    val semana = emptyList<NvHeaderEntity>()
    LazyColumn(Modifier.padding(18.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Text("Resumen del dia", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold) }
        item { KpiVentaCard("Ventas del día", dia) }
        item { KpiVentaCard("Ventas últimos 7 días", semana) }
        item { KpiVentaCard("Ventas acumuladas del mes", mes) }
    }
}

@Composable fun Dia(vm: MainVm) {
    val nvs by vm.nvs.collectAsState()
    val hoy = LocalDate.now()
    val dia = nvs.filter {
        runCatching { LocalDate.parse(it.fecha) }.getOrNull() == hoy
    }.sortedByDescending { it.createdAt }
    val totalDia = dia.sumOf { it.total }
    LazyColumn(Modifier.padding(18.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Text("Ventas del dia", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold) }
        item {
            Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(10.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) {
                Column(Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                        Column {
                            Text("Detalle ventas de hoy", fontWeight=FontWeight.Bold)
                            Text("${dia.size} documento(s)", color=Muted, style=MaterialTheme.typography.bodySmall)
                        }
                        Text(clp(totalDia), color=Purple, fontWeight=FontWeight.Bold)
                    }
                    Divider()
                    if (dia.isEmpty()) {
                        Text("No hay ventas locales registradas hoy.", color=Muted)
                    } else {
                        dia.forEach { h ->
                            val doc = h.ventaNumeroServidor?.toString() ?: h.offlineId.take(10)
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.abrirDetallePedido(h) }
                                    .padding(vertical=6.dp),
                                verticalArrangement=Arrangement.spacedBy(4.dp)
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                                    Text("NV $doc", fontWeight=FontWeight.Bold, color=Ink, maxLines=1, modifier=Modifier.weight(1f))
                                    Text(clp(h.total), color=Purple, fontWeight=FontWeight.Bold, maxLines=1)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(h.clienteNombre, maxLines=1, color=Ink, fontWeight=FontWeight.SemiBold)
                                        Text("${h.clienteRut} · ${h.syncStatus}", color=Muted, style=MaterialTheme.typography.bodySmall, maxLines=1)
                                    }
                                    Icon(Icons.Default.ChevronRight, null, tint=Muted)
                                }
                            }
                            Divider()
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                            Text("TOTAL GENERAL", fontWeight=FontWeight.Bold)
                            Text(clp(totalDia), color=Purple, fontWeight=FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable fun KpiVentaCard(titulo: String, items: List<NvHeaderEntity>) {
    Card(shape=RoundedCornerShape(12.dp), modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(titulo, fontWeight=FontWeight.Bold)
            Text("NV: ${items.size}", color=Color.Gray)
            Text("Neto: ${clp(items.sumOf{it.neto})}", color=Ink)
            Text("IVA: ${clp(items.sumOf{it.iva})}", color=Ink)
            Text("Total: ${clp(items.sumOf{it.total})}", color=Purple, fontWeight=FontWeight.Bold)
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun Consulta(vm: MainVm) {
    val familias by vm.familias.collectAsState()
    var q by remember { mutableStateOf("") }
    var exact by remember { mutableStateOf(false) }
    var familiaExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(q, exact, vm.selectedFamilia) {
        if (!exact) kotlinx.coroutines.delay(350)
        vm.searchProductos(q, exact)
    }
    LazyColumn(Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item { Text("Consulta de productos", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold) }
        item {
            Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    ExposedDropdownMenuBox(expanded = familiaExpanded, onExpandedChange = { familiaExpanded = !familiaExpanded }) {
                        OutlinedTextField(
                            value = vm.selectedFamilia?.let { "${it.familiaCodigo} · ${it.familiaDescripcion ?: ""}" } ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Familia") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = familiaExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(6.dp)
                        )
                        ExposedDropdownMenu(expanded = familiaExpanded, onDismissRequest = { familiaExpanded = false }) {
                            DropdownMenuItem(text = { Text("Todas / busqueda manual") }, onClick = { vm.selectFamilia(null); familiaExpanded = false })
                            familias.forEach { f ->
                                DropdownMenuItem(
                                    text = { Text("${f.familiaCodigo} · ${f.familiaDescripcion ?: ""}") },
                                    onClick = { vm.selectFamilia(f); familiaExpanded = false }
                                )
                            }
                        }
                    }
                    Row(verticalAlignment=Alignment.CenterVertically){ Checkbox(exact,{exact=it}); Text("Codigo exacto") }
                    OutlinedTextField(q,{q=it}, label={Text(if(exact) "Codigo exacto" else "Descripcion, codigo o familia")}, modifier=Modifier.fillMaxWidth(), singleLine=true, shape=RoundedCornerShape(6.dp))
                }
            }
        }
        items(vm.productos){ p ->
            val precioLista = vm.productListPrices[p.productoCodigo] ?: 0L
            Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(12.dp), horizontalArrangement=Arrangement.spacedBy(12.dp), verticalAlignment=Alignment.CenterVertically) {
                    ProductoFoto(p.fotoBase64, Modifier.size(72.dp))
                    Column(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text(p.productoDescripcion ?: p.productoCodigo, fontWeight=FontWeight.Bold)
                        Text("${p.productoCodigo} · ${p.familiaCodigo ?: "-"} ${p.familiaDescripcion ?: ""}", color=Muted)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text(if (precioLista > 0L) clp(precioLista) else "Sin precio L01", color=Purple, fontWeight=FontWeight.Bold)
                        Text("Desc max ${stockText(p.productoDescuento ?: 0.0)}%", color=Muted, style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
@Composable fun Sync(vm: MainVm) { val ctx= LocalContext.current; LazyColumn(Modifier.padding(18.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) { item { Text("Sincronización de datos", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold) }; item { Card(shape=RoundedCornerShape(12.dp), modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) { Text("Preparación offline", fontWeight=FontWeight.Bold); Text("Antes de salir a terreno, descargue clientes, direcciones, productos, precios, rutas y cuenta corriente.", color=Color.Gray); if (vm.isSyncing) LinearProgressIndicator(Modifier.fillMaxWidth()); Text(vm.syncMessage, color=if(vm.apiOk == false) Color.Red else Ink); vm.lastSyncAt?.let { Text("Última descarga: $it", color=Color.Gray) } } } }; item { Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(10.dp)) { OutlinedButton(onClick={vm.testConnection()}, modifier=Modifier.weight(1f)) { Icon(Icons.Default.Wifi,null); Spacer(Modifier.width(6.dp)); Text("Probar API") }; Button(onClick={vm.fullSync(ctx)}, modifier=Modifier.weight(1f), enabled=!vm.isSyncing) { Icon(Icons.Default.CloudDownload,null); Spacer(Modifier.width(6.dp)); Text("Descargar datos") } } }; item { Card(shape=RoundedCornerShape(12.dp), modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) { Text("Base local Room", fontWeight=FontWeight.Bold); CountRow("Usuarios secuser", vm.counts.usuarios); CountRow("Clientes", vm.counts.clientes); CountRow("Direcciones reparto", vm.counts.direcciones); CountRow("Productos", vm.counts.productos); CountRow("Familias", vm.counts.familias); CountRow("Precios", vm.counts.precios); CountRow("Rutas", vm.counts.rutas); CountRow("Cartola Cta. Cte", vm.counts.cartola) } } }; item { Card(shape=RoundedCornerShape(12.dp), modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) { Text("Cola de NV", fontWeight=FontWeight.Bold); CountRow("Pendientes", vm.counts.nvPendientes); CountRow("Con error", vm.counts.nvError); CountRow("Sincronizadas", vm.counts.nvSincronizadas); OutlinedButton(onClick={vm.sync(ctx)}, modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.Sync,null); Spacer(Modifier.width(8.dp)); Text("Enviar NV pendientes") } } } } }
}

@Composable fun CuentaCorriente(vm: MainVm) {
    val docs by vm.cartola.collectAsState()
    var q by remember { mutableStateOf("") }
    val query = q.trim()
    val filtrados = if (query.length < 2) docs else docs.filter {
        it.clienteRut.contains(query, true) || (it.clienteNombre ?: "").contains(query, true)
    }
    val grupos = filtrados
        .groupBy { it.clienteRut }
        .map { (_, rows) -> rows.first() to rows.sortedByDescending { it.ventaFecha ?: "" } }
        .sortedBy { it.first.clienteNombre ?: it.first.clienteRut }

    LazyColumn(Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {
            Text("Cuenta corriente", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold)
            Text("Cartola offline ultimos 6 meses", color=Color.Gray)
        }
        item { OutlinedTextField(q, { q = it }, label={Text("Buscar cliente por nombre o RUT")}, modifier=Modifier.fillMaxWidth(), singleLine=true) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    CountRow("Documentos", filtrados.size)
                    CountMoney("Saldo", filtrados.sumOf { it.saldo }, true)
                }
            }
        }
        if (grupos.isEmpty()) {
            item { Text("Sin cartola sincronizada. Ejecute Sync > Descargar datos.", color=Color.Gray) }
        } else {
            items(grupos) { (cliente, rows) ->
                val saldoCliente = rows.sumOf { it.saldo }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(cliente.clienteNombre ?: cliente.clienteRut, fontWeight=FontWeight.Bold)
                        Text("${cliente.clienteRut} · Docs ${rows.size} · Saldo ${clp(saldoCliente)}", color=if(saldoCliente > 0) Color.Red else Ink, fontWeight=FontWeight.SemiBold)
                        rows.take(12).forEach { d ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text("${d.ventaTipo} ${d.ventaNumero} · ${fechaCl(d.ventaFecha)}", fontWeight=FontWeight.SemiBold)
                                    Text("Total ${clp(d.ventaTotalVenta)} · Pagado ${clp(d.ventaPagoTotal)}", color=Color.Gray)
                                }
                                Text(clp(d.saldo), color=if(d.saldo > 0) Color.Red else Ink, fontWeight=FontWeight.Bold)
                            }
                        }
                        if (rows.size > 12) Text("+ ${rows.size - 12} documentos mas", color=Color.Gray)
                    }
                }
            }
        }
    }
}

@Composable fun MetricCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = Purple)
            Text(label, color = Muted, style = MaterialTheme.typography.bodySmall)
            Text(value, color = Ink, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable fun ProductoFoto(fotoBase64: String?, modifier: Modifier = Modifier) {
    val image = remember(fotoBase64) {
        runCatching {
            val raw = Base64.decode(fotoBase64.orEmpty(), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(raw, 0, raw.size)?.asImageBitmap()
        }.getOrNull()
    }
    Surface(modifier, shape = RoundedCornerShape(10.dp), color = Color(0xFFF1F5F9)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (image != null) {
                Image(bitmap = image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Default.Inventory2, contentDescription = null, tint = Muted)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun Consulta2(vm: MainVm) {
    val familias by vm.familias.collectAsState()
    var q by remember { mutableStateOf("") }
    var exact by remember { mutableStateOf(false) }
    var familiaExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(q, exact, vm.selectedFamilia) {
        if (!exact) kotlinx.coroutines.delay(350)
        vm.searchProductos(q, exact)
    }
    LazyColumn(Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                MetricCard("Resultados", vm.productos.size.toString(), Icons.Default.Inventory2, modifier = Modifier.weight(1f))
                MetricCard("Modo", if (vm.selectedFamilia != null) "Familia" else if (exact) "Codigo" else "Texto", Icons.Default.Tune, modifier = Modifier.weight(1f))
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Consulta de productos", fontWeight=FontWeight.Bold)
                    ExposedDropdownMenuBox(expanded = familiaExpanded, onExpandedChange = { familiaExpanded = !familiaExpanded }) {
                        OutlinedTextField(
                            value = vm.selectedFamilia?.let { "${it.familiaCodigo} · ${it.familiaDescripcion ?: ""}" } ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Familia") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = familiaExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(6.dp)
                        )
                        ExposedDropdownMenu(expanded = familiaExpanded, onDismissRequest = { familiaExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Todas / busqueda manual") },
                                onClick = {
                                    vm.selectFamilia(null)
                                    familiaExpanded = false
                                }
                            )
                            familias.forEach { f ->
                                DropdownMenuItem(
                                    text = { Text("${f.familiaCodigo} · ${f.familiaDescripcion ?: ""}") },
                                    onClick = {
                                        vm.selectFamilia(f)
                                        familiaExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Row(verticalAlignment=Alignment.CenterVertically){ Checkbox(exact,{exact=it}); Text("Codigo exacto") }
                    OutlinedTextField(
                        q,
                        { q=it },
                        label={Text(if(exact) "Codigo exacto" else "Descripcion, codigo o familia")},
                        modifier=Modifier.fillMaxWidth(),
                        singleLine=true,
                        shape = RoundedCornerShape(6.dp)
                    )
                }
            }
        }
        items(vm.productos){ p ->
            Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(12.dp), horizontalArrangement=Arrangement.spacedBy(12.dp), verticalAlignment=Alignment.CenterVertically) {
                    ProductoFoto(p.fotoBase64, Modifier.size(72.dp))
                    Column(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text(p.productoDescripcion ?: p.productoCodigo, fontWeight=FontWeight.Bold)
                        Text("${p.productoCodigo} · ${p.familiaCodigo ?: "-"} ${p.familiaDescripcion ?: ""}", color=Muted)
                        Text("Stock ${stockConsultaText(p.stockActual)}", color=if (p.stockActual > 0.0) Ink else Color.Red, fontWeight=FontWeight.SemiBold)
                    }
                    Text("Desc max ${stockText(p.productoDescuento ?: 0.0)}%", color=Muted, style=MaterialTheme.typography.bodySmall)
                    val precioLista = vm.productListPrices[p.productoCodigo] ?: 0L
                    Text(if (precioLista > 0L) clp(precioLista) else "Sin precio L01", color=Purple, fontWeight=FontWeight.Bold)
                }
            }
        }
    }

}

@Composable fun CuentaCorriente2(vm: MainVm, onNuevaNv: () -> Unit = {}) {
    val ctx = LocalContext.current
    val docs by vm.cartola.collectAsState()
    val clientesLocales by vm.allClientes.collectAsState()
    var modoOnline by remember { mutableStateOf(vm.cuentaCorrienteModoOnline) }
    var q by remember { mutableStateOf("") }
    var qOnline by remember { mutableStateOf("") }
    var clienteOnline by remember { mutableStateOf<ClienteEntity?>(null) }
    var verTodos by remember { mutableStateOf(false) }
    var verCartolaCompleta by remember { mutableStateOf(false) }
    var clientesExpandidos by remember { mutableStateOf<Set<String>>(emptySet()) }
    var documentosPopup by remember { mutableStateOf<List<CuentaCorrienteEntity>>(emptyList()) }
    var documentosPopupCliente by remember { mutableStateOf<String?>(null) }
    val clienteVoiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            vm.buscarClientesResumenPorVoz(spoken)
        }
    }
    fun startClienteVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale("es", "CL").toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Diga el nombre o RUT del cliente")
        }
        try {
            clienteVoiceLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            vm.message = "Reconocimiento de voz no disponible en este dispositivo"
        }
    }
    val vendedorActual = vm.currentUser?.vendedorCodigo?.trim().orEmpty()
    val clientesDelVendedor = remember(clientesLocales, vendedorActual) {
        if (vendedorActual.isBlank()) emptySet()
        else clientesLocales
            .filter { it.clienteVendedor?.trim() == vendedorActual }
            .map { it.clienteRut.trim().uppercase() }
            .toSet()
    }
    val docsVendedor = if (verTodos || clientesDelVendedor.isEmpty()) docs else docs.filter { it.clienteRut.trim().uppercase() in clientesDelVendedor }
    val docsBase = if (verCartolaCompleta) docsVendedor else documentosConSaldoPendiente(docsVendedor)
    val query = q.trim()
    val filtrados = if (query.length < 2) docsBase else docsBase.filter { it.clienteRut.contains(query, true) || (it.clienteNombre ?: "").contains(query, true) }
    val grupos = filtrados.groupBy { it.clienteRut }.map { (_, rows) -> rows.first() to rows.sortedByDescending { it.ventaFecha ?: "" } }.sortedBy { it.first.clienteNombre ?: it.first.clienteRut }
    LazyColumn(Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {
            Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(8.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) {
                Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                        Column {
                            Text("Cta Cte", fontWeight=FontWeight.Bold, color=Ink)
                            Text(if (modoOnline) "Consulta online al servidor" else "Cartola y resumen por cliente", color=Muted, style=MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text("Online", color=Muted, style=MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.width(6.dp))
                            Switch(
                                checked = modoOnline,
                                onCheckedChange = {
                                    modoOnline = it
                                    vm.cuentaCorrienteModoOnline = it
                                    vm.limpiarCuentaCorrienteOnline()
                                    clienteOnline = null
                                    qOnline = ""
                                }
                            )
                        }
                    }
                    if (modoOnline) {
                        val sugerencias = if (qOnline.trim().length < 2) emptyList() else clientesLocales.filter {
                            it.clienteNombre.contains(qOnline, true) || it.clienteRut.contains(qOnline, true)
                        }.take(8)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                qOnline,
                                { qOnline = it; clienteOnline = null },
                                label={Text("Buscar cliente por nombre o RUT")},
                                modifier=Modifier.fillMaxWidth(),
                                singleLine=true,
                                shape=RoundedCornerShape(6.dp)
                            )
                            if (sugerencias.isNotEmpty()) {
                                Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(6.dp), colors=CardDefaults.cardColors(containerColor=Color(0xFFF7F8FA))) {
                                    Column(Modifier.padding(8.dp), verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                        sugerencias.forEach { c ->
                                            Text(
                                                "${c.clienteNombre} (${c.clienteRut})",
                                                modifier=Modifier.fillMaxWidth().clickable {
                                                    clienteOnline = c
                                                    qOnline = c.clienteNombre
                                                    vm.cargarCuentaCorrienteOnline(c.clienteRut)
                                                }.padding(vertical=6.dp, horizontal=4.dp),
                                                color=Ink,
                                                style=MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }
                            }
                            Button(
                                onClick = { clienteOnline?.let { vm.cargarCuentaCorrienteOnline(it.clienteRut) } },
                                enabled = clienteOnline != null && !vm.isSyncing,
                                modifier=Modifier.fillMaxWidth(),
                                shape=RoundedCornerShape(6.dp),
                                colors=ButtonDefaults.buttonColors(containerColor=Purple)
                            ) {
                                Icon(Icons.Default.Cloud, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Consultar cuenta corriente online")
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
                            OutlinedTextField(q, { q = it }, label={Text("Buscar cliente por nombre o RUT")}, modifier=Modifier.weight(1f), singleLine=true, shape=RoundedCornerShape(6.dp))
                            IconButton(onClick={ startClienteVoiceSearch() }) {
                                Icon(Icons.Default.Mic, contentDescription="Resumen por voz", tint=Purple)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(if (verTodos) "Mostrando todos los clientes descargados" else "Mostrando clientes del vendedor", color=Ink, fontWeight=FontWeight.SemiBold, style=MaterialTheme.typography.bodySmall)
                                Text("Solo documentos con folio fiscal. Use Ver todos si necesita consultar otra cartola.", color=Muted, style=MaterialTheme.typography.labelSmall)
                            }
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text("Ver todos", color=Muted, style=MaterialTheme.typography.labelSmall)
                                Switch(checked=verTodos, onCheckedChange={ verTodos = it })
                            }
                        }
                        OutlinedButton(
                            onClick={
                                verCartolaCompleta = !verCartolaCompleta
                                clientesExpandidos = emptySet()
                            },
                            modifier=Modifier.fillMaxWidth(),
                            shape=RoundedCornerShape(6.dp)
                        ) {
                            Icon(if (verCartolaCompleta) Icons.Default.FilterAlt else Icons.Default.ReceiptLong, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (verCartolaCompleta) "Mostrar solo documentos pendientes" else "Ver Cta Cte completa")
                        }
                        OutlinedButton(onClick={ vm.descargarCuentaCorrienteCompleta() }, enabled=!vm.isSyncing, modifier=Modifier.fillMaxWidth(), shape=RoundedCornerShape(6.dp)) {
                            Icon(Icons.Default.CloudDownload, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Descargar Cta Cte completa")
                        }
                    }
                }
            }
        }
        if (modoOnline) {
            item {
                val docsOnline = vm.cuentaCorrienteOnlineRows
                val clienteNombreOnline = clienteOnline?.clienteNombre ?: clienteOnline?.clienteRut ?: vm.cuentaCorrienteRutOnline ?: "Cliente"
                Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(8.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) {
                    Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text(clienteNombreOnline, fontWeight=FontWeight.Bold, color=Ink)
                        Text("Documentos con folio fiscal", color=Muted, style=MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text("Documento", modifier=Modifier.weight(1.05f), color=Muted, style=MaterialTheme.typography.labelSmall, fontWeight=FontWeight.Bold, maxLines=1)
                            Text("Folio", modifier=Modifier.weight(.70f), color=Muted, style=MaterialTheme.typography.labelSmall, fontWeight=FontWeight.Bold, maxLines=1)
                            Text("Fecha", modifier=Modifier.weight(.70f), color=Muted, style=MaterialTheme.typography.labelSmall, fontWeight=FontWeight.Bold, maxLines=1)
                            Text("Total", modifier=Modifier.weight(1.05f), color=Muted, style=MaterialTheme.typography.labelSmall, fontWeight=FontWeight.Bold, maxLines=1)
                            Text("Saldo", modifier=Modifier.weight(1.05f), color=Muted, style=MaterialTheme.typography.labelSmall, fontWeight=FontWeight.Bold, maxLines=1)
                        }
                        if (docsOnline.isEmpty()) {
                            Text("Sin documentos online. Seleccione un cliente y consulte.", color=Muted)
                        } else {
                            docsOnline.forEach { d ->
                                val saldoDoc = cuentaSaldoDocumento(d)
                                Surface(
                                    modifier=Modifier.fillMaxWidth().clickable { vm.cargarDocumentoVentaDetalle(d) },
                                    color=Color(0xFFF7F8FA),
                                    shape=RoundedCornerShape(6.dp)
                                ) {
                                    Row(Modifier.fillMaxWidth().padding(horizontal=6.dp, vertical=7.dp), horizontalArrangement=Arrangement.spacedBy(6.dp), verticalAlignment=Alignment.CenterVertically) {
                                        Text("${d.ventaTipo} ${d.ventaNumero}", modifier=Modifier.weight(1.05f), style=MaterialTheme.typography.bodySmall, maxLines=1)
                                        Text(if (d.folio != null && d.folio > 0) d.folio.toString() else "-", modifier=Modifier.weight(.70f), style=MaterialTheme.typography.bodySmall, maxLines=1)
                                        Text(fechaCortaCl(d.ventaFecha), modifier=Modifier.weight(.70f), style=MaterialTheme.typography.bodySmall, maxLines=1)
                                        Text(clpCompact(d.ventaTotalVenta), modifier=Modifier.weight(1.05f), style=MaterialTheme.typography.bodySmall, maxLines=1)
                                        Text(clpCompact(saldoDoc), modifier=Modifier.weight(.85f), color=if(saldoDoc > 0) Color.Red else Ink, fontWeight=FontWeight.Bold, style=MaterialTheme.typography.bodySmall, maxLines=1)
                                        Icon(Icons.Default.Visibility, contentDescription="Ver detalle", tint=Purple, modifier=Modifier.size(18.dp))
                                        IconButton(
                                            onClick={ vm.abrirDtePdfReal(ctx, d) },
                                            modifier=Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.PictureAsPdf, contentDescription="PDF real", tint=Color(0xFFB91C1C), modifier=Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            if (grupos.isEmpty()) item {
                Text(
                    if (verCartolaCompleta) "Sin cartola sincronizada. Ejecute Sync > Descargar datos." else "No hay documentos pendientes.",
                    color=Muted
                )
            }
            items(grupos) { (cliente, rows) ->
                val saldoCliente = cuentaSaldoTotal(rows)
                val clienteExpandido = cliente.clienteRut in clientesExpandidos
                val rowsVisibles = if (clienteExpandido) rows else rows.take(10)
                val cartolaCompletaCliente = docsVendedor
                    .filter { it.clienteRut == cliente.clienteRut }
                    .sortedByDescending { it.ventaFecha ?: "" }
                Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(8.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) {
                    Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(cliente.clienteNombre ?: cliente.clienteRut, fontWeight=FontWeight.Bold, color=Ink)
                                Text("${cliente.clienteRut} · ${rows.size} docs", color=Muted, style=MaterialTheme.typography.bodySmall)
                            }
                            Text("Saldo ${clp(saldoCliente)}", color=if(saldoCliente > 0) Color.Red else Ink, fontWeight=FontWeight.Bold)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text("Documento", modifier=Modifier.weight(1.05f), color=Muted, style=MaterialTheme.typography.labelSmall, fontWeight=FontWeight.Bold, maxLines=1)
                            Text("Folio", modifier=Modifier.weight(.70f), color=Muted, style=MaterialTheme.typography.labelSmall, fontWeight=FontWeight.Bold, maxLines=1)
                            Text("Fecha", modifier=Modifier.weight(.70f), color=Muted, style=MaterialTheme.typography.labelSmall, fontWeight=FontWeight.Bold, maxLines=1)
                            Text("Total", modifier=Modifier.weight(1.05f), color=Muted, style=MaterialTheme.typography.labelSmall, fontWeight=FontWeight.Bold, maxLines=1)
                            Text("Saldo", modifier=Modifier.weight(1.05f), color=Muted, style=MaterialTheme.typography.labelSmall, fontWeight=FontWeight.Bold, maxLines=1)
                        }
                        Text("Toque un documento para ver su detalle.", color=Muted, style=MaterialTheme.typography.labelSmall)
                        rowsVisibles.forEach { d ->
                            val saldoDoc = cuentaSaldoDocumento(d)
                            Surface(
                                modifier=Modifier.fillMaxWidth().clickable { vm.cargarDocumentoVentaDetalle(d) },
                                color=Color(0xFFF7F8FA),
                                shape=RoundedCornerShape(6.dp)
                            ) {
                                Row(Modifier.fillMaxWidth().padding(horizontal=6.dp, vertical=7.dp), horizontalArrangement=Arrangement.spacedBy(6.dp), verticalAlignment=Alignment.CenterVertically) {
                                    Text("${d.ventaTipo} ${d.ventaNumero}", modifier=Modifier.weight(1.05f), style=MaterialTheme.typography.bodySmall, maxLines=1)
                                    Text(if (d.folio != null && d.folio > 0) d.folio.toString() else "-", modifier=Modifier.weight(.70f), style=MaterialTheme.typography.bodySmall, maxLines=1)
                                    Text(fechaCortaCl(d.ventaFecha), modifier=Modifier.weight(.70f), style=MaterialTheme.typography.bodySmall, maxLines=1)
                                    Text(clpCompact(d.ventaTotalVenta), modifier=Modifier.weight(1.05f), style=MaterialTheme.typography.bodySmall, maxLines=1)
                                    Text(clpCompact(saldoDoc), modifier=Modifier.weight(.85f), color=if(saldoDoc > 0) Color.Red else Ink, fontWeight=FontWeight.Bold, style=MaterialTheme.typography.bodySmall, maxLines=1)
                                    Icon(Icons.Default.Visibility, contentDescription="Ver detalle", tint=Purple, modifier=Modifier.size(18.dp))
                                    IconButton(
                                        onClick={ vm.abrirDtePdfReal(ctx, d) },
                                        modifier=Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.PictureAsPdf, contentDescription="PDF real", tint=Color(0xFFB91C1C), modifier=Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    if (rows.size > 10) {
                        TextButton(onClick={
                            clientesExpandidos = if (clienteExpandido) clientesExpandidos - cliente.clienteRut else clientesExpandidos + cliente.clienteRut
                        }) {
                            Text(if (clienteExpandido) "Ver menos" else "Ver ${rows.size - 10} documentos mas")
                        }
                    }
                    Divider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        val pendientes = documentosConSaldoPendiente(cartolaCompletaCliente)
                        Button(onClick={ vm.abrirCuentaCorrientePdf(ctx, cliente, pendientes) }, modifier=Modifier.weight(1f), contentPadding=PaddingValues(horizontal=5.dp, vertical=8.dp), shape=RoundedCornerShape(6.dp), colors=ButtonDefaults.buttonColors(containerColor=Purple)) {
                            Icon(Icons.Default.PictureAsPdf, null)
                            Spacer(Modifier.width(4.dp))
                            Text("PDF pendientes", style=MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(onClick={
                            documentosPopup = pendientes.sortedWith(compareBy<CuentaCorrienteEntity> { it.ventaFecha ?: "" }.thenBy { it.ventaNumero })
                            documentosPopupCliente = cliente.clienteNombre ?: cliente.clienteRut
                        }, modifier=Modifier.weight(1f), contentPadding=PaddingValues(horizontal=5.dp, vertical=8.dp), shape=RoundedCornerShape(6.dp)) {
                            Icon(Icons.Default.ReceiptLong, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Ver documentos", style=MaterialTheme.typography.labelMedium)
                        }
                        Button(onClick={ vm.compartirCuentaCorrienteWhatsApp(ctx, cliente, pendientes) }, modifier=Modifier.weight(1f), contentPadding=PaddingValues(horizontal=5.dp, vertical=8.dp), shape=RoundedCornerShape(6.dp), colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF16A34A))) {
                            Icon(Icons.Default.Send, null)
                            Spacer(Modifier.width(4.dp))
                            Text("WhatsApp")
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={ vm.abrirCuentaCorrientePdf(ctx, cliente, cartolaCompletaCliente) }, modifier=Modifier.weight(1f), contentPadding=PaddingValues(horizontal=8.dp, vertical=8.dp)) {
                            Icon(Icons.Default.PictureAsPdf, null)
                            Spacer(Modifier.width(6.dp))
                            Text("PDF completo")
                        }
                        OutlinedButton(onClick={ vm.cargarUltimaVentaDetalle(cliente) }, modifier=Modifier.weight(1f), contentPadding=PaddingValues(horizontal=8.dp, vertical=8.dp)) {
                            Icon(Icons.Default.ReceiptLong, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Ultima venta")
                        }
                    }
                    OutlinedButton(onClick={ vm.copiarUltimaFacturaANuevaNv(cliente, onNuevaNv) }, modifier=Modifier.fillMaxWidth(), enabled=!vm.isSyncing, contentPadding=PaddingValues(horizontal=8.dp, vertical=9.dp), shape=RoundedCornerShape(6.dp), colors=ButtonDefaults.outlinedButtonColors(contentColor=Purple)) {
                        Icon(Icons.Default.ContentCopy, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Copiar ultima factura a NV", fontWeight=FontWeight.Bold)
                    }
                }
            }
        }
    }
    }
    documentosPopupCliente?.let { clienteNombre ->
        val saldoFinal = documentosPopup.sumOf(::cuentaSaldoDocumento)
        AlertDialog(
            onDismissRequest = {
                documentosPopupCliente = null
                documentosPopup = emptyList()
            },
            title = { Text("Documentos pendientes", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(clienteNombre, color = Muted, style = MaterialTheme.typography.bodyMedium)
                    if (documentosPopup.isEmpty()) {
                        Text("El cliente no tiene documentos pendientes.")
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Documento", Modifier.weight(1.05f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            Text("Fecha", Modifier.weight(.7f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            Text("Total", Modifier.weight(1.05f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            Text("Saldo", Modifier.weight(.85f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        }
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(documentosPopup) { doc ->
                                val saldoDoc = cuentaSaldoDocumento(doc)
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("${doc.ventaTipo} ${doc.ventaNumero}", Modifier.weight(1.05f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                    Text(fechaCortaCl(doc.ventaFecha), Modifier.weight(.7f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                    Text(clpCompact(doc.ventaTotalVenta), Modifier.weight(1.05f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                    Text(clpCompact(saldoDoc), Modifier.weight(.85f), color = if(saldoDoc > 0) Color.Red else Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                }
                                Divider()
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Saldo final", fontWeight = FontWeight.Bold)
                            Text(clp(saldoFinal), color = if(saldoFinal > 0) Color.Red else Ink, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    documentosPopupCliente = null
                    documentosPopup = emptyList()
                }) {
                    Text("Cerrar")
                }
            }
        )
    }
}

@Composable fun MiniVentasCuentaChart(rows: List<CuentaCorrienteEntity>) {
    val ventasPorMes = rows
        .filter { it.ventaTipo.equals("FE", true) || it.ventaTipo.equals("FA", true) || it.ventaTipo.equals("BO", true) || it.ventaTipo.equals("CH", true) }
        .groupBy { (it.ventaFecha ?: "").take(7) }
        .mapValues { (_, docs) -> docs.sumOf { it.ventaTotalVenta } }
        .filterKeys { it.length == 7 }
        .toSortedMap()
        .entries
        .toList()
        .takeLast(6)
    if (ventasPorMes.isEmpty() || ventasPorMes.all { it.value <= 0L }) return
    val maxVenta = ventasPorMes.maxOf { it.value }.coerceAtLeast(1L)
    Surface(shape=RoundedCornerShape(8.dp), color=Color(0xFFF7F8FA)) {
        Column(Modifier.padding(10.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("Ventas segun cuenta corriente", color=Ink, fontWeight=FontWeight.SemiBold, style=MaterialTheme.typography.bodySmall)
            Canvas(Modifier.fillMaxWidth().height(74.dp)) {
                val gap = 7.dp.toPx()
                val barWidth = (size.width - gap * (ventasPorMes.size - 1)) / ventasPorMes.size
                ventasPorMes.forEachIndexed { index, entry ->
                    val barHeight = (entry.value.toFloat() / maxVenta.toFloat()) * size.height
                    val left = index * (barWidth + gap)
                    drawRect(
                        color = Purple,
                        topLeft = androidx.compose.ui.geometry.Offset(left, size.height - barHeight),
                        size = androidx.compose.ui.geometry.Size(barWidth, barHeight)
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                ventasPorMes.forEach { entry ->
                    Text(entry.key.drop(5), style=MaterialTheme.typography.labelSmall, color=Muted, modifier=Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable fun UltimaVentaDialog(vm: MainVm) {
    val venta = vm.ultimaVentaDetalle ?: return
    AlertDialog(
        onDismissRequest = { vm.cerrarUltimaVentaDetalle() },
        title = { Text("Detalle documento") },
        text = {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if (venta.ventaNumero == null) {
                    Text("No hay venta registrada para este cliente.", color=Muted)
                } else {
                    Text("${venta.ventaTipo ?: "-"} ${venta.ventaNumero} · ${fechaCl(venta.ventaFecha)}", fontWeight=FontWeight.Bold)
                    Text("Total ${clp(venta.ventaTotalVenta ?: 0)}", color=Purple, fontWeight=FontWeight.SemiBold)
                    Divider()
                    if (venta.lines.isEmpty()) {
                        Text("Sin detalle disponible.", color=Muted)
                    } else {
                        LazyColumn(Modifier.heightIn(max=320.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            items(venta.lines) { line ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(line.descripcion ?: line.productoCodigo, fontWeight=FontWeight.SemiBold)
                                        Text("Codigo ${line.productoCodigo}", color=Muted, style=MaterialTheme.typography.bodySmall)
                                    }
                                    Text("UXE ${stockText(line.uxe ?: 0.0)}", fontWeight=FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.cerrarUltimaVentaDetalle() }) { Text("Cerrar") } }
    )
}

@Composable fun ResumenClienteVozDialog(vm: MainVm) {
    val text = vm.resumenClienteVoz ?: return
    AlertDialog(
        onDismissRequest = { vm.cerrarResumenClienteVoz() },
        title = { Text("Resumen de cliente") },
        text = {
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(text)
                VentasCuentaChart(vm.resumenClienteChartRows)
            }
        },
        confirmButton = { TextButton(onClick = { vm.cerrarResumenClienteVoz() }) { Text("Cerrar") } }
    )
}

@Composable fun VentasCuentaChart(rows: List<CuentaCorrienteEntity>) {
    val ventasPorMes = rows
        .filter { it.ventaTipo.equals("FE", true) || it.ventaTipo.equals("FA", true) || it.ventaTipo.equals("BO", true) || it.ventaTipo.equals("CH", true) }
        .groupBy { (it.ventaFecha ?: "").take(7) }
        .mapValues { (_, docs) -> docs.sumOf { it.ventaTotalVenta } }
        .filterKeys { it.length == 7 }
        .toSortedMap()
        .entries
        .toList()
        .takeLast(6)
    if (ventasPorMes.isEmpty()) {
        Text("Sin ventas en cuenta corriente para graficar.", color=Muted)
        return
    }
    val maxVenta = ventasPorMes.maxOf { it.value }.coerceAtLeast(1L)
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("Ventas segun cuenta corriente", fontWeight=FontWeight.Bold)
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val gap = 8.dp.toPx()
            val barWidth = (size.width - gap * (ventasPorMes.size - 1)) / ventasPorMes.size
            ventasPorMes.forEachIndexed { index, entry ->
                val barHeight = (entry.value.toFloat() / maxVenta.toFloat()) * (size.height - 26.dp.toPx())
                val left = index * (barWidth + gap)
                drawRect(
                    color = if (entry.value > 0) Purple else Muted,
                    topLeft = androidx.compose.ui.geometry.Offset(left, size.height - barHeight - 22.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(barWidth, barHeight)
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
            ventasPorMes.forEach { entry ->
                Column(horizontalAlignment=Alignment.CenterHorizontally, modifier=Modifier.weight(1f)) {
                    Text(entry.key.drop(5), style=MaterialTheme.typography.labelSmall, color=Muted)
                    Text(clp(entry.value), style=MaterialTheme.typography.labelSmall, fontWeight=FontWeight.Bold, color=Ink)
                }
            }
        }
    }
}

@Composable fun ResumenClienteCandidatesDialog(vm: MainVm) {
    val candidates = vm.resumenClienteCandidates
    if (candidates.isEmpty()) return
    val ctx = LocalContext.current
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(ctx) {
        lateinit var speech: TextToSpeech
        speech = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) speech.language = Locale("es", "CL")
        }
        tts = speech
        onDispose {
            speech.stop()
            speech.shutdown()
            tts = null
        }
    }
    AlertDialog(
        onDismissRequest = { vm.cerrarResumenClienteCandidates() },
        title = { Text("Seleccione cliente") },
        text = {
            LazyColumn(Modifier.heightIn(max=360.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(candidates) { c ->
                    Card(
                        Modifier.fillMaxWidth().clickable {
                            vm.generarResumenCliente(c) { text ->
                                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "resumen_cliente")
                            }
                        },
                        shape=RoundedCornerShape(8.dp)
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement=Arrangement.spacedBy(3.dp)) {
                            Text(c.clienteNombre ?: c.clienteRut, fontWeight=FontWeight.Bold)
                            Text(c.clienteRut, color=Muted)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.cerrarResumenClienteCandidates() }) { Text("Cerrar") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun Sync2(vm: MainVm) {
    val ctx = LocalContext.current
    var showMailDialog by remember { mutableStateOf(false) }
    var resumenEmail by remember { mutableStateOf("") }
    val currentYear = LocalDate.now().year
    val monthNames = listOf("Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre")
    var resumenMes by remember { mutableStateOf(LocalDate.now().monthValue) }
    var resumenAno by remember { mutableStateOf(currentYear) }
    var monthExpanded by remember { mutableStateOf(false) }
    var yearExpanded by remember { mutableStateOf(false) }
    LazyColumn(Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                MetricCard("Clientes", vm.counts.clientes.toString(), Icons.Default.Groups, modifier = Modifier.weight(1f))
                MetricCard("Productos", vm.counts.productos.toString(), Icons.Default.Inventory2, modifier = Modifier.weight(1f))
                MetricCard("Cartola", vm.counts.cartola.toString(), Icons.Default.AccountBalanceWallet, modifier = Modifier.weight(1f))
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("Sincronizacion de datos", fontWeight=FontWeight.Bold)
                    Text(vm.syncMessage, color=if(vm.apiOk == false) Color.Red else Ink)
                    if (vm.isSyncing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick={vm.testConnection()}, modifier=Modifier.weight(1f)) { Icon(Icons.Default.Wifi,null); Spacer(Modifier.width(6.dp)); Text("Probar") }
                        Button(onClick={vm.fullSync(ctx)}, modifier=Modifier.weight(1f), enabled=!vm.isSyncing) { Icon(Icons.Default.CloudDownload,null); Spacer(Modifier.width(6.dp)); Text("Descargar") }
                    }
                    OutlinedButton(onClick={vm.actualizarApp(ctx)}, modifier=Modifier.fillMaxWidth(), enabled=!vm.isSyncing) {
                        Icon(Icons.Default.SystemUpdate, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Actualizar App")
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Cola de NV", fontWeight=FontWeight.Bold)
                    CountRow("Pendientes", vm.counts.nvPendientes)
                    CountRow("Con error", vm.counts.nvError)
                    CountRow("Sincronizadas", vm.counts.nvSincronizadas)
                    OutlinedButton(onClick={vm.sync(ctx)}, modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.Sync,null); Spacer(Modifier.width(8.dp)); Text("Enviar NV pendientes") }
                    Button(onClick={ showMailDialog = true }, modifier=Modifier.fillMaxWidth(), enabled=!vm.isSyncing, colors=ButtonDefaults.buttonColors(containerColor=Purple, contentColor=Color.White), shape=RoundedCornerShape(6.dp)) {
                        Icon(Icons.Default.Email, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Enviar reporte vendedor PDF")
                    }
                }
            }
        }
    }
    if (showMailDialog) {
        AlertDialog(
            onDismissRequest={ showMailDialog = false },
            title={ Text("Enviar reporte vendedor", fontWeight=FontWeight.Bold, color=Ink) },
            text={
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Seleccione periodo. El PDF considera FE y BO como ventas; CE descuenta.", color=Muted)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        ExposedDropdownMenuBox(
                            expanded = monthExpanded,
                            onExpandedChange = { monthExpanded = !monthExpanded },
                            modifier = Modifier.weight(1f)
                        ) {
                            OutlinedTextField(
                                value = monthNames[resumenMes - 1],
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Mes") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(monthExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(expanded = monthExpanded, onDismissRequest = { monthExpanded = false }) {
                                monthNames.forEachIndexed { index, name ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = {
                                            resumenMes = index + 1
                                            monthExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        ExposedDropdownMenuBox(
                            expanded = yearExpanded,
                            onExpandedChange = { yearExpanded = !yearExpanded },
                            modifier = Modifier.weight(1f)
                        ) {
                            OutlinedTextField(
                                value = resumenAno.toString(),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Ano") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(yearExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(expanded = yearExpanded, onDismissRequest = { yearExpanded = false }) {
                                (currentYear downTo 2018).forEach { year ->
                                    DropdownMenuItem(
                                        text = { Text(year.toString()) },
                                        onClick = {
                                            resumenAno = year
                                            yearExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value=resumenEmail,
                        onValueChange={ resumenEmail = it.take(120) },
                        label={ Text("Correo destino") },
                        leadingIcon={ Icon(Icons.Default.Email, null, tint=Purple) },
                        modifier=Modifier.fillMaxWidth(),
                        singleLine=true
                    )
                }
            },
            confirmButton={
                Button(
                    onClick={
                        vm.enviarResumenVentasDia(resumenEmail, resumenMes, resumenAno)
                        showMailDialog = false
                    },
                    enabled=resumenEmail.contains("@") && resumenEmail.contains(".") && !vm.isSyncing,
                    colors=ButtonDefaults.buttonColors(containerColor=Purple, contentColor=Color.White)
                ) { Text("Enviar PDF") }
            },
            dismissButton={ TextButton(onClick={ showMailDialog = false }) { Text("Cancelar") } },
            containerColor=Color.White,
            shape=RoundedCornerShape(8.dp)
        )
    }
}

@Composable fun CountRow(label: String, value: Int) { Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) { Text(label, color=Ink); Text(value.toString(), fontWeight=FontWeight.Bold, color=if(value > 0) Purple else Color.Red) } }
@Composable fun CountMoney(label: String, value: Long, strong: Boolean=false) { Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) { Text(label, fontWeight=if(strong) FontWeight.Bold else FontWeight.Normal); Text(clp(value), fontWeight=FontWeight.Bold, color=if(strong) Purple else Ink) } }
@Composable fun SmallStatusChip(label: String, value: Int) { AssistChip(onClick={}, label={ Text("$label: $value") }) }
