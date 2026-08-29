
package cl.risek.offline.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "productos")
data class ProductoEntity(

    @PrimaryKey
    val producto_codigo: String,

    val producto_descripcion: String,

    // NUEVOS CAMPOS STOCK
    val stockActual: Double = 0.0,
    val stockFecha: String = ""

)
