package cl.risek.offline

import androidx.room.*
import com.google.gson.annotations.SerializedName

@Entity(tableName = "sec_users")
data class SecUserEntity(
    @PrimaryKey val secUserId: Int,
    val secUserName: String,
    val vendedorCodigo: String?
)

@Entity(tableName = "clientes")
data class ClienteEntity(
    @PrimaryKey val clienteRut: String,
    val clienteNombre: String,
    val clienteDireccion: String?,
    val ciudadCodigo: String?,
    val comuna: String?,
    val clienteEstado: String?,
    val rutaId: Int?,
    val listaCodigo: String?,
    val clienteGeo: String?,
    val clienteVendedor: String?
)

@Entity(tableName = "cliente_direcciones", primaryKeys = ["clienteRut", "direccionId"])
data class ClienteDireccionEntity(
    val clienteRut: String,
    val direccionId: String,
    val direccion: String,
    val comuna: String?,
    val ciudadCodigo: String?
)

@Entity(tableName = "rutas")
data class RutaEntity(@PrimaryKey val rutaId: Int, val rutaNombre: String?)

@Entity(tableName = "productos")
data class ProductoEntity(
    @PrimaryKey val productoCodigo: String,
    val productoDescripcion: String?,
    val familiaCodigo: String?,
    val productoEstado: String?,
    val productoUnidadEnvase: Double?,
    val productoGramaje: Double?,
    val productoDescuento: Double?,
    val productoVenta: Long?,
    val familiaDescripcion: String? = null,
    val stockActual: Double = 0.0,
    val stockFecha: String? = null,
    val fotoBase64: String? = null
)

@Entity(tableName = "familias")
data class FamiliaEntity(
    @PrimaryKey val familiaCodigo: String,
    val familiaDescripcion: String?,
    val familiaRestaurant: String?
)

@Entity(tableName = "precios", primaryKeys = ["productoCodigo", "listaCodigo"])
data class PrecioEntity(
    val productoCodigo: String,
    val listaCodigo: String,
    val listaNeto: Long,
    val listaVenta: Long = listaNeto,
    val listaIla: Long = 0
)

@Entity(tableName = "cuenta_corriente", primaryKeys = ["clienteRut", "ventaTipo", "ventaNumero"])
data class CuentaCorrienteEntity(
    val clienteRut: String,
    val clienteNombre: String?,
    val ventaNumero: Long,
    val ventaTipo: String,
    val ventaFecha: String?,
    val ventaTotalVenta: Long,
    val ventaPagoTotal: Long,
    val saldo: Long,
    val folio: Long? = null,
    @SerializedName("folio_sii") val folioSii: Long? = null,
    @SerializedName("estado_sii") val estadoSii: String? = null
)

@Entity(tableName = "nv_headers")
data class NvHeaderEntity(
    @PrimaryKey val offlineId: String,
    val ventaNumeroServidor: Long? = null,
    val clienteRut: String,
    val clienteNombre: String,
    val vendedorCodigo: String?,
    val localCodigo: String = Config.LOCAL_CODIGO,
    val bodegaCodigo: String = Config.BODEGA_CODIGO,
    val fecha: String,
    val fechaReparto: String,
    val direccion: String?,
    val neto: Long,
    val iva: Long,
    val total: Long,
    val observacion: String? = null,
    val facturado: String = "N",
    val syncStatus: String = "PENDIENTE",
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "nv_lines", primaryKeys = ["offlineId", "productoCodigo"])
data class NvLineEntity(
    val offlineId: String,
    val productoCodigo: String,
    val descripcion: String?,
    val uxe: Double,
    val cantidad: Double,
    val precio: Long,
    val descuento: Double,
    val netoLinea: Long,
    val ivaLinea: Long,
    val ilaLinea: Long = 0,
    val totalLinea: Long,
    @SerializedName("bodega_codigo") val bodegaCodigo: String = Config.BODEGA_CODIGO
)

data class LoginRequest(@SerializedName("sec_user_id") val secUserId: Int, val password: String)
data class LoginResponse(val ok: Boolean, val token: String?, @SerializedName("vendedor_codigo") val vendedorCodigo: String?, val message: String?)
data class SecUserDto(@SerializedName("sec_user_id") val secUserId: Int, @SerializedName("sec_user_name") val secUserName: String, @SerializedName("vendedor_codigo") val vendedorCodigo: String?)
data class ClienteDto(@SerializedName("cliente_rut") val clienteRut: String, @SerializedName("cliente_nombre") val clienteNombre: String?, @SerializedName("cliente_direccion") val clienteDireccion: String?, @SerializedName("Ciudad_codigo") val ciudadCodigo: String?, @SerializedName("Comuna") val comuna: String?, @SerializedName("cliente_estado") val clienteEstado: String?, @SerializedName("ruta_id") val rutaId: Int?, @SerializedName("lista_codigo") val listaCodigo: String?, @SerializedName("cliente_geo") val clienteGeo: String?, @SerializedName("cliente_vendedor") val clienteVendedor: String?)
data class ClienteDireccionDto(@SerializedName("direccion_id") val direccionId: String?, @SerializedName("cliente_rut") val clienteRut: String, @SerializedName("direccion") val direccion: String, @SerializedName("comuna") val comuna: String?, @SerializedName("ciudad_codigo") val ciudadCodigo: String?)
data class ProductoDto(@SerializedName("producto_codigo") val productoCodigo: String, @SerializedName("producto_descripcion") val productoDescripcion: String?, @SerializedName("familia_codigo") val familiaCodigo: String?, @SerializedName("familia_descripcion") val familiaDescripcion: String?, @SerializedName("producto_estado") val productoEstado: String?, @SerializedName("producto_unidadenvase") val productoUnidadEnvase: Double?, @SerializedName("producto_gramaje") val productoGramaje: Double?, @SerializedName("producto_descuento") val productoDescuento: Double?, @SerializedName("producto_venta") val productoVenta: Long?, @SerializedName("stock_actual") val stockActual: Double = 0.0, @SerializedName("stock_fecha") val stockFecha: String? = null)
data class FamiliaDto(@SerializedName("familia_codigo") val familiaCodigo: String, @SerializedName("familia_descripcion") val familiaDescripcion: String?, @SerializedName("familia_restaurant") val familiaRestaurant: String?)
data class RutaDto(@SerializedName("ruta_id") val rutaId: Int, @SerializedName("ruta_nombre") val rutaNombre: String?)
data class PrecioDto(@SerializedName("producto_codigo") val productoCodigo: String, @SerializedName("lista_codigo") val listaCodigo: String, @SerializedName("lista_neto") val listaNeto: Long, @SerializedName("lista_venta") val listaVenta: Long? = null, @SerializedName("lista_ila") val listaIla: Long? = null)
data class CuentaCorrienteItemDto(
    @SerializedName("cliente_rut") val clienteRut: String,
    @SerializedName("cliente_nombre") val clienteNombre: String?,
    @SerializedName("venta_numero") val ventaNumero: Long,
    @SerializedName("venta_tipo") val ventaTipo: String,
    @SerializedName("venta_fecha") val ventaFecha: String?,
    @SerializedName("venta_totalventa") val ventaTotalVenta: Long,
    @SerializedName("venta_pagototal") val ventaPagoTotal: Long,
    @SerializedName("venta_folio") val folio: Long? = null,
    @SerializedName("venta_foliosii") val folioSii: Long? = null,
    @SerializedName("venta_estadosii") val estadoSii: String? = null,
    val saldo: Long
)
data class BootstrapDto(val clientes: List<ClienteDto>, val productos: List<ProductoDto>, val rutas: List<RutaDto>, val precios: List<PrecioDto>, val direcciones: List<ClienteDireccionDto> = emptyList(), val familias: List<FamiliaDto> = emptyList())

data class HealthResponse(val ok: Boolean, val service: String?)
data class AppVersionDto(
    val available: Boolean,
    @SerializedName("version_code") val versionCode: Int,
    @SerializedName("version_name") val versionName: String?,
    @SerializedName("apk_url") val apkUrl: String?,
    val notes: String?
)

data class LocalCounts(
    val usuarios: Int = 0,
    val clientes: Int = 0,
    val direcciones: Int = 0,
    val productos: Int = 0,
    val familias: Int = 0,
    val precios: Int = 0,
    val rutas: Int = 0,
    val cartola: Int = 0,
    val nvPendientes: Int = 0,
    val nvError: Int = 0,
    val nvSincronizadas: Int = 0
)

data class BootstrapResult(
    val usuarios: Int,
    val clientes: Int,
    val direcciones: Int,
    val productos: Int,
    val familias: Int,
    val precios: Int,
    val rutas: Int,
    val cartola: Int = 0,
    val warnings: List<String> = emptyList(),
    val ventasOperativas: Boolean = clientes > 0 && direcciones > 0 && productos > 0 && precios > 0
)

data class NvSyncRequest(
    @SerializedName("offline_id") val offlineId: String,
    @SerializedName("local_codigo") val localCodigo: String,
    @SerializedName("bodega_codigo") val bodegaCodigo: String = Config.BODEGA_CODIGO,
    @SerializedName("cliente_rut") val clienteRut: String,
    @SerializedName("vendedor_codigo") val vendedorCodigo: String?,
    @SerializedName("venta_fecha") val ventaFecha: String,
    @SerializedName("venta_fechavto") val ventaFechaVto: String,
    @SerializedName("venta_direccion") val ventaDireccion: String?,
    @SerializedName("venta_neto") val ventaNeto: Long,
    @SerializedName("venta_iva") val ventaIva: Long,
    @SerializedName("venta_ila") val ventaIla: Long = 0,
    @SerializedName("venta_totalventa") val ventaTotalVenta: Long,
    @SerializedName("venta_observacion01") val ventaObservacion01: String? = null,
    @SerializedName("venta_guardado_ms") val ventaGuardadoMs: Long? = null,
    val lines: List<NvLineSyncRequest>
)
data class NvLineSyncRequest(
    @SerializedName("producto_codigo") val productoCodigo: String,
    val descripcion: String?,
    val uxe: Double,
    val cantidad: Double,
    val precio: Long,
    val descuento: Double,
    @SerializedName("neto_linea") val netoLinea: Long,
    @SerializedName("iva_linea") val ivaLinea: Long,
    @SerializedName("ila_linea") val ilaLinea: Long = 0,
    @SerializedName("total_linea") val totalLinea: Long,
    @SerializedName("bodega_codigo") val bodegaCodigo: String = Config.BODEGA_CODIGO
)
data class NvSyncResponse(val ok: Boolean, @SerializedName("venta_numero") val ventaNumero: Long?, @SerializedName("already_synced") val alreadySynced: Boolean, val message: String?)

data class NvDeleteRequest(
    @SerializedName("offline_id") val offlineId: String,
    @SerializedName("venta_numero") val ventaNumero: Long?,
    @SerializedName("local_codigo") val localCodigo: String = Config.LOCAL_CODIGO
)
data class NvDeleteResponse(val ok: Boolean, @SerializedName("venta_numero") val ventaNumero: Long?, val message: String?)


data class NvStatusDto(@SerializedName("offline_id") val offlineId: String, @SerializedName("venta_numero") val ventaNumero: Long?, @SerializedName("venta_facturado") val ventaFacturado: String?, @SerializedName("venta_fecha") val ventaFecha: String?)


data class PedidoClienteDto(
    @SerializedName("venta_numero") val ventaNumero: Long,
    @SerializedName("venta_fecha") val ventaFecha: String?,
    @SerializedName("venta_fechavto") val ventaFechaVto: String?,
    @SerializedName("venta_facturado") val ventaFacturado: String?,
    @SerializedName("venta_totalventa") val ventaTotalVenta: Long?,
    @SerializedName("venta_neto1") val ventaNeto: Long?,
    @SerializedName("venta_iva1") val ventaIva: Long?,
    @SerializedName("venta_observacion01") val observacion: String?
)

data class CuentaCorrienteDto(
    @SerializedName("cliente_rut") val clienteRut: String,
    @SerializedName("documentos") val documentos: Int,
    @SerializedName("saldo") val saldo: Long
)

data class UltimaVentaLineaDto(
    @SerializedName("producto_codigo") val productoCodigo: String,
    val descripcion: String?,
    val cantidad: Double?,
    val uxe: Double?,
    val precio: Long?,
    @SerializedName("total_linea") val totalLinea: Long?
)

data class UltimaVentaDetalleDto(
    @SerializedName("cliente_rut") val clienteRut: String,
    @SerializedName("venta_numero") val ventaNumero: Long?,
    @SerializedName("venta_tipo") val ventaTipo: String?,
    @SerializedName("venta_fecha") val ventaFecha: String?,
    @SerializedName("venta_totalventa") val ventaTotalVenta: Long?,
    val lines: List<UltimaVentaLineaDto> = emptyList()
)

data class SupervisorSummaryDto(
    @SerializedName("nv_hoy") val nvHoy: Int = 0,
    @SerializedName("facturas_hoy") val facturasHoy: Int = 0,
    @SerializedName("documentos_hoy") val documentosHoy: Int = 0,
    @SerializedName("nc_hoy") val ncHoy: Int = 0,
    @SerializedName("venta_total") val ventaTotal: Long = 0,
    val clientes: Int = 0,
    @SerializedName("facturas_30") val facturas30: Int = 0,
    @SerializedName("documentos_30") val documentos30: Int = 0,
    @SerializedName("boletas_30") val boletas30: Int = 0,
    @SerializedName("nc_30") val nc30: Int = 0,
    @SerializedName("venta_30") val venta30: Long = 0,
    @SerializedName("clientes_30") val clientes30: Int = 0,
    @SerializedName("ticket_promedio") val ticketPromedio: Long = 0,
    val pendientes: Int = 0
)

data class SupervisorVendedorDto(
    @SerializedName("vendedor_codigo") val vendedorCodigo: String?,
    @SerializedName("vendedor_nombre") val vendedorNombre: String?,
    val nv: Int = 0,
    val facturas: Int = 0,
    val documentos: Int = 0,
    val total: Long = 0,
    val clientes: Int = 0
)

data class SupervisorRutaDto(
    @SerializedName("ruta_id") val rutaId: String?,
    @SerializedName("ruta_nombre") val rutaNombre: String?,
    val nv: Int = 0,
    val facturas: Int = 0,
    val documentos: Int = 0,
    val venta: Long = 0,
    val clientes: Int = 0
)

data class SupervisorTrendDto(
    val fecha: String?,
    val total: Long = 0
)

data class SupervisorProductoDto(
    @SerializedName("producto_codigo") val productoCodigo: String?,
    @SerializedName("producto_descripcion") val productoDescripcion: String?,
    @SerializedName("familia_codigo") val familiaCodigo: String?,
    @SerializedName("familia_descripcion") val familiaDescripcion: String?,
    @SerializedName("uxe_total") val uxeTotal: Double = 0.0,
    val total: Long = 0
)

data class SupervisorFamiliaDto(
    @SerializedName("familia_codigo") val familiaCodigo: String?,
    @SerializedName("familia_descripcion") val familiaDescripcion: String?,
    @SerializedName("uxe_total") val uxeTotal: Double = 0.0,
    val total: Long = 0
)

data class SupervisorDashboardDto(
    val summary: SupervisorSummaryDto = SupervisorSummaryDto(),
    val vendedores: List<SupervisorVendedorDto> = emptyList(),
    val rutas: List<SupervisorRutaDto> = emptyList(),
    val trend: List<SupervisorTrendDto> = emptyList(),
    val productos: List<SupervisorProductoDto> = emptyList(),
    val familias: List<SupervisorFamiliaDto> = emptyList()
)

data class SupervisorVendedorSummaryDto(
    val facturas: Int = 0,
    val boletas: Int = 0,
    @SerializedName("notas_credito") val notasCredito: Int = 0,
    val documentos: Int = 0,
    val clientes: Int = 0,
    @SerializedName("venta_facturas") val ventaFacturas: Long = 0,
    @SerializedName("venta_boletas") val ventaBoletas: Long = 0,
    @SerializedName("venta_total") val ventaTotal: Long = 0,
    @SerializedName("total_nc") val totalNc: Long = 0,
    @SerializedName("ticket_promedio") val ticketPromedio: Long = 0
)

data class SupervisorVendedorClienteDto(
    @SerializedName("cliente_rut") val clienteRut: String?,
    @SerializedName("cliente_nombre") val clienteNombre: String?,
    val documentos: Int = 0,
    val total: Long = 0,
    @SerializedName("ultima_fecha") val ultimaFecha: String?
)

data class SupervisorVendedorDetalleDto(
    @SerializedName("vendedor_codigo") val vendedorCodigo: String?,
    @SerializedName("vendedor_nombre") val vendedorNombre: String?,
    val summary: SupervisorVendedorSummaryDto = SupervisorVendedorSummaryDto(),
    val trend: List<SupervisorTrendDto> = emptyList(),
    val clientes: List<SupervisorVendedorClienteDto> = emptyList(),
    val productos: List<SupervisorProductoDto> = emptyList()
)

data class GerenteSummaryDto(
    @SerializedName("ventas_30") val ventas30: Long = 0,
    @SerializedName("facturas_30") val facturas30: Int = 0,
    @SerializedName("boletas_30") val boletas30: Int = 0,
    @SerializedName("nc_30") val nc30: Int = 0,
    @SerializedName("ventas_mes") val ventasMes: Long = 0,
    @SerializedName("facturas_mes") val facturasMes: Int = 0,
    @SerializedName("boletas_mes") val boletasMes: Int = 0,
    @SerializedName("nc_mes") val ncMes: Int = 0,
    @SerializedName("deuda_total") val deudaTotal: Long = 0,
    @SerializedName("cobranza_30") val cobranza30: Long = 0,
    @SerializedName("cobranza_mes") val cobranzaMes: Long = 0,
    @SerializedName("clientes_30") val clientes30: Int = 0,
    @SerializedName("clientes_mes") val clientesMes: Int = 0,
    @SerializedName("clientes_deuda") val clientesDeuda: Int = 0,
    @SerializedName("ticket_promedio") val ticketPromedio: Long = 0,
    @SerializedName("mes_actual") val mesActual: String? = null,
    @SerializedName("ventas_dia") val ventasDia: Long = 0,
    @SerializedName("ventas_local_01") val ventasLocal01: Long = 0,
    @SerializedName("ventas_local_02") val ventasLocal02: Long = 0
)

data class GerenteLocalVentaDto(
    @SerializedName("local_codigo") val localCodigo: String? = null,
    val total: Long = 0
)

data class GerenteLocalTrendDto(
    val fecha: String? = null,
    @SerializedName("total_01") val total01: Long = 0,
    @SerializedName("total_02") val total02: Long = 0
)

data class GerenteSectorDto(
    val sector: String? = null,
    val total: Long = 0,
    val clientes: Int = 0
)

data class GerenteYearCompareDto(
    val mes: Int = 0,
    @SerializedName("mes_label") val mesLabel: String? = null,
    @SerializedName("actual") val actual: Long = 0,
    @SerializedName("anterior") val anterior: Long = 0
)

data class GerenteNvMapaDto(
    @SerializedName("venta_numero") val ventaNumero: Long = 0,
    @SerializedName("cliente_rut") val clienteRut: String? = null,
    @SerializedName("cliente_nombre") val clienteNombre: String? = null,
    @SerializedName("cliente_geo") val clienteGeo: String? = null,
    val total: Long = 0
)

data class GerenteVendedorDto(
    @SerializedName("vendedor_codigo") val vendedorCodigo: String?,
    @SerializedName("vendedor_nombre") val vendedorNombre: String?,
    val facturas: Int = 0,
    val documentos: Int = 0,
    val total: Long = 0,
    val clientes: Int = 0
)

data class GerenteDeudaClienteDto(
    @SerializedName("cliente_rut") val clienteRut: String?,
    @SerializedName("cliente_nombre") val clienteNombre: String?,
    val saldo: Long = 0,
    val documentos: Int = 0,
    @SerializedName("ultima_fecha") val ultimaFecha: String?
)

data class GerenteDashboardDto(
    val summary: GerenteSummaryDto = GerenteSummaryDto(),
    val vendedores: List<GerenteVendedorDto> = emptyList(),
    val trend: List<SupervisorTrendDto> = emptyList(),
    @SerializedName("ventas_mes") val ventasMes: List<SupervisorTrendDto> = emptyList(),
    val deudas: List<GerenteDeudaClienteDto> = emptyList(),
    val alertas: List<String> = emptyList(),
    @SerializedName("ventas_locales") val ventasLocales: List<GerenteLocalVentaDto> = emptyList(),
    @SerializedName("ventas_mensuales_locales") val ventasMensualesLocales: List<GerenteLocalTrendDto> = emptyList(),
    @SerializedName("ventas_mes_locales") val ventasMesLocales: List<GerenteLocalTrendDto> = emptyList(),
    val sectores: List<GerenteSectorDto> = emptyList(),
    @SerializedName("ventas_anio_comparativo") val ventasAnioComparativo: List<GerenteYearCompareDto> = emptyList(),
    @SerializedName("nv_hoy_mapa") val nvHoyMapa: List<GerenteNvMapaDto> = emptyList()
)

data class LogisticaSummaryDto(
    val documentos: Int = 0,
    val pendientes: Int = 0,
    @SerializedName("en_ruta") val enRuta: Int = 0,
    val entregados: Int = 0,
    @SerializedName("no_entregados") val noEntregados: Int = 0,
    val total: Long = 0
)

data class LogisticaRutaDto(
    @SerializedName("ruta_id") val rutaId: String?,
    @SerializedName("ruta_nombre") val rutaNombre: String?,
    val documentos: Int = 0,
    val entregados: Int = 0,
    val pendientes: Int = 0,
    val total: Long = 0
)

data class LogisticaDocumentoDto(
    @SerializedName("venta_numero") val ventaNumero: Long,
    @SerializedName("venta_tipo") val ventaTipo: String,
    @SerializedName("local_codigo") val localCodigo: String,
    @SerializedName("fecha_reparto") val fechaReparto: String?,
    @SerializedName("cliente_rut") val clienteRut: String?,
    @SerializedName("cliente_nombre") val clienteNombre: String?,
    val direccion: String?,
    val comuna: String?,
    @SerializedName("ruta_id") val rutaId: String?,
    @SerializedName("ruta_nombre") val rutaNombre: String?,
    @SerializedName("cliente_geo") val clienteGeo: String?,
    val total: Long = 0,
    val estado: String = "PENDIENTE",
    val observacion: String? = null,
    val actualizado: String? = null
)

data class LogisticaDashboardDto(
    val fecha: String? = null,
    val summary: LogisticaSummaryDto = LogisticaSummaryDto(),
    val rutas: List<LogisticaRutaDto> = emptyList(),
    val documentos: List<LogisticaDocumentoDto> = emptyList()
)

data class LogisticaEstadoRequest(
    @SerializedName("venta_numero") val ventaNumero: Long,
    @SerializedName("venta_tipo") val ventaTipo: String,
    @SerializedName("local_codigo") val localCodigo: String,
    val estado: String,
    val observacion: String? = null
)

data class SimpleResponse(val ok: Boolean = false, val message: String? = null, val estado: String? = null)

data class ResumenVentasEmailRequest(
    val email: String,
    @SerializedName("vendedor_codigo") val vendedorCodigo: String?,
    val mes: Int,
    val ano: Int
)
