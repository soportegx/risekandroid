package cl.risek.offline

import kotlinx.coroutines.flow.Flow
import com.google.gson.JsonParser
import retrofit2.HttpException
import java.text.Normalizer
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.delay
import kotlin.math.roundToLong

private const val DEFAULT_PRICE_LIST = "01"
private val EXCLUDED_FAMILIES = setOf("24", "29", "30")

class AuthRepository(private val api: RisekApi, private val dao: SecUserDao, private val session: SessionStore) {
    fun users(): Flow<List<SecUserEntity>> = dao.observeAll()
    suspend fun testConnection(): HealthResponse = api.health()
    suspend fun refreshUsers(): Int {
        val users = api.secusers().map { SecUserEntity(it.secUserId, it.secUserName, it.vendedorCodigo) }
        dao.upsertAll(users)
        return users.size
    }
    suspend fun countUsers(): Int = dao.countUsers()
    suspend fun localUsers(): List<SecUserEntity> = dao.getAll()
    suspend fun login(user: SecUserEntity, password: String): LoginResponse {
        val res = api.login(LoginRequest(user.secUserId, password))
        if (res.ok && res.token != null) session.save(res.token, res.vendedorCodigo ?: user.vendedorCodigo)
        return res
    }
}

fun apiErrorMessage(e: Exception): String {
    if (e is HttpException) {
        val raw = runCatching { e.response()?.errorBody()?.string() }.getOrNull().orEmpty()
        val detail = runCatching {
            JsonParser.parseString(raw).asJsonObject.get("detail")?.asString
        }.getOrNull()
        return detail?.takeIf { it.isNotBlank() } ?: "HTTP ${e.code()} ${e.message()}"
    }
    return e.message ?: "Error sync"
}

fun normalizeSearchText(value: String): String =
    Normalizer.normalize(value.trim().lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")

private fun cuentaCorrienteEntityFromDto(it: CuentaCorrienteItemDto): CuentaCorrienteEntity =
    CuentaCorrienteEntity(
        clienteRut = it.clienteRut.trim(),
        clienteNombre = it.clienteNombre,
        ventaNumero = it.ventaNumero,
        ventaTipo = it.ventaTipo,
        ventaFecha = it.ventaFecha,
        ventaTotalVenta = it.ventaTotalVenta,
        ventaPagoTotal = it.ventaPagoTotal,
        saldo = it.saldo,
        folio = it.folio,
        folioSii = it.folioSii,
        estadoSii = it.estadoSii
    )

class CatalogRepository(private val api: RisekApi, private val dao: CatalogDao, private val cuentaDao: CuentaCorrienteDao, private val session: SessionStore) {
    fun cuentaCorriente(): Flow<List<CuentaCorrienteEntity>> = cuentaDao.observeAllConFolio()

    private suspend fun <T> retryDownload(label: String, attempts: Int = 2, block: suspend () -> T): T {
        var last: Exception? = null
        repeat(attempts) { idx ->
            try {
                return block()
            } catch (e: Exception) {
                last = e
                if (idx < attempts - 1) delay(700L)
            }
        }
        throw RuntimeException("Error descargando $label: ${last?.message}", last)
    }

    private suspend fun <T> descargarPorPaginas(pageSize: Int = 800, fetch: suspend (limit: Int, offset: Int) -> List<T>): List<T> {
        val out = mutableListOf<T>()
        var offset = 0
        while (true) {
            val page = fetch(pageSize, offset)
            out.addAll(page)
            if (page.size < pageSize) break
            offset += pageSize
        }
        return out
    }

    private suspend fun descargarClientesPorPaginas(desbloqueados: Boolean = false): List<ClienteDto> =
        descargarPorPaginas { limit, offset ->
            if (desbloqueados) api.bootstrapClientesDesbloqueados(limit, offset) else api.bootstrapClientes(limit, offset)
        }

    private fun manifestCount(key: String, manifest: Map<String, String>): Int =
        manifest[key].orEmpty().substringBefore(":").toIntOrNull() ?: 0

    private fun needsDownload(key: String, manifest: Map<String, String>, hasLocalData: Boolean, localCount: Int = 0): Boolean {
        val remote = manifest[key].orEmpty()
        return remote.isBlank() || !hasLocalData || session.bootstrapSignature(key) != remote
    }

    private fun saveSignature(key: String, manifest: Map<String, String>) {
        session.saveBootstrapSignature(key, manifest[key])
    }

    suspend fun bootstrap(): BootstrapResult {
        // Carga separada por tabla: si falla algo, el mensaje indicará la entidad exacta.
        val warnings = mutableListOf<String>()
        val local = counts()
        val manifest = runCatching { api.bootstrapManifest() }.getOrDefault(emptyMap())
        val dlClientes = needsDownload("clientes", manifest, local.clientes > 0, local.clientes)
        val dlDirecciones = needsDownload("direcciones", manifest, local.direcciones > 0, local.direcciones)
        val dlProductos = needsDownload("productos", manifest, local.productos > 0, local.productos)
        val dlFamilias = needsDownload("familias", manifest, local.familias > 0, local.familias)
        val dlPrecios = needsDownload("precios", manifest, local.precios > 0, local.precios)
        val dlRutas = needsDownload("rutas", manifest, local.rutas > 0, local.rutas)
        val vendedorCartola = session.vendedorCodigo()?.trim()?.takeIf { it.isNotBlank() }
        val cuentaSignatureKey = if (vendedorCartola.isNullOrBlank()) "cuenta_corriente" else "cuenta_corriente_${vendedorCartola}"
        val cuentaManifest = mapOf(cuentaSignatureKey to manifest["cuenta_corriente"].orEmpty())
        val dlCuenta = needsDownload(cuentaSignatureKey, cuentaManifest, local.cartola > 0, local.cartola)

        val clientesDto = if (dlClientes) runCatching { retryDownload("clientes") { descargarClientesPorPaginas() } }.getOrElse { warnings.add(it.message ?: "Error descargando clientes"); emptyList() } else emptyList()
        val direccionesDto = if (dlDirecciones) runCatching { retryDownload("direcciones") { descargarPorPaginas { limit, offset -> api.bootstrapDirecciones(limit, offset) } } }.getOrElse { warnings.add(it.message ?: "Error descargando direcciones"); emptyList() } else emptyList()
        val productosDto = if (dlProductos) runCatching { retryDownload("productos") { descargarPorPaginas { limit, offset -> api.bootstrapProductos(limit, offset) } } }.getOrElse { warnings.add(it.message ?: "Error descargando productos"); emptyList() } else emptyList()
        val familiasDto = if (dlFamilias) runCatching { retryDownload("familias") { api.bootstrapFamilias() } }.getOrElse { warnings.add(it.message ?: "Error descargando familias"); emptyList() } else emptyList()
        val preciosDto = if (dlPrecios) runCatching { retryDownload("precios") { descargarPorPaginas { limit, offset -> api.bootstrapPrecios(limit, offset) } } }.getOrElse { warnings.add(it.message ?: "Error descargando precios"); emptyList() } else emptyList()
        val rutasDto = if (dlRutas) runCatching { retryDownload("rutas") { api.bootstrapRutas() } }.getOrElse { warnings.add(it.message ?: "Error descargando rutas"); emptyList() } else emptyList()
        val cuentaDto = if (dlCuenta) runCatching { retryDownload("cuenta corriente") { descargarPorPaginas { limit, offset -> api.bootstrapCuentaCorriente(limit, offset, vendedorCartola) } } }.getOrElse { warnings.add(it.message ?: "Error descargando cuenta corriente"); emptyList() } else emptyList()

        val clientes = clientesDto
            .filter { it.clienteRut.isNotBlank() }
            .map { ClienteEntity(it.clienteRut.trim(), it.clienteNombre.orEmpty(), it.clienteDireccion, it.ciudadCodigo, it.comuna, it.clienteEstado, it.rutaId, it.listaCodigo, it.clienteGeo, it.clienteVendedor) }

        val direcciones = direccionesDto
            .filter { it.clienteRut.isNotBlank() && it.direccion.isNotBlank() }
            .distinctBy { "${it.clienteRut.trim().uppercase()}|${it.direccion.trim().uppercase()}|${it.comuna?.trim()?.uppercase().orEmpty()}|${it.ciudadCodigo?.trim()?.uppercase().orEmpty()}" }
            .mapIndexed { idx, it ->
                ClienteDireccionEntity(
                    clienteRut = it.clienteRut.trim(),
                    direccionId = it.direccionId?.takeIf { id -> id.isNotBlank() } ?: "L4-${it.clienteRut.trim()}-$idx",
                    direccion = it.direccion.trim(),
                    comuna = it.comuna,
                    ciudadCodigo = it.ciudadCodigo
                )
            }
            // No agregar clientes.cliente_direccion. La dirección de reparto válida viene solo de clienteslevel4/clientelevel4.

        val familias = familiasDto
            .filter { it.familiaCodigo.isNotBlank() && it.familiaCodigo.trim() !in EXCLUDED_FAMILIES && !it.familiaDescripcion.equals("INACTIVOS", ignoreCase = true) }
            .map { FamiliaEntity(it.familiaCodigo.trim(), it.familiaDescripcion, it.familiaRestaurant) }

        val familiaNombre = familias.associate { it.familiaCodigo to it.familiaDescripcion }
        val productos = productosDto
            .filter { it.productoCodigo.isNotBlank() && it.familiaCodigo?.trim() !in EXCLUDED_FAMILIES && !it.familiaDescripcion.equals("INACTIVOS", ignoreCase = true) }
            .map { ProductoEntity(it.productoCodigo.trim(), it.productoDescripcion, it.familiaCodigo, it.productoEstado, it.productoUnidadEnvase, it.productoGramaje, it.productoDescuento, it.productoVenta, it.familiaDescripcion ?: familiaNombre[it.familiaCodigo], it.stockActual, it.stockFecha, null) }

        val rutas = rutasDto.map { RutaEntity(it.rutaId, it.rutaNombre) }

        val precios = preciosDto
            .filter { it.productoCodigo.isNotBlank() && it.listaCodigo.isNotBlank() }
            .map { precioEntityFromDto(it) }

        val cuenta = cuentaDto
            .filter { it.clienteRut.isNotBlank() && it.ventaTipo.isNotBlank() && it.ventaNumero > 0 }
            .map { cuentaCorrienteEntityFromDto(it) }

        if (dlClientes && clientes.isNotEmpty()) { dao.replaceClientes(clientes); saveSignature("clientes", manifest) }
        if (dlDirecciones && direcciones.isNotEmpty()) { dao.replaceDirecciones(direcciones); saveSignature("direcciones", manifest) }
        if (dlFamilias && familias.isNotEmpty()) { dao.replaceFamilias(familias); saveSignature("familias", manifest) }
        if (dlProductos && productos.isNotEmpty()) { dao.replaceProductos(productos); saveSignature("productos", manifest) }
        if (dlRutas && rutas.isNotEmpty()) { dao.replaceRutas(rutas); saveSignature("rutas", manifest) }
        if (dlPrecios && precios.isNotEmpty()) { dao.replacePrecios(precios); saveSignature("precios", manifest) }
        if (dlCuenta && cuenta.isNotEmpty()) { cuentaDao.upsertAll(cuenta); saveSignature(cuentaSignatureKey, cuentaManifest) }

        val after = counts()
        return BootstrapResult(
            usuarios = 0,
            clientes = after.clientes,
            direcciones = after.direcciones,
            productos = after.productos,
            familias = after.familias,
            precios = after.precios,
            rutas = after.rutas,
            cartola = after.cartola,
            warnings = warnings.distinct()
        )
    }
    suspend fun counts(): LocalCounts = LocalCounts(clientes = dao.countClientes(), direcciones = dao.countDirecciones(), productos = dao.countProductos(), familias = dao.countFamilias(), precios = dao.countPrecios(), rutas = dao.countRutas(), cartola = cuentaDao.count())
    suspend fun verifyBootstrap(): SyncVerificationResult {
        val manifest = api.bootstrapManifest()
        val local = counts()
        val expected = mapOf(
            "clientes" to manifestCount("clientes", manifest),
            "direcciones" to manifestCount("direcciones", manifest),
            "productos" to manifestCount("productos", manifest),
            "familias" to manifestCount("familias", manifest),
            "precios" to manifestCount("precios", manifest),
            "rutas" to manifestCount("rutas", manifest),
            "cartola" to manifestCount("cuenta_corriente", manifest)
        )
        val actual = mapOf(
            "clientes" to local.clientes,
            "direcciones" to local.direcciones,
            "productos" to local.productos,
            "familias" to local.familias,
            "precios" to local.precios,
            "rutas" to local.rutas,
            "cartola" to local.cartola
        )
        return SyncVerificationResult(expected, actual)
    }

    suspend fun syncCuentaCorrienteCompleta(): Int {
        val cuentaDto = retryDownload("cuenta corriente completa") {
            descargarPorPaginas { limit, offset -> api.bootstrapCuentaCorriente(limit, offset, null) }
        }
        val cuenta = cuentaDto
            .filter { it.clienteRut.isNotBlank() && it.ventaTipo.isNotBlank() && it.ventaNumero > 0 }
            .map { cuentaCorrienteEntityFromDto(it) }
        if (cuenta.isNotEmpty()) cuentaDao.upsertAll(cuenta)
        return cuenta.size
    }

    suspend fun cuentaCorrienteOnline(rut: String): List<CuentaCorrienteEntity> {
        val dto = retryDownload("cuenta corriente online") { api.cuentaCorrienteDetalle(rut.trim()) }
        return dto
            .filter { it.clienteRut.isNotBlank() && it.ventaTipo.isNotBlank() && it.ventaNumero > 0 }
            .map { cuentaCorrienteEntityFromDto(it) }
    }

    suspend fun cuentaCorrienteOffline(rut: String): List<CuentaCorrienteEntity> =
        cuentaDao.porClienteConFolio(rut.trim())
    suspend fun syncClientesDesbloqueados(): Pair<Int, Int> {
        val clientesDto = try { descargarClientesPorPaginas(desbloqueados = true) } catch (e: Exception) { throw RuntimeException("Error descargando clientes desbloqueados: ${e.message}", e) }
        val direccionesDto = try { descargarPorPaginas { limit, offset -> api.bootstrapDireccionesDesbloqueados(limit, offset) } } catch (e: Exception) { throw RuntimeException("Error descargando direcciones desbloqueadas: ${e.message}", e) }
        val clientes = clientesDto
            .filter { it.clienteRut.isNotBlank() }
            .map { ClienteEntity(it.clienteRut.trim(), it.clienteNombre.orEmpty(), it.clienteDireccion, it.ciudadCodigo, it.comuna, it.clienteEstado, it.rutaId, it.listaCodigo, it.clienteGeo, it.clienteVendedor) }
        val direcciones = direccionesDto
            .filter { it.clienteRut.isNotBlank() && it.direccion.isNotBlank() }
            .mapIndexed { idx, it ->
                ClienteDireccionEntity(
                    clienteRut = it.clienteRut.trim(),
                    direccionId = it.direccionId?.takeIf { id -> id.isNotBlank() } ?: "L4-${it.clienteRut.trim()}-$idx",
                    direccion = it.direccion.trim(),
                    comuna = it.comuna,
                    ciudadCodigo = it.ciudadCodigo
                )
            }
        dao.upsertClientes(clientes)
        dao.upsertDirecciones(direcciones)
        return clientes.size to direcciones.size
    }

    suspend fun syncClientePorRut(rut: String): ClienteEntity? {
        val clienteDto = try { api.bootstrapCliente(rut) } catch (e: Exception) { throw RuntimeException("Error descargando cliente $rut: ${e.message}", e) }
        val cliente = clienteDto
            .filter { it.clienteRut.isNotBlank() }
            .map { ClienteEntity(it.clienteRut.trim(), it.clienteNombre.orEmpty(), it.clienteDireccion, it.ciudadCodigo, it.comuna, it.clienteEstado, it.rutaId, it.listaCodigo, it.clienteGeo, it.clienteVendedor) }
            .firstOrNull()
            ?: return null
        val direccionesDto = try { api.bootstrapDireccionesCliente(cliente.clienteRut) } catch (e: Exception) { throw RuntimeException("Error descargando direcciones cliente ${cliente.clienteRut}: ${e.message}", e) }
        val direcciones = direccionesDto
            .filter { it.clienteRut.isNotBlank() && it.direccion.isNotBlank() }
            .mapIndexed { idx, it ->
                ClienteDireccionEntity(
                    clienteRut = it.clienteRut.trim(),
                    direccionId = it.direccionId?.takeIf { id -> id.isNotBlank() } ?: "L4-${it.clienteRut.trim()}-$idx",
                    direccion = it.direccion.trim(),
                    comuna = it.comuna,
                    ciudadCodigo = it.ciudadCodigo
                )
            }
        dao.upsertClientes(listOf(cliente))
        dao.replaceDireccionesCliente(cliente.clienteRut, direcciones)
        return cliente
    }
    private fun productoEntityFromDto(it: ProductoDto, familiaNombre: Map<String, String?> = emptyMap()): ProductoEntity =
        ProductoEntity(it.productoCodigo.trim(), it.productoDescripcion, it.familiaCodigo, it.productoEstado, it.productoUnidadEnvase, it.productoGramaje, it.productoDescuento, it.productoVenta, it.familiaDescripcion ?: familiaNombre[it.familiaCodigo], it.stockActual, it.stockFecha, null)

    private fun precioEntityFromDto(it: PrecioDto): PrecioEntity =
        PrecioEntity(
            productoCodigo = it.productoCodigo.trim(),
            listaCodigo = it.listaCodigo.trim(),
            listaNeto = it.listaNeto,
            listaVenta = it.listaVenta ?: it.listaNeto,
            listaIla = it.listaIla ?: 0L
        )

    suspend fun syncProductoPorCodigo(codigo: String): List<ProductoEntity> {
        val productosDto = runCatching { api.bootstrapProductoCodigo(codigo) }.getOrDefault(emptyList())
        val preciosDto = runCatching { api.bootstrapPreciosProducto(codigo) }.getOrDefault(emptyList())
        val productos = productosDto
            .filter { it.productoCodigo.isNotBlank() && it.familiaCodigo?.trim() !in EXCLUDED_FAMILIES && !it.familiaDescripcion.equals("INACTIVOS", ignoreCase = true) }
            .map { productoEntityFromDto(it) }
        val precios = preciosDto
            .filter { it.productoCodigo.isNotBlank() && it.listaCodigo.isNotBlank() }
            .map { precioEntityFromDto(it) }
        if (productos.isNotEmpty()) dao.upsertProductos(productos)
        if (precios.isNotEmpty()) dao.upsertPrecios(precios)
        return productos
    }
    suspend fun searchClientes(q: String) = dao.searchClientes(q)
    suspend fun clientePorRut(rut: String) = dao.clientePorRut(rut)
    fun observeClientes(): Flow<List<ClienteEntity>> = dao.observeClientes()
    fun familias(): Flow<List<FamiliaEntity>> = dao.observeFamilias()
    suspend fun searchProductos(q: String, exactCode: Boolean, familiaCodigo: String? = null): List<ProductoEntity> {
        val normalized = normalizeSearchText(q)
        val base = when {
            !familiaCodigo.isNullOrBlank() && exactCode -> dao.searchProductosCodigoExactoPorFamilia(familiaCodigo, q.trim())
            !familiaCodigo.isNullOrBlank() -> dao.searchProductosPorFamiliaYTexto(familiaCodigo, normalized)
            exactCode -> {
                val codigo = q.trim()
                dao.searchProductosCodigoExacto(codigo)
            }
            else -> dao.searchProductosTexto(normalized)
        }
        return if (familiaCodigo.isNullOrBlank()) {
            base.sortedWith(
                compareBy<ProductoEntity>(
                    { it.familiaCodigo?.padStart(8, '0') ?: "ZZZZZZZZ" },
                    { normalizeSearchText(it.familiaDescripcion ?: "") },
                    { normalizeSearchText(it.productoDescripcion ?: "") },
                    { it.productoCodigo }
                )
            )
        } else {
            base.sortedWith(compareBy({ normalizeSearchText(it.productoDescripcion ?: "") }, { it.productoCodigo }))
        }
    }
    suspend fun productosPorFamilia(familiaCodigo: String, limit: Int, offset: Int, q: String = ""): List<ProductoEntity> {
        val normalized = normalizeSearchText(q)
        return if (normalized.isBlank()) dao.productosPorFamilia(familiaCodigo, limit, offset)
        else dao.productosPorFamiliaYDescripcion(familiaCodigo, normalized, limit, offset)
    }
    suspend fun countProductosPorFamilia(familiaCodigo: String, q: String = ""): Int {
        val normalized = normalizeSearchText(q)
        return if (normalized.isBlank()) dao.countProductosPorFamilia(familiaCodigo)
        else dao.countProductosPorFamiliaYDescripcion(familiaCodigo, normalized)
    }
    suspend fun direcciones(clienteRut: String) = dao.direcciones(clienteRut)
    suspend fun precio(producto: String, lista: String?) = dao.precio(producto.trim(), DEFAULT_PRICE_LIST)
}

data class SyncVerificationResult(
    val expected: Map<String, Int>,
    val actual: Map<String, Int>
) {
    val mismatches: List<String> = expected.keys
        .filter { (expected[it] ?: 0) > 0 && expected[it] != actual[it] }
    val ok: Boolean = mismatches.isEmpty()
}

class NvRepository(private val api: RisekApi, private val dao: NvDao, private val session: SessionStore) {
    fun observeNv() = dao.observeHeaders()
    suspend fun createOrUpdateLocal(cliente: ClienteEntity, direccion: String?, lines: List<NvLineEntity>, fechaReparto: String, observacion: String?, editOfflineId: String? = null): String {
        require(fechaReparto.isNotBlank()) { "Debe indicar fecha de reparto" }
        require(cliente.clienteEstado != "B") { "Cliente bloqueado: no se puede vender" }
        require(!direccion.isNullOrBlank()) { "Debe seleccionar dirección de reparto" }
        require(lines.isNotEmpty()) { "La NV no tiene productos" }
        require(lines.size <= 17) { "La NV no puede superar 17 líneas" }
        val vendedorScope = session.vendedorCodigo()?.trim()?.takeIf { it.isNotBlank() } ?: "AND"
        val offlineId = editOfflineId ?: "${vendedorScope}-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val neto = lines.sumOf { it.netoLinea }
        val iva = lines.sumOf { it.ivaLinea }
        val total = lines.sumOf { it.totalLinea }
        val fixedLines = lines.map { it.copy(offlineId = offlineId) }
        val existing = editOfflineId?.let { dao.header(it) }
        require(existing == null || existing.facturado != "S") { "NV facturada: no se puede modificar" }
        val header = NvHeaderEntity(
            offlineId=offlineId,
            ventaNumeroServidor=existing?.ventaNumeroServidor,
            clienteRut=cliente.clienteRut,
            clienteNombre=cliente.clienteNombre,
            vendedorCodigo=session.vendedorCodigo(),
            fecha=existing?.fecha ?: LocalDate.now().toString(),
            fechaReparto=fechaReparto,
            direccion=direccion,
            neto=neto,
            iva=iva,
            total=total,
            observacion=observacion?.take(200),
            syncStatus="PENDIENTE",
            createdAt=existing?.createdAt ?: System.currentTimeMillis()
        )
        dao.insertNv(header, fixedLines)
        return offlineId
    }
    suspend fun lines(offlineId: String) = dao.lines(offlineId)
    suspend fun requestDeleteNv(h: NvHeaderEntity): Boolean {
        require(h.facturado != "S") { "NV facturada: no se puede eliminar" }
        if (h.ventaNumeroServidor != null || h.syncStatus == "SINCRONIZADO") {
            require(h.ventaNumeroServidor != null) { "NV sincronizada sin número servidor: no se puede eliminar" }
            try {
                val res = api.deleteNv(NvDeleteRequest(h.offlineId, h.ventaNumeroServidor, h.localCodigo))
                if (!res.ok) error(res.message ?: "Servidor no confirmo eliminacion")
                dao.deleteNvLocal(h.offlineId)
                return true
            } catch (e: Exception) {
                if (e is HttpException && e.code() == 409) {
                    throw IllegalStateException(apiErrorMessage(e))
                }
                dao.markDeletePending(h.offlineId)
                return false
            }
        } else {
            dao.deleteNvLocal(h.offlineId)
            return true
        }
    }
    suspend fun ultimasNvCliente(rut: String): List<PedidoClienteDto> = api.ultimasNvCliente(rut)
    suspend fun cuentaCorriente(rut: String): CuentaCorrienteDto = api.cuentaCorriente(rut)
    suspend fun ultimaVentaDetalle(rut: String): UltimaVentaDetalleDto = api.ultimaVentaDetalle(rut)
    suspend fun ventaDocumentoDetalle(doc: CuentaCorrienteEntity): UltimaVentaDetalleDto = api.ventaDocumentoDetalle(doc.ventaTipo, doc.ventaNumero, doc.clienteRut)
    suspend fun supervisorDashboard(): SupervisorDashboardDto = api.supervisorDashboard()
    suspend fun supervisorVendedorResumen(codigo: String): SupervisorVendedorDetalleDto = api.supervisorVendedorResumen(codigo)
    suspend fun gerenteDashboard(): GerenteDashboardDto = api.gerenteDashboard()
    suspend fun logisticaDashboard(fecha: String? = null): LogisticaDashboardDto = api.logisticaDashboard(fecha)
    suspend fun logisticaEstado(req: LogisticaEstadoRequest): SimpleResponse = api.logisticaEstado(req)
    suspend fun enviarResumenVentasDia(email: String, mes: Int, ano: Int): SimpleResponse =
        api.enviarResumenVentasDia(ResumenVentasEmailRequest(email, session.vendedorCodigo(), mes, ano))
    suspend fun counts(): LocalCounts = LocalCounts(nvPendientes = dao.countPending(), nvError = dao.countError(), nvSincronizadas = dao.countSynced())
    suspend fun refreshServerStatuses(): Int {
        var updated = 0
        val statuses = api.nvStatuses()
        for (s in statuses) {
            if (s.offlineId.isNotBlank()) {
                dao.markFacturado(s.offlineId, s.ventaFacturado ?: "N", s.ventaNumero)
                updated++
            }
        }
        return updated
    }
    suspend fun syncPendingOnce(): Int {
        var ok = 0
        for (h in dao.pending()) {
            dao.mark(h.offlineId, "SINCRONIZANDO", h.ventaNumeroServidor, null)
            try {
                if (h.syncStatus == "ELIMINAR_PENDIENTE") {
                    val res = api.deleteNv(NvDeleteRequest(h.offlineId, h.ventaNumeroServidor, h.localCodigo))
                    if (res.ok) {
                        dao.deleteNvLocal(h.offlineId)
                        ok++
                    } else {
                        dao.mark(h.offlineId, "ERROR", h.ventaNumeroServidor, res.message ?: "Servidor no confirmó eliminación")
                    }
                } else {
                    val lines = dao.lines(h.offlineId)
                    val ila = lines.sumOf { it.ilaLinea }
                    val req = NvSyncRequest(
                        h.offlineId, h.localCodigo, h.bodegaCodigo, h.clienteRut, h.vendedorCodigo, h.fecha, h.fechaReparto, h.direccion,
                        h.neto, h.iva, ila, h.total, h.observacion, h.createdAt,
                        lines.map { NvLineSyncRequest(it.productoCodigo, it.descripcion, it.uxe, it.cantidad, it.precio, it.descuento, it.netoLinea, it.ivaLinea, it.ilaLinea, it.totalLinea, it.bodegaCodigo) }
                    )
                    val res = api.syncNv(req)
                    if (res.ok && res.ventaNumero != null) {
                        if (h.ventaNumeroServidor != null && res.alreadySynced) {
                            dao.mark(h.offlineId, "ERROR", h.ventaNumeroServidor, "Servidor no aplicó la edición; actualice la API para modificar NV sincronizadas")
                        } else {
                            dao.mark(h.offlineId, "SINCRONIZADO", res.ventaNumero, null)
                            ok++
                        }
                    } else {
                        dao.mark(h.offlineId, "ERROR", h.ventaNumeroServidor, res.message ?: "Servidor no confirmó número de NV")
                    }
                }
            } catch (e: Exception) { dao.mark(h.offlineId, "ERROR", h.ventaNumeroServidor, apiErrorMessage(e)) }
        }
        runCatching { refreshServerStatuses() }
        return ok
    }
}

fun buildLine(producto: ProductoEntity, precioNetoLista: Long, precioVentaLista: Long, listaIla: Long = 0, uxe: Double, descuento: Double = 0.0, cajas: Double = 0.0): NvLineEntity {
    require(descuento >= 0.0) { "El descuento no puede ser negativo" }
    val descuentoMaximo = producto.productoDescuento ?: 0.0
    require(descuento <= descuentoMaximo) { "Descuento máximo permitido para ${producto.productoCodigo}: ${stockText(descuentoMaximo)}%" }
    require(descuento < 100.0) { "El descuento no puede ser 100% o mayor" }
    val unidadesEnvase = producto.productoUnidadEnvase ?: 0.0
    val unidadesCalculadas = if (cajas > 0.0) {
        require(unidadesEnvase > 0.0) { "Producto sin unidad por envase para calcular cajas" }
        cajas * unidadesEnvase
    } else {
        uxe
    }
    val uxeFinal = unidadesCalculadas
    val cantidad = 1.0
    require(uxeFinal > 0.0) { "UXE o cajas debe ser mayor a cero" }
    val netoBase = uxeFinal * precioNetoLista
    val ilaBase = uxeFinal * listaIla
    val totalBase = uxeFinal * precioVentaLista
    val neto = (netoBase - (netoBase * descuento / 100.0)).roundToLong()
    val ila = (ilaBase - (ilaBase * descuento / 100.0)).roundToLong()
    val total = (totalBase - (totalBase * descuento / 100.0)).roundToLong()
    val iva = (total - neto - ila).coerceAtLeast(0)
    return NvLineEntity("TEMP", producto.productoCodigo, producto.productoDescripcion, uxeFinal, cantidad, precioNetoLista, descuento, neto, iva, ila, total, Config.BODEGA_CODIGO)
}
