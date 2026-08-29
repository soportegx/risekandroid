package cl.risek.offline

import retrofit2.http.*

interface RisekApi {
    @GET("health") suspend fun health(): HealthResponse
    @GET("app/version") suspend fun appVersion(): AppVersionDto
    @GET("bootstrap/manifest") suspend fun bootstrapManifest(): Map<String, String>
    @GET("secusers") suspend fun secusers(): List<SecUserDto>
    @POST("login") suspend fun login(@Body req: LoginRequest): LoginResponse

    // Bootstrap separado. Evita payload único gigante y permite saber exactamente qué tabla falla.
    @GET("bootstrap/clientes") suspend fun bootstrapClientes(@Query("limit") limit: Int = 0, @Query("offset") offset: Int = 0): List<ClienteDto>
    @GET("bootstrap/clientes-desbloqueados") suspend fun bootstrapClientesDesbloqueados(@Query("limit") limit: Int = 0, @Query("offset") offset: Int = 0): List<ClienteDto>
    @GET("bootstrap/clientes/{rut}") suspend fun bootstrapCliente(@Path("rut") rut: String): List<ClienteDto>
    @GET("bootstrap/direcciones") suspend fun bootstrapDirecciones(@Query("limit") limit: Int = 0, @Query("offset") offset: Int = 0): List<ClienteDireccionDto>
    @GET("bootstrap/direcciones-cliente/{rut}") suspend fun bootstrapDireccionesCliente(@Path("rut") rut: String): List<ClienteDireccionDto>
    @GET("bootstrap/direcciones-desbloqueados") suspend fun bootstrapDireccionesDesbloqueados(@Query("limit") limit: Int = 0, @Query("offset") offset: Int = 0): List<ClienteDireccionDto>
    @GET("bootstrap/productos") suspend fun bootstrapProductos(@Query("limit") limit: Int = 0, @Query("offset") offset: Int = 0): List<ProductoDto>
    @GET("bootstrap/productos/{codigo}") suspend fun bootstrapProductoCodigo(@Path("codigo") codigo: String): List<ProductoDto>
    @GET("bootstrap/familias") suspend fun bootstrapFamilias(): List<FamiliaDto>
    @GET("bootstrap/precios") suspend fun bootstrapPrecios(@Query("limit") limit: Int = 0, @Query("offset") offset: Int = 0): List<PrecioDto>
    @GET("bootstrap/precios/{codigo}") suspend fun bootstrapPreciosProducto(@Path("codigo") codigo: String): List<PrecioDto>
    @GET("bootstrap/rutas") suspend fun bootstrapRutas(): List<RutaDto>
    @GET("bootstrap/cuenta-corriente") suspend fun bootstrapCuentaCorriente(@Query("limit") limit: Int = 0, @Query("offset") offset: Int = 0, @Query("vendedor_codigo") vendedorCodigo: String? = null): List<CuentaCorrienteItemDto>

    // Compatibilidad con versiones anteriores. No usar para carga principal.
    @GET("bootstrap") suspend fun bootstrap(): BootstrapDto

    @POST("nv/sync") suspend fun syncNv(@Body req: NvSyncRequest): NvSyncResponse
    @POST("nv/delete") suspend fun deleteNv(@Body req: NvDeleteRequest): NvDeleteResponse
    @GET("nv/statuses") suspend fun nvStatuses(): List<NvStatusDto>
    @GET("clientes/{rut}/ultimas-nv") suspend fun ultimasNvCliente(@Path("rut") rut: String): List<PedidoClienteDto>
    @GET("clientes/{rut}/cuenta-corriente") suspend fun cuentaCorriente(@Path("rut") rut: String): CuentaCorrienteDto
    @GET("clientes/{rut}/cuenta-corriente-detalle") suspend fun cuentaCorrienteDetalle(@Path("rut") rut: String): List<CuentaCorrienteItemDto>
    @GET("clientes/{rut}/ultima-venta-detalle") suspend fun ultimaVentaDetalle(@Path("rut") rut: String): UltimaVentaDetalleDto
    @GET("ventas/{tipo}/{numero}/detalle") suspend fun ventaDocumentoDetalle(@Path("tipo") tipo: String, @Path("numero") numero: Long, @Query("cliente_rut") rut: String, @Query("local_codigo") localCodigo: String = Config.LOCAL_CODIGO): UltimaVentaDetalleDto
    @GET("supervisor/dashboard") suspend fun supervisorDashboard(): SupervisorDashboardDto
    @GET("supervisor/vendedores/{codigo}/resumen") suspend fun supervisorVendedorResumen(@Path("codigo") codigo: String): SupervisorVendedorDetalleDto
    @GET("gerente/dashboard") suspend fun gerenteDashboard(): GerenteDashboardDto
    @GET("logistica/dashboard") suspend fun logisticaDashboard(@Query("fecha") fecha: String? = null): LogisticaDashboardDto
    @POST("logistica/estado") suspend fun logisticaEstado(@Body req: LogisticaEstadoRequest): SimpleResponse
    @POST("ventas/resumen-dia-email") suspend fun enviarResumenVentasDia(@Body req: ResumenVentasEmailRequest): SimpleResponse
}
