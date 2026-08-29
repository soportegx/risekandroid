
package cl.risek.offline.data.remote.dto

import com.google.gson.annotations.SerializedName

data class ProductoDto(

    @SerializedName("producto_codigo")
    val productoCodigo: String,

    @SerializedName("producto_descripcion")
    val productoDescripcion: String,

    // NUEVOS CAMPOS STOCK
    @SerializedName("stock_actual")
    val stockActual: Double,

    @SerializedName("stock_fecha")
    val stockFecha: String

)
