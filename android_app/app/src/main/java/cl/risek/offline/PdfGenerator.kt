package cl.risek.offline

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object PdfGenerator {
    private fun money(value: Long): String = NumberFormat.getCurrencyInstance(Locale("es", "CL")).format(value)
    private fun fechaCl(value: String?): String {
        if (value.isNullOrBlank()) return "-"
        return runCatching { LocalDate.parse(value.take(10)).format(DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale("es", "CL"))) }.getOrDefault(value)
    }
    private fun fechaCorta(value: String?): String {
        if (value.isNullOrBlank()) return "-"
        return runCatching { LocalDate.parse(value.take(10)).format(DateTimeFormatter.ofPattern("dd-MM-yy", Locale("es", "CL"))) }.getOrDefault(value.take(8))
    }
    private fun drawRight(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint) {
        canvas.drawText(text, x - paint.measureText(text), y, paint)
    }
    private fun drawLogo(context: Context, canvas: Canvas, left: Float, top: Float, size: Int) {
        val logo = runCatching { BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher) }.getOrNull()
        if (logo != null) {
            val dst = RectF(left, top, left + size, top + size)
            canvas.drawBitmap(logo, null, dst, null)
        }
    }

    fun createNvPdf(context: Context, h: NvHeaderEntity, lines: List<NvLineEntity>): File {
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val c = page.canvas
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; color = Color.rgb(30, 30, 30) }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = Color.rgb(90, 90, 90) }
        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; typeface = Typeface.DEFAULT_BOLD; color = Color.rgb(30, 30, 30) }
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; typeface = Typeface.DEFAULT_BOLD; color = Color.rgb(185, 28, 28) }
        val linePaint = Paint().apply { color = Color.rgb(220, 220, 220); strokeWidth = 1f }

        c.drawText("RISEK - NOTA DE VENTA", 40f, 45f, title)
        c.drawText("N° servidor: ${h.ventaNumeroServidor ?: "Pendiente"}", 40f, 72f, bold)
        c.drawText("Offline ID: ${h.offlineId}", 40f, 92f, small)
        c.drawText("Cliente: ${h.clienteNombre} (${h.clienteRut})", 40f, 118f, p)
        c.drawText("Fecha pedido: ${fechaCl(h.fecha)}", 40f, 138f, p)
        c.drawText("Fecha reparto: ${fechaCl(h.fechaReparto)}", 220f, 138f, p)
        c.drawText("Dirección: ${h.direccion ?: "-"}", 40f, 158f, p)
        c.drawText("Estado: ${h.syncStatus} · Facturado: ${h.facturado}", 40f, 178f, p)
        if (!h.observacion.isNullOrBlank()) c.drawText("Obs: ${h.observacion.take(90)}", 40f, 198f, p)

        var y = 230f
        c.drawLine(40f, y - 16f, 555f, y - 16f, linePaint)
        c.drawText("Código", 40f, y, bold)
        c.drawText("Producto", 112f, y, bold)
        c.drawText("UXE", 310f, y, bold)
        c.drawText("P.Neto", 345f, y, bold)
        c.drawText("IVA", 405f, y, bold)
        c.drawText("ILA", 452f, y, bold)
        c.drawText("Total", 505f, y, bold)
        y += 18f
        c.drawLine(40f, y - 8f, 555f, y - 8f, linePaint)

        if (lines.isEmpty()) {
            c.drawText("Sin detalle local disponible para esta NV.", 40f, y + 20f, p)
            y += 45f
        } else {
            lines.forEach {
                if (y > 720f) return@forEach
                c.drawText(it.productoCodigo.take(10), 40f, y, small)
                c.drawText((it.descripcion ?: "").take(28), 112f, y, small)
                c.drawText(it.uxe.toString(), 310f, y, small)
                c.drawText(money(it.precio), 345f, y, small)
                c.drawText(money(it.ivaLinea), 405f, y, small)
                c.drawText(if (it.ilaLinea > 0) money(it.ilaLinea) else "-", 452f, y, small)
                c.drawText(money(it.totalLinea), 505f, y, small)
                y += 18f
            }
        }

        y += 24f
        val totalIla = lines.sumOf { it.ilaLinea }
        c.drawLine(355f, y - 16f, 555f, y - 16f, linePaint)
        c.drawText("NETO", 380f, y, bold); c.drawText(money(h.neto), 470f, y, bold)
        y += 22f
        c.drawText("IVA 19%", 380f, y, bold); c.drawText(money(h.iva), 470f, y, bold)
        if (totalIla > 0) {
            y += 22f
            c.drawText("ILA", 380f, y, bold); c.drawText(money(totalIla), 470f, y, bold)
        }
        y += 28f
        c.drawText("TOTAL", 380f, y, title); c.drawText(money(h.total), 470f, y, title)

        pdf.finishPage(page)
        val file = File(context.getExternalFilesDir(null), "NV_${h.offlineId}.pdf")
        file.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        return file
    }

    fun createCuentaCorrientePdf(
        context: Context,
        cliente: CuentaCorrienteEntity,
        rows: List<CuentaCorrienteEntity>,
        reportTitle: String = "CUENTA CORRIENTE",
        pendingOnly: Boolean = false,
        filePrefix: String = "Cuenta_Corriente"
    ): File {
        val pdf = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 36f
        val red = Color.rgb(227, 6, 19)
        val ink = Color.rgb(31, 41, 55)
        val muted = Color.rgb(98, 112, 130)
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(226, 232, 240); strokeWidth = 1f }
        val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(247, 248, 250) }
        val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f; color = ink }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = muted }
        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f; typeface = Typeface.DEFAULT_BOLD; color = ink }
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD; color = red }
        val whiteBold = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; typeface = Typeface.DEFAULT_BOLD; color = Color.WHITE }
        val totalBold = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; typeface = Typeface.DEFAULT_BOLD; color = red }

        val filtered = rows
            .map { it to cuentaSaldoDocumento(it) }
            .sortedByDescending { (doc, _) -> doc.ventaFecha ?: "" }
        val deuda = cuentaDeudaTotal(rows)
        val nc = cuentaNcTotal(rows)
        val saldoFinal = deuda + nc
        var pageNumber = 1
        var index = 0

        fun startPage(): Pair<PdfDocument.Page, Canvas> {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            val canvas = page.canvas
            drawLogo(context, canvas, margin, 26f, 48)
            canvas.drawText(reportTitle, 96f, 46f, title)
            canvas.drawText("RISEK Offline", 96f, 64f, bold)
            drawRight(canvas, "Fecha emision: ${fechaCl(LocalDate.now().toString())}", pageWidth - margin, 46f, small)
            drawRight(canvas, "Pagina $pageNumber", pageWidth - margin, 64f, small)

            canvas.drawRoundRect(RectF(margin, 88f, pageWidth - margin, 156f), 10f, 10f, bandPaint)
            canvas.drawText((cliente.clienteNombre ?: "Cliente").take(58), margin + 14f, 112f, bold)
            canvas.drawText("RUT: ${cliente.clienteRut}", margin + 14f, 132f, p)
            drawRight(canvas, if (pendingOnly) "Total pendiente" else "Saldo final", pageWidth - margin - 14f, 112f, small)
            drawRight(canvas, money(saldoFinal), pageWidth - margin - 14f, 134f, totalBold)

            val headerTop = 184f
            canvas.drawRoundRect(RectF(margin, headerTop - 18f, pageWidth - margin, headerTop + 6f), 6f, 6f, redPaint)
            canvas.drawText("Documento", margin + 8f, headerTop, whiteBold)
            canvas.drawText("Fecha", 168f, headerTop, whiteBold)
            drawRight(canvas, "Total venta", 394f, headerTop, whiteBold)
            drawRight(canvas, "Saldo", pageWidth - margin - 8f, headerTop, whiteBold)
            return page to canvas
        }

        var (page, canvas) = startPage()
        var y = 214f
        if (filtered.isEmpty()) {
            canvas.drawText("Sin documentos para este cliente.", margin, y, p)
            y += 26f
        } else {
            while (index < filtered.size) {
                if (y > 724f) {
                    pdf.finishPage(page)
                    pageNumber += 1
                    val next = startPage()
                    page = next.first
                    canvas = next.second
                    y = 214f
                }
                val (doc, saldo) = filtered[index]
                canvas.drawText("${doc.ventaTipo} ${doc.ventaNumero}".take(20), margin + 8f, y, p)
                canvas.drawText(fechaCorta(doc.ventaFecha), 168f, y, p)
                drawRight(canvas, money(doc.ventaTotalVenta), 394f, y, p)
                val saldoPaint = if (saldo < 0) Paint(p).apply { color = red; typeface = Typeface.DEFAULT_BOLD } else bold
                drawRight(canvas, money(saldo), pageWidth - margin - 8f, y, saldoPaint)
                canvas.drawLine(margin, y + 8f, pageWidth - margin, y + 8f, linePaint)
                y += 22f
                index += 1
            }
        }

        y += 18f
        if (y > 724f) {
            pdf.finishPage(page)
            pageNumber += 1
            val next = startPage()
            page = next.first
            canvas = next.second
            y = 214f
        }
        val totalBoxHeight = if (pendingOnly) 52f else 96f
        canvas.drawRoundRect(RectF(330f, y - 18f, pageWidth - margin, y - 18f + totalBoxHeight), 10f, 10f, bandPaint)
        if (pendingOnly) {
            canvas.drawText("TOTAL PENDIENTE", 346f, y + 12f, totalBold)
            drawRight(canvas, money(saldoFinal), pageWidth - margin - 14f, y + 12f, totalBold)
        } else {
            canvas.drawText("Total DEUDA", 346f, y, bold)
            drawRight(canvas, money(deuda), pageWidth - margin - 14f, y, bold)
            y += 24f
            canvas.drawText("Total NC", 346f, y, bold)
            drawRight(canvas, money(nc), pageWidth - margin - 14f, y, bold)
            y += 30f
            canvas.drawText("Saldo final", 346f, y, totalBold)
            drawRight(canvas, money(saldoFinal), pageWidth - margin - 14f, y, totalBold)
        }
        canvas.drawText("Documento generado desde RISEK Offline.", margin, pageHeight - 34f, small)

        pdf.finishPage(page)
        val safeRut = cliente.clienteRut.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(context.getExternalFilesDir(null), "${filePrefix}_$safeRut.pdf")
        file.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        return file
    }
}
