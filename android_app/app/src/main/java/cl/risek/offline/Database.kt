package cl.risek.offline

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao interface SecUserDao {
    @Query("SELECT * FROM sec_users ORDER BY secUserName") fun observeAll(): Flow<List<SecUserEntity>>
    @Query("SELECT * FROM sec_users ORDER BY secUserName") suspend fun getAll(): List<SecUserEntity>
    @Query("SELECT COUNT(*) FROM sec_users") suspend fun countUsers(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(items: List<SecUserEntity>)
}

@Dao interface CatalogDao {
    @Query("SELECT * FROM clientes WHERE clienteNombre LIKE '%' || :q || '%' OR clienteRut LIKE '%' || :q || '%' ORDER BY clienteNombre LIMIT 50") suspend fun searchClientes(q: String): List<ClienteEntity>
    @Query("SELECT * FROM clientes ORDER BY clienteNombre") fun observeClientes(): Flow<List<ClienteEntity>>
    @Query("SELECT * FROM clientes WHERE clienteRut=:rut LIMIT 1") suspend fun clientePorRut(rut: String): ClienteEntity?
    @Query("""
        SELECT * FROM productos
        WHERE (productoEstado IS NULL OR productoEstado <> 'I')
          AND (familiaCodigo IS NULL OR familiaCodigo NOT IN ('24','29','30'))
          AND (
            TRIM(productoCodigo) = TRIM(:codigo)
            OR (productoCodigo GLOB '[0-9]*' AND :codigo GLOB '[0-9]*' AND CAST(productoCodigo AS INTEGER) = CAST(:codigo AS INTEGER))
          )
        LIMIT 20
    """) suspend fun searchProductosCodigoExacto(codigo: String): List<ProductoEntity>
    @Query("""
        SELECT * FROM productos
        WHERE (productoEstado IS NULL OR productoEstado <> 'I')
        AND (familiaCodigo IS NULL OR familiaCodigo NOT IN ('24','29','30'))
        AND (
            productoCodigo LIKE '%' || :q || '%'
            OR familiaCodigo LIKE '%' || :q || '%'
            OR lower(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(COALESCE(productoDescripcion,''),'Á','A'),'É','E'),'Í','I'),'Ó','O'),'Ú','U'),'á','a'),'é','e'),'í','i'),'ó','o'),'ú','u')) LIKE '%' || :q || '%'
            OR lower(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(COALESCE(familiaDescripcion,''),'Á','A'),'É','E'),'Í','I'),'Ó','O'),'Ú','U'),'á','a'),'é','e'),'í','i'),'ó','o'),'ú','u')) LIKE '%' || :q || '%'
        )
        ORDER BY familiaDescripcion, productoDescripcion
        LIMIT 50
    """) suspend fun searchProductosTexto(q: String): List<ProductoEntity>
    @Query("SELECT * FROM familias WHERE familiaCodigo NOT IN ('24','29','30') AND UPPER(COALESCE(familiaDescripcion,'')) <> 'INACTIVOS' ORDER BY familiaDescripcion") fun observeFamilias(): Flow<List<FamiliaEntity>>
    @Query("SELECT * FROM productos WHERE (productoEstado IS NULL OR productoEstado <> 'I') AND (familiaCodigo IS NULL OR familiaCodigo NOT IN ('24','29','30')) AND familiaCodigo=:familiaCodigo ORDER BY productoDescripcion LIMIT :limit OFFSET :offset") suspend fun productosPorFamilia(familiaCodigo: String, limit: Int, offset: Int): List<ProductoEntity>
    @Query("SELECT COUNT(*) FROM productos WHERE (productoEstado IS NULL OR productoEstado <> 'I') AND (familiaCodigo IS NULL OR familiaCodigo NOT IN ('24','29','30')) AND familiaCodigo=:familiaCodigo") suspend fun countProductosPorFamilia(familiaCodigo: String): Int
    @Query("""
        SELECT * FROM productos
        WHERE (productoEstado IS NULL OR productoEstado <> 'I')
          AND (familiaCodigo IS NULL OR familiaCodigo NOT IN ('24','29','30'))
          AND familiaCodigo=:familiaCodigo
          AND (
            TRIM(productoCodigo) = TRIM(:codigo)
            OR (productoCodigo GLOB '[0-9]*' AND :codigo GLOB '[0-9]*' AND CAST(productoCodigo AS INTEGER) = CAST(:codigo AS INTEGER))
          )
        LIMIT 20
    """) suspend fun searchProductosCodigoExactoPorFamilia(familiaCodigo: String, codigo: String): List<ProductoEntity>
    @Query("""
        SELECT * FROM productos
        WHERE (productoEstado IS NULL OR productoEstado <> 'I')
        AND (familiaCodigo IS NULL OR familiaCodigo NOT IN ('24','29','30'))
        AND familiaCodigo=:familiaCodigo
        AND (
            productoCodigo LIKE '%' || :q || '%'
            OR familiaCodigo LIKE '%' || :q || '%'
            OR lower(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(COALESCE(productoDescripcion,''),'Á','A'),'É','E'),'Í','I'),'Ó','O'),'Ú','U'),'á','a'),'é','e'),'í','i'),'ó','o'),'ú','u')) LIKE '%' || :q || '%'
            OR lower(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(COALESCE(familiaDescripcion,''),'Á','A'),'É','E'),'Í','I'),'Ó','O'),'Ú','U'),'á','a'),'é','e'),'í','i'),'ó','o'),'ú','u')) LIKE '%' || :q || '%'
        )
        ORDER BY productoDescripcion
        LIMIT 50
    """) suspend fun searchProductosPorFamiliaYTexto(familiaCodigo: String, q: String): List<ProductoEntity>
    @Query("""
        SELECT * FROM productos
        WHERE (productoEstado IS NULL OR productoEstado <> 'I')
        AND (familiaCodigo IS NULL OR familiaCodigo NOT IN ('24','29','30'))
        AND familiaCodigo=:familiaCodigo
        AND (
            productoCodigo LIKE '%' || :q || '%'
            OR lower(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(COALESCE(productoDescripcion,''),'Á','A'),'É','E'),'Í','I'),'Ó','O'),'Ú','U'),'á','a'),'é','e'),'í','i'),'ó','o'),'ú','u')) LIKE '%' || :q || '%'
        )
        ORDER BY productoDescripcion
        LIMIT :limit OFFSET :offset
    """) suspend fun productosPorFamiliaYDescripcion(familiaCodigo: String, q: String, limit: Int, offset: Int): List<ProductoEntity>
    @Query("""
        SELECT COUNT(*) FROM productos
        WHERE (productoEstado IS NULL OR productoEstado <> 'I')
        AND (familiaCodigo IS NULL OR familiaCodigo NOT IN ('24','29','30'))
        AND familiaCodigo=:familiaCodigo
        AND (
            productoCodigo LIKE '%' || :q || '%'
            OR lower(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(COALESCE(productoDescripcion,''),'Á','A'),'É','E'),'Í','I'),'Ó','O'),'Ú','U'),'á','a'),'é','e'),'í','i'),'ó','o'),'ú','u')) LIKE '%' || :q || '%'
        )
    """) suspend fun countProductosPorFamiliaYDescripcion(familiaCodigo: String, q: String): Int
    @Query("SELECT * FROM cliente_direcciones WHERE clienteRut=:clienteRut ORDER BY direccion, comuna, ciudadCodigo") suspend fun direcciones(clienteRut: String): List<ClienteDireccionEntity>
    @Query("""
        SELECT * FROM precios
        WHERE TRIM(productoCodigo)=TRIM(:producto)
          AND TRIM(listaCodigo)=TRIM(:lista)
        LIMIT 1
    """) suspend fun precio(producto: String, lista: String): PrecioEntity?
    @Query("SELECT COUNT(*) FROM clientes") suspend fun countClientes(): Int
    @Query("SELECT COUNT(*) FROM cliente_direcciones") suspend fun countDirecciones(): Int
    @Query("SELECT COUNT(*) FROM productos WHERE familiaCodigo IS NULL OR familiaCodigo NOT IN ('24','29','30')") suspend fun countProductos(): Int
    @Query("SELECT COUNT(*) FROM familias WHERE familiaCodigo NOT IN ('24','29','30') AND UPPER(COALESCE(familiaDescripcion,'')) <> 'INACTIVOS'") suspend fun countFamilias(): Int
    @Query("SELECT COUNT(*) FROM precios") suspend fun countPrecios(): Int
    @Query("SELECT COUNT(*) FROM rutas") suspend fun countRutas(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertClientes(items: List<ClienteEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertDirecciones(items: List<ClienteDireccionEntity>)
    @Query("DELETE FROM clientes") suspend fun clearClientes()
    @Query("DELETE FROM cliente_direcciones") suspend fun clearDirecciones()
    @Transaction suspend fun replaceClientes(items: List<ClienteEntity>) { clearClientes(); upsertClientes(items) }
    @Transaction suspend fun replaceDirecciones(items: List<ClienteDireccionEntity>) { clearDirecciones(); upsertDirecciones(items) }
    @Query("DELETE FROM cliente_direcciones WHERE clienteRut=:clienteRut") suspend fun deleteDireccionesCliente(clienteRut: String)
    @Transaction suspend fun replaceDireccionesCliente(clienteRut: String, items: List<ClienteDireccionEntity>) { deleteDireccionesCliente(clienteRut); upsertDirecciones(items) }
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertProductos(items: List<ProductoEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertFamilias(items: List<FamiliaEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertRutas(items: List<RutaEntity>)
    @Query("DELETE FROM productos") suspend fun clearProductos()
    @Query("DELETE FROM familias") suspend fun clearFamilias()
    @Query("DELETE FROM rutas") suspend fun clearRutas()
    @Transaction suspend fun replaceProductos(items: List<ProductoEntity>) { clearProductos(); upsertProductos(items) }
    @Transaction suspend fun replaceFamilias(items: List<FamiliaEntity>) { clearFamilias(); upsertFamilias(items) }
    @Transaction suspend fun replaceRutas(items: List<RutaEntity>) { clearRutas(); upsertRutas(items) }
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPrecios(items: List<PrecioEntity>)
    @Query("DELETE FROM precios") suspend fun clearPrecios()
    @Transaction suspend fun replacePrecios(items: List<PrecioEntity>) { clearPrecios(); upsertPrecios(items) }
}

@Dao interface CuentaCorrienteDao {
    @Query("SELECT * FROM cuenta_corriente ORDER BY clienteNombre, clienteRut, ventaFecha DESC, ventaNumero DESC") fun observeAll(): Flow<List<CuentaCorrienteEntity>>
    @Query("SELECT * FROM cuenta_corriente WHERE COALESCE(folio,0) > 0 ORDER BY clienteNombre, clienteRut, ventaFecha DESC, ventaNumero DESC") fun observeAllConFolio(): Flow<List<CuentaCorrienteEntity>>
    @Query("SELECT * FROM cuenta_corriente WHERE clienteRut=:rut AND COALESCE(folio,0) > 0 ORDER BY ventaFecha DESC, ventaNumero DESC") suspend fun porClienteConFolio(rut: String): List<CuentaCorrienteEntity>
    @Query("SELECT COUNT(*) FROM cuenta_corriente") suspend fun count(): Int
    @Query("DELETE FROM cuenta_corriente") suspend fun clear()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(items: List<CuentaCorrienteEntity>)
    @Transaction suspend fun replaceAll(items: List<CuentaCorrienteEntity>) { clear(); upsertAll(items) }
}

@Dao interface NvDao {
    @Query("SELECT * FROM nv_headers WHERE syncStatus <> 'ELIMINAR_PENDIENTE' ORDER BY createdAt DESC") fun observeHeaders(): Flow<List<NvHeaderEntity>>
    @Query("SELECT * FROM nv_headers WHERE syncStatus IN ('PENDIENTE','ERROR','ELIMINAR_PENDIENTE') ORDER BY createdAt LIMIT 20") suspend fun pending(): List<NvHeaderEntity>
    @Query("SELECT * FROM nv_headers WHERE offlineId=:offlineId LIMIT 1") suspend fun header(offlineId: String): NvHeaderEntity?
    @Query("SELECT COUNT(*) FROM nv_headers WHERE syncStatus='PENDIENTE'") suspend fun countPending(): Int
    @Query("SELECT COUNT(*) FROM nv_headers WHERE syncStatus='ERROR'") suspend fun countError(): Int
    @Query("SELECT COUNT(*) FROM nv_headers WHERE syncStatus='SINCRONIZADO'") suspend fun countSynced(): Int
    @Query("SELECT * FROM nv_lines WHERE offlineId=:offlineId") suspend fun lines(offlineId: String): List<NvLineEntity>
    @Transaction suspend fun insertNv(header: NvHeaderEntity, lines: List<NvLineEntity>) { insertHeader(header); deleteLines(header.offlineId); insertLines(lines) }
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertHeader(h: NvHeaderEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertLines(lines: List<NvLineEntity>)
    @Query("DELETE FROM nv_lines WHERE offlineId=:offlineId") suspend fun deleteLines(offlineId: String)
    @Query("DELETE FROM nv_headers WHERE offlineId=:offlineId") suspend fun deleteHeader(offlineId: String)
    @Transaction suspend fun deleteNvLocal(offlineId: String) { deleteLines(offlineId); deleteHeader(offlineId) }
    @Query("UPDATE nv_headers SET syncStatus=:status, ventaNumeroServidor=:ventaNumero, lastError=:error WHERE offlineId=:offlineId") suspend fun mark(offlineId: String, status: String, ventaNumero: Long?, error: String?)
    @Query("UPDATE nv_headers SET syncStatus='ELIMINAR_PENDIENTE', lastError=NULL WHERE offlineId=:offlineId") suspend fun markDeletePending(offlineId: String)
    @Query("UPDATE nv_headers SET facturado=:facturado, ventaNumeroServidor=COALESCE(:ventaNumero, ventaNumeroServidor) WHERE offlineId=:offlineId") suspend fun markFacturado(offlineId: String, facturado: String, ventaNumero: Long?)
}

@Database(entities=[SecUserEntity::class, ClienteEntity::class, ClienteDireccionEntity::class, RutaEntity::class, ProductoEntity::class, FamiliaEntity::class, PrecioEntity::class, CuentaCorrienteEntity::class, NvHeaderEntity::class, NvLineEntity::class], version=14, exportSchema=true)
abstract class RisekDatabase: RoomDatabase() {
    abstract fun secUserDao(): SecUserDao
    abstract fun catalogDao(): CatalogDao
    abstract fun cuentaCorrienteDao(): CuentaCorrienteDao
    abstract fun nvDao(): NvDao
}
