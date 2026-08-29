from __future__ import annotations

import os
import subprocess
import textwrap
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import cm
from reportlab.platypus import (
    Image as RLImage,
    KeepTogether,
    ListFlowable,
    ListItem,
    PageBreak,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)


ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs" / "capacitacion_vendedores"
SLIDES = OUT / "slides"
AUDIO = OUT / "audio"
SEGMENTS = OUT / "segments"
SCREENS = OUT / "screens"
IMG = ROOT / "docs" / "img"
LOGO = ROOT / "android_app" / "app" / "src" / "main" / "res" / "drawable" / "logo_risek.png"
BG = ROOT / "android_app" / "app" / "src" / "main" / "res" / "drawable-nodpi" / "login_sales_bg_soft.png"


RED = colors.HexColor("#e30613")
DARK = colors.HexColor("#111827")
INK = colors.HexColor("#1f2937")
MUTED = colors.HexColor("#6b7280")
SOFT = colors.HexColor("#f3f4f6")
BLUE = colors.HexColor("#073b4c")


def ensure_dirs() -> None:
    for path in (OUT, SLIDES, AUDIO, SEGMENTS, SCREENS):
        path.mkdir(parents=True, exist_ok=True)


def p(text: str, style: ParagraphStyle) -> Paragraph:
    return Paragraph(text, style)


def bullet_list(items: list[str], style: ParagraphStyle) -> ListFlowable:
    return ListFlowable(
        [ListItem(Paragraph(item, style), leftIndent=8) for item in items],
        bulletType="bullet",
        leftIndent=14,
        bulletFontName="Helvetica-Bold",
        bulletColor=RED,
    )


def image_flow(path: Path, max_w: float = 11.0 * cm, max_h: float = 9.0 * cm) -> RLImage | None:
    if not path.exists():
        return None
    with Image.open(path) as im:
        w, h = im.size
    scale = min(max_w / w, max_h / h)
    return RLImage(str(path), width=w * scale, height=h * scale)


def draw_phone_screen(name: str, title: str, rows: list[str], footer: str = "") -> Path:
    w, h = 520, 920
    im = Image.new("RGB", (w, h), "#f5f6f8")
    d = ImageDraw.Draw(im)
    font_title = load_font(32, True)
    font_h = load_font(23, True)
    font = load_font(20)
    font_small = load_font(16)
    d.rounded_rectangle((18, 18, w - 18, h - 18), radius=34, fill="#111827")
    d.rounded_rectangle((34, 48, w - 34, h - 48), radius=28, fill="#f4f5f7")
    d.rectangle((34, 48, w - 34, 126), fill="#e30613")
    d.text((70, 72), title, font=font_title, fill="white")
    y = 158
    if LOGO.exists() and name == "login":
        logo = Image.open(LOGO).convert("RGBA")
        logo.thumbnail((250, 90))
        im.paste(logo, ((w - logo.width) // 2, y), logo)
        y += 105
        d.text((150, y), "RISEK VENTAS", font=font_h, fill="#111827")
        d.text((158, y + 30), "Ventas en terreno", font=font_small, fill="#6b7280")
        y += 85
    for row in rows:
        if row.startswith("#"):
            d.text((64, y), row[1:], font=font_h, fill="#111827")
            y += 42
        elif row.startswith("!"):
            d.rounded_rectangle((60, y, w - 60, y + 58), radius=12, fill="#fee2e2", outline="#fecaca")
            d.text((78, y + 17), row[1:], font=font_small, fill="#991b1b")
            y += 74
        elif row.startswith("$"):
            d.rounded_rectangle((60, y, w - 60, y + 62), radius=12, fill="#e30613")
            tw = d.textlength(row[1:], font=font_h)
            d.text(((w - tw) / 2, y + 17), row[1:], font=font_h, fill="white")
            y += 80
        else:
            d.rounded_rectangle((60, y, w - 60, y + 56), radius=10, fill="white", outline="#d1d5db")
            d.text((78, y + 17), row, font=font, fill="#1f2937")
            y += 70
    if footer:
        d.rounded_rectangle((58, h - 142, w - 58, h - 84), radius=12, fill="#eaf6f9", outline="#b9dce5")
        d.text((76, h - 123), footer, font=font_small, fill="#073b4c")
    out = SCREENS / f"{name}.png"
    im.save(out, "PNG")
    return out


def make_training_screens() -> dict[str, Path]:
    return {
        "2. Login": draw_phone_screen("login", "Ingreso", ["Usuario", "Clave", "$INGRESAR"], "Usuario entra directo a ventas"),
        "3. Menu principal": draw_phone_screen("menu", "RISEK VentAS", ["Pedidos", "Nueva NV", "Dia", "Cta Cte", "Consulta", "Sync"], "Botonera inferior de trabajo"),
        "4. Sincronizacion de datos": draw_phone_screen("sync", "Sync", ["#Preparacion offline", "Descargar datos", "Enviar NV pendientes", "Reporte mensual por mail", "!Ventas operativas si hay ultima descarga buena"], "Clientes, direcciones, productos, precios"),
        "5. Crear Nueva NV": draw_phone_screen("nv", "Total $0", ["Cliente", "Direccion reparto", "Fecha Reparto", "Familia", "Codigo o descripcion", "UXE   Cajas   Desc %", "$AGREGAR"], "Carrito arriba para revisar venta"),
        "9. Cuenta corriente": draw_phone_screen("ctacte", "Cta Cte", ["Buscar cliente", "Documento | Fecha | Total | Saldo", "PDF cartola", "PDF pendientes", "WhatsApp pendientes"], "Ver detalle de factura"),
        "10. Consulta de productos": draw_phone_screen("consulta", "Consulta", ["Codigo exacto", "Descripcion / familia", "Precio Lista 01", "Stock", "Desc max %"], "Stock negativo muestra cero"),
        "11. Ventas del dia": draw_phone_screen("dia", "Dia", ["NV | Cliente | Total", "Abrir detalle", "Total general dia"], "Control cierre vendedor"),
    }


def header_footer(canvas, doc):
    canvas.saveState()
    canvas.setFillColor(RED)
    canvas.rect(0, A4[1] - 0.32 * cm, A4[0], 0.32 * cm, stroke=0, fill=1)
    if LOGO.exists():
        canvas.drawImage(str(LOGO), 1.35 * cm, A4[1] - 1.35 * cm, width=2.8 * cm, height=0.95 * cm, preserveAspectRatio=True, mask="auto")
    canvas.setFillColor(DARK)
    canvas.setFont("Helvetica-Bold", 8)
    canvas.drawRightString(A4[0] - 1.35 * cm, A4[1] - 0.92 * cm, "Manual de uso - RISEK Ventas")
    canvas.setFillColor(MUTED)
    canvas.setFont("Helvetica", 8)
    canvas.drawString(1.35 * cm, 0.78 * cm, "Uso interno vendedores RISEK")
    canvas.drawRightString(A4[0] - 1.35 * cm, 0.78 * cm, f"Pagina {doc.page}")
    canvas.restoreState()


def make_pdf() -> Path:
    pdf_path = OUT / "manual_usuario_vendedores_risek.pdf"
    doc = SimpleDocTemplate(
        str(pdf_path),
        pagesize=A4,
        rightMargin=1.35 * cm,
        leftMargin=1.35 * cm,
        topMargin=1.65 * cm,
        bottomMargin=1.25 * cm,
        title="Manual de uso RISEK Ventas",
    )
    styles = getSampleStyleSheet()
    styles.add(ParagraphStyle("CoverTitle", fontName="Helvetica-Bold", fontSize=27, leading=31, textColor=RED, alignment=TA_CENTER, spaceAfter=10))
    styles.add(ParagraphStyle("CoverSub", fontName="Helvetica-Bold", fontSize=14, leading=18, textColor=DARK, alignment=TA_CENTER, spaceAfter=24))
    styles.add(ParagraphStyle("H1R", fontName="Helvetica-Bold", fontSize=17, leading=21, textColor=DARK, spaceBefore=10, spaceAfter=8))
    styles.add(ParagraphStyle("H2R", fontName="Helvetica-Bold", fontSize=12.5, leading=16, textColor=RED, spaceBefore=8, spaceAfter=5))
    styles.add(ParagraphStyle("BodyR", fontName="Helvetica", fontSize=9.5, leading=13.2, textColor=INK, alignment=TA_LEFT))
    styles.add(ParagraphStyle("SmallR", fontName="Helvetica", fontSize=8.2, leading=11, textColor=MUTED))
    styles.add(ParagraphStyle("Callout", fontName="Helvetica-Bold", fontSize=10, leading=13, textColor=BLUE, backColor=colors.HexColor("#eef7fa"), borderColor=colors.HexColor("#b9dce5"), borderWidth=0.7, borderPadding=8, spaceBefore=6, spaceAfter=8))
    styles.add(ParagraphStyle("Warn", fontName="Helvetica-Bold", fontSize=9.2, leading=12, textColor=colors.HexColor("#7f1d1d"), backColor=colors.HexColor("#fee2e2"), borderColor=colors.HexColor("#fecaca"), borderWidth=0.7, borderPadding=7, spaceBefore=5, spaceAfter=7))

    story = []
    if LOGO.exists():
        logo = image_flow(LOGO, 7 * cm, 2.2 * cm)
        if logo:
            story += [Spacer(1, 2.6 * cm), logo, Spacer(1, 0.55 * cm)]
    story += [
        p("RISEK VENTAS", styles["CoverTitle"]),
        p("Manual de uso para vendedores en terreno", styles["CoverSub"]),
        p("Guia practica para iniciar sesion, sincronizar datos, crear notas de venta, consultar productos, revisar cuenta corriente, enviar documentos y trabajar con pedidos del dia.", styles["Callout"]),
        Spacer(1, 0.8 * cm),
    ]
    intro = [
        ["Version", "Capacitacion vendedores"],
        ["Uso", "Ventas en terreno con datos offline"],
        ["Objetivo", "Vender rapido, seguro y con informacion actualizada"],
    ]
    t = Table(intro, colWidths=[4 * cm, 11 * cm])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (0, -1), RED),
        ("TEXTCOLOR", (0, 0), (0, -1), colors.white),
        ("FONTNAME", (0, 0), (-1, -1), "Helvetica-Bold"),
        ("FONTSIZE", (0, 0), (-1, -1), 9),
        ("GRID", (0, 0), (-1, -1), 0.5, colors.HexColor("#d1d5db")),
        ("BACKGROUND", (1, 0), (1, -1), colors.white),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("PADDING", (0, 0), (-1, -1), 8),
    ]))
    story += [t, PageBreak()]

    sections = [
        ("1. Antes de salir a vender", [
            "Tener bateria suficiente en telefono.",
            "Abrir app en zona con buena senal.",
            "Entrar con usuario y clave asignados.",
            "Sincronizar datos antes de iniciar ruta.",
            "Confirmar que existan clientes, direcciones, productos y precios.",
        ], "La sincronizacion deja informacion disponible para vender aun cuando la senal sea baja durante ruta."),
        ("2. Login", [
            "Abrir RISEK Ventas.",
            "Seleccionar usuario.",
            "Ingresar clave.",
            "Presionar INGRESAR.",
            "Si clave esta mala, app muestra aviso de contrasena incorrecta.",
        ], "Empresa/local trabajan por defecto. Vendedor no debe cambiar configuraciones internas."),
        ("3. Menu principal", [
            "Pedidos: revisar notas de venta guardadas, sincronizadas o pendientes.",
            "Nueva NV: crear venta para cliente.",
            "Dia: revisar ventas del dia y detalle de cada NV.",
            "Cta Cte: revisar documentos, deuda y PDF de cartola.",
            "Consulta: buscar productos y precios.",
            "Sync: descargar datos, enviar NV pendientes y enviar reporte mensual.",
        ], "Cada opcion esta pensada para uso rapido en terreno."),
        ("4. Sincronizacion de datos", [
            "Entrar a Sync.",
            "Presionar Descargar datos.",
            "Esperar mensaje final.",
            "Si dice Datos descargados, salir a vender.",
            "Si dice Ventas operativas, tambien puede vender con ultima informacion valida.",
            "Si dice Faltan datos para vender, reintentar con mejor conexion.",
        ], "La app conserva ultima descarga buena. Si falla cartola u otro dato no critico, venta no queda bloqueada."),
        ("5. Crear Nueva NV", [
            "Entrar a Nueva NV.",
            "Buscar cliente por RUT o nombre.",
            "Seleccionar direccion de reparto.",
            "Confirmar Fecha Reparto o Entrega.",
            "Buscar productos por codigo, descripcion o familia.",
            "Ingresar UXE, cajas o descuento si corresponde.",
            "Presionar Agregar.",
            "Revisar carrito y total acumulado.",
            "Guardar NV.",
        ], "Al guardar, pedido queda en Pedidos. Si hay internet, luego puede sincronizarse al servidor."),
        ("6. UXE, cajas y descuentos", [
            "UXE es unidades por envase vendidas.",
            "Cajas permite calcular cantidad segun unidad de envase del producto.",
            "Descuento respeta maximo permitido del producto.",
            "Si campo UXE, Cajas o Desc esta vacio, app entiende valor cero salvo UXE al agregar cuando corresponde.",
        ], "No aplicar descuento mayor al permitido. App valida tope para evitar rechazo o venta incorrecta."),
        ("7. Carrito de NV", [
            "Icono carrito en cabecera muestra productos ya agregados.",
            "Sirve para revisar descripcion, UXE, precio, descuento y total.",
            "Producto agregado queda marcado visualmente en busqueda.",
            "Antes de guardar, validar que cantidades y descuentos sean correctos.",
        ], "Usar carrito evita ventas incompletas sin bajar al final de pantalla."),
        ("8. Pedidos", [
            "Pedidos muestra NV guardadas.",
            "NV pendiente debe enviarse desde Sync.",
            "NV sincronizada queda con numero servidor.",
            "Puede abrir detalle para revisar contenido.",
            "Si pedido sincronizado permite edicion, al guardar queda pendiente de reenviar.",
            "Eliminar NV sincronizada avisa al servidor cuando hay conexion.",
        ], "No borrar pedido si existe duda. Revisar detalle antes."),
        ("9. Cuenta corriente", [
            "Entrar a Cta Cte.",
            "Buscar cliente.",
            "Ver documentos, fecha, total venta y saldo.",
            "Abrir detalle de factura cuando se requiera.",
            "Generar PDF cartola completa o solo pendientes.",
            "Enviar cartola pendiente por WhatsApp si cliente lo solicita.",
        ], "Cartola ayuda a informar deuda, pero no reemplaza conversacion comercial con cliente."),
        ("10. Consulta de productos", [
            "Entrar a Consulta.",
            "Buscar por codigo exacto o descripcion.",
            "Ver precio Lista 01, stock, familia y descuento maximo.",
            "Stock negativo se muestra como cero.",
            "Producto inactivo no debe venderse.",
        ], "Consulta sirve para responder precio rapido sin crear NV."),
        ("11. Ventas del dia", [
            "Entrar a Dia.",
            "Ver listado de NV del dia.",
            "Revisar numero de documento, cliente y total.",
            "Al tocar una venta, abrir detalle.",
            "Validar total general del dia.",
        ], "Usar esta opcion para cierre personal de ruta."),
        ("12. Enviar NV pendientes", [
            "Entrar a Sync.",
            "Presionar Enviar NV pendientes.",
            "Esperar resultado.",
            "Si queda con error, revisar mensaje y reintentar con conexion estable.",
        ], "No reinstalar app por errores de envio. Primero sincronizar nuevamente."),
        ("13. Reporte mensual por correo", [
            "Entrar a Sync.",
            "Presionar boton de reporte por mail.",
            "Seleccionar mes y ano.",
            "Ingresar correo destino.",
            "App envia PDF con FE, BO y CE del vendedor logueado.",
        ], "Valores del reporte van en pesos chilenos. CE descuenta total del mes."),
        ("14. Actualizar app", [
            "Si app informa nueva version, presionar Descargar.",
            "Esperar descarga completa.",
            "Aceptar instalacion.",
            "Abrir app y verificar version inferior.",
            "Si Android bloquea instalacion, habilitar permiso de instalar apps desconocidas para navegador o gestor usado.",
        ], "Actualizar mantiene mejoras y correcciones. Sincronizar datos despues de actualizar si corresponde."),
        ("15. Buenas practicas", [
            "Sincronizar antes de ruta.",
            "No compartir clave.",
            "Revisar cliente bloqueado antes de vender.",
            "Confirmar direccion y fecha de entrega.",
            "Revisar carrito antes de guardar.",
            "Enviar pendientes al terminar jornada.",
            "Si algo falla, anotar mensaje exacto y avisar soporte.",
        ], "Trabajo ordenado evita diferencias de precio, clientes equivocados y pedidos incompletos."),
    ]

    screenshot_map = make_training_screens()

    for title, bullets, callout in sections:
        story.append(p(title, styles["H1R"]))
        if title in screenshot_map:
            img = image_flow(screenshot_map[title], 6.1 * cm, 10.3 * cm)
            if img:
                story.append(img)
                story.append(Spacer(1, 0.2 * cm))
        story.append(bullet_list(bullets, styles["BodyR"]))
        story.append(p(callout, styles["Callout"]))
        if title in ("4. Sincronizacion de datos", "8. Pedidos", "11. Ventas del dia"):
            story.append(PageBreak())
        else:
            story.append(Spacer(1, 0.2 * cm))

    story.append(PageBreak())
    story.append(p("Checklist diario vendedor", styles["H1R"]))
    checks = [
        ["Momento", "Accion", "Listo"],
        ["Inicio", "Entrar a app y sincronizar datos", ""],
        ["Inicio", "Confirmar clientes/productos/precios disponibles", ""],
        ["Ruta", "Crear NV y revisar carrito antes de guardar", ""],
        ["Ruta", "Consultar cuenta corriente cuando cliente lo solicite", ""],
        ["Cierre", "Enviar NV pendientes", ""],
        ["Cierre", "Revisar ventas del dia y total general", ""],
    ]
    table = Table(checks, colWidths=[3 * cm, 10.5 * cm, 2 * cm])
    table.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), BLUE),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
        ("FONTNAME", (0, 1), (-1, -1), "Helvetica"),
        ("FONTSIZE", (0, 0), (-1, -1), 8.6),
        ("GRID", (0, 0), (-1, -1), 0.45, colors.HexColor("#cbd5e1")),
        ("BACKGROUND", (0, 1), (-1, -1), colors.HexColor("#f8fafc")),
        ("PADDING", (0, 0), (-1, -1), 7),
    ]))
    story += [table, Spacer(1, 0.4 * cm), p("Ante error: tomar captura, copiar mensaje exacto, indicar usuario, hora y opcion usada.", styles["Warn"])]
    doc.build(story, onFirstPage=header_footer, onLaterPages=header_footer)
    return pdf_path


def load_font(size: int, bold: bool = False):
    candidates = [
        "C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf",
        "C:/Windows/Fonts/seguisb.ttf" if bold else "C:/Windows/Fonts/segoeui.ttf",
    ]
    for c in candidates:
        if Path(c).exists():
            return ImageFont.truetype(c, size)
    return ImageFont.load_default()


VIDEO_STEPS = [
    ("RISEK Ventas", "Capacitacion para vendedores en terreno", ["Objetivo: vender rapido y seguro.", "Usar datos sincronizados.", "Revisar pedidos antes de cerrar dia."], "Bienvenidos a RISEK Ventas. En este video veremos como usar la aplicacion Android para vender en terreno."),
    ("1. Login", "Ingreso seguro", ["Seleccione usuario.", "Digite clave.", "Presione INGRESAR.", "Si clave es incorrecta, app avisa."], "Primero ingrese con su usuario y clave. No comparta su clave con otros vendedores."),
    ("2. Sincronizar", "Antes de salir a ruta", ["Entrar a Sync.", "Presionar Descargar datos.", "Esperar mensaje final.", "Si dice Ventas operativas, puede vender."], "Antes de salir, sincronice. La app conserva la ultima descarga buena para no detener la venta."),
    ("3. Nueva NV", "Crear pedido", ["Buscar cliente.", "Seleccionar direccion.", "Confirmar fecha reparto.", "Buscar y agregar productos."], "Para crear una nota de venta, seleccione cliente, direccion y fecha de entrega."),
    ("4. Productos", "Codigo, descripcion o familia", ["Codigo exacto busca rapido.", "Descripcion agrupa por familia.", "Familia muestra todos productos del grupo.", "Consulta muestra precio y descuento maximo."], "Puede buscar por codigo, descripcion o familia. Revise precio y descuento maximo antes de vender."),
    ("5. UXE, cajas y descuento", "Cantidad correcta", ["UXE: unidades por envase.", "Cajas calcula segun unidad envase.", "Descuento no puede superar maximo.", "Stock negativo se muestra como cero."], "Ingrese UXE o cajas segun corresponda. El descuento respeta el maximo autorizado."),
    ("6. Carrito", "Validar antes de guardar", ["Toque icono carrito.", "Revise productos agregados.", "Valide cantidad, precio y descuento.", "Luego guarde NV."], "Antes de guardar, revise el carrito. Asi evita pedidos incompletos."),
    ("7. Pedidos", "Control de NV", ["Ver pendientes.", "Ver sincronizadas.", "Abrir detalle.", "Editar si corresponde."], "En pedidos revise notas guardadas, pendientes y sincronizadas."),
    ("8. Cuenta corriente", "Informacion cliente", ["Buscar cliente.", "Ver documentos y saldo.", "Abrir factura.", "Enviar PDF por WhatsApp."], "La cuenta corriente permite informar deuda y entregar PDF al cliente."),
    ("9. Dia", "Cierre vendedor", ["Ver ventas del dia.", "Abrir detalle.", "Revisar total general.", "Enviar pendientes al cierre."], "Al final de jornada revise ventas del dia y envie notas pendientes."),
    ("10. Reporte mensual", "PDF por correo", ["Entrar a Sync.", "Elegir mes y ano.", "Ingresar email.", "Enviar reporte FE, BO y CE."], "El reporte mensual se envia al correo indicado y solo considera al vendedor logueado."),
    ("Cierre", "Buenas practicas", ["Sincronizar antes de ruta.", "Revisar carrito.", "Enviar pendientes.", "Reportar mensaje exacto si hay error."], "Con estos pasos, la venta queda ordenada, segura y lista para sincronizar."),
]


def draw_slide(idx: int, step: tuple[str, str, list[str], str]) -> Path:
    title, subtitle, bullets, _ = step
    w, h = 1280, 720
    im = Image.new("RGB", (w, h), "#f5f6f8")
    d = ImageDraw.Draw(im)
    if BG.exists():
        bg = Image.open(BG).convert("RGB")
        bg.thumbnail((w, h))
        bg_layer = Image.new("RGB", (w, h), "#111827")
        bg_layer.paste(bg, ((w - bg.width) // 2, (h - bg.height) // 2))
        overlay = Image.new("RGB", (w, h), "#111827")
        im = Image.blend(bg_layer, overlay, 0.62)
        d = ImageDraw.Draw(im)
    d.rounded_rectangle((56, 48, 1224, 672), radius=28, fill=(255, 255, 255), outline=(230, 10, 26), width=4)
    d.rectangle((56, 48, 1224, 150), fill=(230, 6, 19))
    if LOGO.exists():
        logo = Image.open(LOGO).convert("RGBA")
        logo.thumbnail((210, 78))
        im.paste(logo, (88, 65), logo)
    font_title = load_font(48, True)
    font_sub = load_font(26, True)
    font_bullet = load_font(30)
    font_small = load_font(19, True)
    d.text((330, 72), title, font=font_title, fill="white")
    d.text((92, 188), subtitle, font=font_sub, fill="#111827")
    y = 255
    for bullet in bullets:
        d.ellipse((105, y + 9, 123, y + 27), fill=(230, 6, 19))
        for line in textwrap.wrap(bullet, width=54):
            d.text((145, y), line, font=font_bullet, fill="#1f2937")
            y += 39
        y += 14
    d.text((92, 625), "RISEK Ventas - Capacitacion vendedores", font=font_small, fill="#6b7280")
    d.text((1095, 625), f"{idx + 1}/{len(VIDEO_STEPS)}", font=font_small, fill="#6b7280")
    out = SLIDES / f"slide_{idx:02d}.png"
    im.save(out, "PNG")
    return out


def powershell_tts(text: str, wav_path: Path) -> bool:
    escaped = text.replace("'", "''")
    script = (
        "Add-Type -AssemblyName System.Speech; "
        "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer; "
        "$s.Rate=-1; $s.Volume=100; "
        f"$s.SetOutputToWaveFile('{str(wav_path)}'); "
        f"$s.Speak('{escaped}'); "
        "$s.Dispose();"
    )
    try:
        subprocess.run(["powershell", "-NoProfile", "-Command", script], check=True, timeout=45, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return wav_path.exists() and wav_path.stat().st_size > 1000
    except Exception:
        return False


def make_video() -> tuple[Path, Path]:
    script_path = OUT / "guion_video_capacitacion.txt"
    script_path.write_text("\n\n".join([f"{i+1}. {s[0]} - {s[3]}" for i, s in enumerate(VIDEO_STEPS)]), encoding="utf-8")
    segments = []
    for idx, step in enumerate(VIDEO_STEPS):
        png = draw_slide(idx, step)
        wav = AUDIO / f"slide_{idx:02d}.wav"
        mp4 = SEGMENTS / f"segment_{idx:02d}.mp4"
        has_audio = powershell_tts(step[3], wav)
        if has_audio:
            cmd = ["ffmpeg", "-y", "-loop", "1", "-i", str(png), "-i", str(wav), "-c:v", "libx264", "-tune", "stillimage", "-c:a", "aac", "-b:a", "128k", "-pix_fmt", "yuv420p", "-shortest", str(mp4)]
        else:
            cmd = ["ffmpeg", "-y", "-loop", "1", "-i", str(png), "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100", "-t", "7", "-c:v", "libx264", "-c:a", "aac", "-pix_fmt", "yuv420p", "-shortest", str(mp4)]
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        segments.append(mp4)
    concat = OUT / "concat.txt"
    concat.write_text("\n".join([f"file '{str(p).replace(chr(92), '/')}'" for p in segments]), encoding="utf-8")
    video = OUT / "video_capacitacion_risek_vendedores.mp4"
    subprocess.run(["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", str(concat), "-c", "copy", str(video)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return video, script_path


def main() -> None:
    ensure_dirs()
    pdf = make_pdf()
    video, script = make_video()
    print(f"PDF={pdf}")
    print(f"VIDEO={video}")
    print(f"GUIA={script}")


if __name__ == "__main__":
    main()
