import base64
import datetime
import io
import os
from pathlib import Path
from datetime import timezone, timedelta
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
import decimal
import json
import math
import re
import time
import smtplib
from email.message import EmailMessage
from PIL import Image
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.responses import FileResponse, Response
from app.db import cursor
from app.security import create_token
from app.schemas import LoginRequest, LoginResponse, NvSyncRequest, NvSyncResponse, NvDeleteRequest, NvDeleteResponse
from pydantic import BaseModel

app = FastAPI(title='RISEK Offline API', version='1.5-v26-hora-409-delete')
app.add_middleware(
    CORSMiddleware,
    allow_origins=['*'],
    allow_credentials=True,
    allow_methods=['*'],
    allow_headers=['*'],
)
app.add_middleware(GZipMiddleware, minimum_size=1000)

APP_UPDATE_DIR = Path(__file__).resolve().parents[1] / 'app_updates'
APP_APK_PATH = Path(os.getenv('RISEK_APP_APK_PATH', str(APP_UPDATE_DIR / 'apprisek.apk')))
APP_VERSION_PATH = Path(os.getenv('RISEK_APP_VERSION_PATH', str(APP_UPDATE_DIR / 'version.json')))
DTE_PDF_DIR = Path(os.getenv('RISEK_DTE_PDF_DIR', r'C:\DTE_RISEK\PDF'))
_GERENTE_DASHBOARD_CACHE = {'ts': 0.0, 'data': None}
_GERENTE_DASHBOARD_CACHE_SECONDS = 300
_SUPERVISOR_DASHBOARD_CACHE = {'ts': 0.0, 'data': None}
_SUPERVISOR_DASHBOARD_CACHE_SECONDS = 300

_CONTROL_CHARS = re.compile(r'[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]')


class LogisticaEstadoRequest(BaseModel):
    venta_numero: int
    venta_tipo: str
    local_codigo: str = '01'
    estado: str
    observacion: str | None = None


class ResumenVentasEmailRequest(BaseModel):
    email: str
    vendedor_codigo: str | None = None
    mes: int | None = None
    ano: int | None = None


try:
    SERVER_TZ = ZoneInfo('America/Santiago')
    SERVER_TZ_NAME = 'America/Santiago'
except Exception:
    # Windows/Python necesita paquete tzdata. Este fallback evita que la API caiga completa.
    # Chile continental en mayo normalmente opera en UTC-4; instale tzdata para manejar cambios DST automáticamente.
    SERVER_TZ = timezone(timedelta(hours=-4))
    SERVER_TZ_NAME = 'America/Santiago fallback UTC-04:00 - instalar tzdata'

def server_now_cl() -> datetime.datetime:
    """Fecha/hora oficial para documentos móviles.

    Regla de negocio: venta_fecha y venta_hora de una NV sincronizada desde Android
    NO deben venir del teléfono. Se calculan en la API con zona America/Santiago,
    para que coincidan con la hora operacional del servidor/Chile.
    """
    return datetime.datetime.now(SERVER_TZ).replace(tzinfo=None)


def fecha_hora_guardado(req: NvSyncRequest) -> datetime.datetime:
    guardado_ms = to_int(getattr(req, 'venta_guardado_ms', None), None)
    if guardado_ms and guardado_ms > 0:
        try:
            return datetime.datetime.fromtimestamp(guardado_ms / 1000, tz=timezone.utc).astimezone(SERVER_TZ).replace(tzinfo=None)
        except Exception:
            pass
    return server_now_cl()



def first_or_none(cur):
    row = cur.fetchone()
    return row if row else None


def clean_text(value, max_len=None):
    if value is None:
        return None
    if isinstance(value, bytes):
        value = value.decode('latin1', errors='replace')
    text = str(value)
    text = _CONTROL_CHARS.sub(' ', text)
    text = text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
    text = ' '.join(text.split())
    if max_len is not None:
        text = text[:max_len]
    return text


def to_int(value, default=0):
    try:
        if value is None or value == '':
            return default
        if isinstance(value, float) and (math.isnan(value) or math.isinf(value)):
            return default
        return int(round(float(value)))
    except Exception:
        return default


def to_float(value, default=None):
    try:
        if value is None or value == '':
            return default
        out = float(value)
        if math.isnan(out) or math.isinf(out):
            return default
        return out
    except Exception:
        return default


def precio_unitario_neto(precio_recibido, total_linea, venta_unidadenvase, descuento):
    precio = to_int(precio_recibido, 0)
    if precio <= 0:
        return 0
    uxe = to_float(venta_unidadenvase, 0.0) or 0.0
    total = to_float(total_linea, 0.0) or 0.0
    desc = to_float(descuento, 0.0) or 0.0
    if uxe <= 0 or total <= 0:
        return precio
    factor_descuento = 1.0 - (desc / 100.0)
    if factor_descuento <= 0:
        factor_descuento = 1.0
    bruto_unitario = total / uxe / factor_descuento
    neto_unitario = int(round(bruto_unitario / 1.19))
    if neto_unitario <= 0:
        return precio
    margen_neto = max(2, int(round(neto_unitario * 0.01)))
    margen_bruto = max(2, int(round(bruto_unitario * 0.01)))
    if abs(precio - neto_unitario) <= margen_neto:
        return neto_unitario
    if abs(precio - int(round(bruto_unitario))) <= margen_bruto:
        return neto_unitario
    if precio > neto_unitario:
        return neto_unitario
    return precio


def _clean_value(value):
    if isinstance(value, decimal.Decimal):
        if value == value.to_integral_value():
            return int(value)
        return float(value)
    if isinstance(value, (datetime.datetime, datetime.date, datetime.time)):
        return value.isoformat()
    if isinstance(value, (bytes, str)):
        return clean_text(value)
    if value is None:
        return None
    return value


def clean_row(row: dict) -> dict:
    return {str(k): _clean_value(v) for k, v in dict(row).items()}


def clean_rows(rows) -> list[dict]:
    return [clean_row(r) for r in rows]


def table_columns(cur, table_name: str) -> set[str]:
    try:
        cur.execute(f"SHOW COLUMNS FROM {table_name}")
        return {clean_text(r.get('Field'), 80) for r in clean_rows(cur.fetchall()) if clean_text(r.get('Field'), 80)}
    except Exception:
        return set()


def ensure_logistica_table(cur):
    cur.execute("""
        CREATE TABLE IF NOT EXISTS mobile_logistica_estado (
          venta_numero BIGINT NOT NULL,
          venta_tipo VARCHAR(4) NOT NULL,
          local_codigo VARCHAR(10) NOT NULL,
          estado VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE',
          observacion VARCHAR(200) NULL,
          updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (venta_numero, venta_tipo, local_codigo),
          KEY ix_mobile_logistica_estado (estado, updated_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=latin1
    """)


def json_safe(data) -> Response:
    # JSON estricto y nunca armado manualmente. Separadores compactos para móvil.
    return Response(
        content=json.dumps(data, ensure_ascii=False, allow_nan=False, separators=(',', ':')),
        media_type='application/json; charset=utf-8',
    )


def pdf_escape(text) -> str:
    out = clean_text(text, None) or ''
    return out.replace('\\', '\\\\').replace('(', '\\(').replace(')', '\\)')


def wrap_pdf_line(text: str, width: int = 92) -> list[str]:
    words = (clean_text(text, None) or '').split()
    lines, current = [], ''
    for word in words:
        if len(current) + len(word) + 1 > width:
            if current:
                lines.append(current)
            current = word
        else:
            current = f"{current} {word}".strip()
    if current:
        lines.append(current)
    return lines or ['']


def simple_pdf_bytes(title: str, lines: list[str]) -> bytes:
    pages = []
    current = []
    for line in lines:
        for wrapped in wrap_pdf_line(line):
            current.append(wrapped)
            if len(current) >= 58:
                pages.append(current)
                current = []
    if current or not pages:
        pages.append(current)

    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        None,
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    kids = []
    for page_lines in pages:
        content_lines = ["BT", "/F1 9 Tf", "12 TL", "40 805 Td"]
        first = True
        for line in page_lines:
            command = f"({pdf_escape(line)}) Tj" if first else f"T* ({pdf_escape(line)}) Tj"
            content_lines.append(command)
            first = False
        content_lines.append("ET")
        stream = "\n".join(content_lines).encode('latin1', errors='replace')
        content_obj = b"<< /Length %d >>\nstream\n%s\nendstream" % (len(stream), stream)
        page_num = len(objects) + 1
        content_num = len(objects) + 2
        kids.append(f"{page_num} 0 R")
        objects.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> /Contents {content_num} 0 R >>".encode('latin1'))
        objects.append(content_obj)
    objects[1] = f"<< /Type /Pages /Kids [{' '.join(kids)}] /Count {len(pages)} >>".encode('latin1')

    chunks = [b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n"]
    offsets = [0]
    for index, obj in enumerate(objects, start=1):
        offsets.append(sum(len(c) for c in chunks))
        chunks.append(f"{index} 0 obj\n".encode('ascii') + obj + b"\nendobj\n")
    xref_offset = sum(len(c) for c in chunks)
    chunks.append(f"xref\n0 {len(objects)+1}\n0000000000 65535 f \n".encode('ascii'))
    for off in offsets[1:]:
        chunks.append(f"{off:010d} 00000 n \n".encode('ascii'))
    chunks.append(f"trailer\n<< /Size {len(objects)+1} /Root 1 0 R /Title ({pdf_escape(title)}) >>\nstartxref\n{xref_offset}\n%%EOF".encode('latin1', errors='replace'))
    return b''.join(chunks)


def money_clp(value) -> str:
    return f"${to_int(value, 0):,}".replace(',', '.')


def pdf_text(cmds: list[str], x: int, y: int, text: str, size: int = 9, bold: bool = False, color: str = "0 0 0") -> None:
    font = "F2" if bold else "F1"
    cmds.append(f"BT /{font} {size} Tf {color} rg {x} {y} Td ({pdf_escape(text)}) Tj ET")


def pdf_rect(cmds: list[str], x: int, y: int, w: int, h: int, color: str, stroke: bool = False) -> None:
    op = "B" if stroke else "f"
    cmds.append(f"{color} {'RG' if stroke else 'rg'} {x} {y} {w} {h} re {op}")


def pdf_stroke_rect(cmds: list[str], x: int, y: int, w: int, h: int, color: str = "0.82 0.82 0.82", width: float = 0.8) -> None:
    cmds.append(f"{width} w {color} RG {x} {y} {w} {h} re S")


def pdf_line(cmds: list[str], x1: int, y1: int, x2: int, y2: int, color: str = "0.86 0.86 0.86", width: float = 0.6) -> None:
    cmds.append(f"{width} w {color} RG {x1} {y1} m {x2} {y2} l S")


def styled_nv_summary_pdf_bytes(title: str, fecha: str, vendedor: str, headers: list[dict], detalles_by_nv: dict, total_dia: int) -> bytes:
    pages: list[list[str]] = []
    cmds: list[str] = []
    y = 0

    def new_page():
        nonlocal cmds, y
        if cmds:
            pages.append(cmds)
        cmds = []
        y = 786
        pdf_rect(cmds, 0, 792, 595, 50, "0.89 0.02 0.07")
        pdf_text(cmds, 40, 812, "DISTRIBUIDORA RISEK", 18, True, "1 1 1")
        pdf_text(cmds, 395, 816, "Resumen diario NV", 12, True, "1 1 1")
        pdf_text(cmds, 395, 800, fecha, 9, False, "1 1 1")
        pdf_rect(cmds, 40, 742, 515, 38, "0.97 0.97 0.97")
        pdf_text(cmds, 55, 765, f"Vendedor: {vendedor or 'Todos'}", 10, True)
        pdf_text(cmds, 55, 750, f"Notas de venta: {len(headers)}", 9)
        pdf_text(cmds, 395, 758, f"Total dia: {money_clp(total_dia)}", 13, True, "0.89 0.02 0.07")
        y = 720

    def ensure_space(height: int):
        nonlocal y
        if y - height < 45:
            new_page()

    new_page()
    for h in headers:
        detalles = detalles_by_nv.get((to_int(h.get('venta_numero'), 0), clean_text(h.get('local_codigo'), 10) or '01'), [])
        block_h = 88 + max(1, len(detalles)) * 18
        ensure_space(min(block_h, 300))
        venta_numero = to_int(h.get('venta_numero'), 0)
        total_nv = to_int(h.get('venta_totalventa'), 0)
        pdf_rect(cmds, 40, y - 24, 515, 26, "0.11 0.13 0.17")
        pdf_text(cmds, 52, y - 7, f"NV {venta_numero}", 12, True, "1 1 1")
        pdf_text(cmds, 145, y - 7, f"Hora {clean_text(h.get('venta_hora'), 12) or '-'}", 9, False, "1 1 1")
        pdf_text(cmds, 430, y - 7, money_clp(total_nv), 12, True, "1 1 1")
        y -= 42
        pdf_text(cmds, 52, y, clean_text(h.get('cliente_nombre'), 80) or "-", 11, True)
        pdf_text(cmds, 52, y - 14, f"RUT: {clean_text(h.get('cliente_rut'), 20) or '-'}", 8, False, "0.35 0.38 0.43")
        pdf_text(cmds, 260, y - 14, f"Direccion: {clean_text(h.get('venta_direccion'), 70) or '-'}", 8, False, "0.35 0.38 0.43")
        y -= 34
        pdf_rect(cmds, 52, y - 2, 490, 18, "0.89 0.02 0.07")
        pdf_text(cmds, 58, y + 3, "Codigo", 8, True, "1 1 1")
        pdf_text(cmds, 116, y + 3, "Descripcion", 8, True, "1 1 1")
        pdf_text(cmds, 304, y + 3, "Cant", 8, True, "1 1 1")
        pdf_text(cmds, 340, y + 3, "UXE", 8, True, "1 1 1")
        pdf_text(cmds, 376, y + 3, "Precio", 8, True, "1 1 1")
        pdf_text(cmds, 430, y + 3, "Desc", 8, True, "1 1 1")
        pdf_text(cmds, 480, y + 3, "Total", 8, True, "1 1 1")
        y -= 18
        if not detalles:
            pdf_text(cmds, 58, y, "Sin detalle asociado", 8, False, "0.5 0.5 0.5")
            y -= 18
        for idx, d in enumerate(detalles):
            ensure_space(24)
            if idx % 2 == 0:
                pdf_rect(cmds, 52, y - 5, 490, 16, "0.97 0.97 0.97")
            pdf_text(cmds, 58, y, clean_text(d.get('producto_codigo'), 14) or "-", 7)
            pdf_text(cmds, 116, y, clean_text(d.get('venta_descripcion'), 34) or "-", 7)
            pdf_text(cmds, 306, y, f"{to_float(d.get('venta_cantidad'), 0) or 0:g}", 7)
            pdf_text(cmds, 342, y, f"{to_float(d.get('venta_unidadenvase'), 0) or 0:g}", 7)
            pdf_text(cmds, 374, y, money_clp(d.get('venta_precio')), 7)
            pdf_text(cmds, 434, y, f"{to_float(d.get('venta_descuentol'), 0) or 0:g}%", 7)
            pdf_text(cmds, 480, y, money_clp(d.get('total_linea')), 7, True)
            y -= 17
        y -= 8
    pages.append(cmds)

    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        None,
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>",
    ]
    kids = []
    for page_cmds in pages:
        stream = "\n".join(page_cmds).encode('latin1', errors='replace')
        content_obj = b"<< /Length %d >>\nstream\n%s\nendstream" % (len(stream), stream)
        page_num = len(objects) + 1
        content_num = len(objects) + 2
        kids.append(f"{page_num} 0 R")
        objects.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents {content_num} 0 R >>".encode('latin1'))
        objects.append(content_obj)
    objects[1] = f"<< /Type /Pages /Kids [{' '.join(kids)}] /Count {len(pages)} >>".encode('latin1')
    chunks = [b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n"]
    offsets = [0]
    for index, obj in enumerate(objects, start=1):
        offsets.append(sum(len(c) for c in chunks))
        chunks.append(f"{index} 0 obj\n".encode('ascii') + obj + b"\nendobj\n")
    xref_offset = sum(len(c) for c in chunks)
    chunks.append(f"xref\n0 {len(objects)+1}\n0000000000 65535 f \n".encode('ascii'))
    for off in offsets[1:]:
        chunks.append(f"{off:010d} 00000 n \n".encode('ascii'))
    chunks.append(f"trailer\n<< /Size {len(objects)+1} /Root 1 0 R /Title ({pdf_escape(title)}) >>\nstartxref\n{xref_offset}\n%%EOF".encode('latin1', errors='replace'))
    return b''.join(chunks)


def nv_like_summary_pdf_bytes(title: str, fecha: str, vendedor: str, headers: list[dict], detalles_by_nv: dict, total_dia: int) -> bytes:
    pages: list[list[str]] = []
    cmds: list[str] = []
    y = 0

    def new_page():
        nonlocal cmds, y
        if cmds:
            pages.append(cmds)
        cmds = []
        pdf_stroke_rect(cmds, 24, 24, 547, 794, "0.88 0.88 0.88", 0.7)
        pdf_rect(cmds, 24, 790, 547, 28, "0.73 0.11 0.11")
        pdf_text(cmds, 42, 800, "DISTRIBUIDORA RISEK", 15, True, "1 1 1")
        pdf_text(cmds, 405, 801, f"Resumen diario {fecha}", 9, True, "1 1 1")
        y = 762

    def ensure_space(height: int):
        if y - height < 58:
            new_page()

    def draw_doc_header(h: dict):
        nonlocal y
        venta_numero = to_int(h.get('venta_numero'), 0)
        pdf_rect(cmds, 40, y - 12, 515, 38, "0.96 0.96 0.96")
        pdf_stroke_rect(cmds, 40, y - 12, 515, 38, "0.82 0.82 0.82", 0.7)
        pdf_text(cmds, 52, y + 8, "RISEK - NOTA DE VENTA", 17, True, "0.73 0.11 0.11")
        pdf_text(cmds, 405, y + 10, f"N servidor: {venta_numero}", 10, True)
        pdf_text(cmds, 405, y - 4, f"Vendedor: {vendedor or '-'}", 8)
        y -= 34
        pdf_text(cmds, 52, y, f"Cliente: {clean_text(h.get('cliente_nombre'), 64) or '-'}", 9, True)
        pdf_text(cmds, 388, y, f"RUT: {clean_text(h.get('cliente_rut'), 20) or '-'}", 8)
        y -= 17
        pdf_text(cmds, 52, y, f"Fecha pedido: {fecha}", 8)
        pdf_text(cmds, 210, y, f"Hora: {clean_text(h.get('venta_hora'), 12) or '-'}", 8)
        pdf_text(cmds, 342, y, f"Total NV: {money_clp(h.get('venta_totalventa'))}", 9, True, "0.73 0.11 0.11")
        y -= 17
        pdf_text(cmds, 52, y, f"Direccion: {clean_text(h.get('venta_direccion'), 82) or '-'}", 8)
        y -= 18
        obs = clean_text(h.get('venta_observacion01'), 90)
        if obs:
            pdf_text(cmds, 52, y, f"Obs: {obs}", 8)
            y -= 18
        y -= 8

    def draw_table_header():
        nonlocal y
        pdf_rect(cmds, 40, y - 6, 515, 20, "0.73 0.11 0.11")
        pdf_text(cmds, 48, y, "Codigo", 8, True, "1 1 1")
        pdf_text(cmds, 112, y, "Producto", 8, True, "1 1 1")
        pdf_text(cmds, 302, y, "UXE", 8, True, "1 1 1")
        pdf_text(cmds, 335, y, "P.Unit", 8, True, "1 1 1")
        pdf_text(cmds, 388, y, "IVA", 8, True, "1 1 1")
        pdf_text(cmds, 428, y, "ILA", 8, True, "1 1 1")
        pdf_text(cmds, 468, y, "Neto", 8, True, "1 1 1")
        pdf_text(cmds, 520, y, "Total", 8, True, "1 1 1")
        y -= 24

    new_page()
    for h in headers:
        detalles = detalles_by_nv.get((to_int(h.get('venta_numero'), 0), clean_text(h.get('local_codigo'), 10) or '01'), [])
        estimated = 190 + max(1, len(detalles)) * 18
        ensure_space(estimated if estimated < 690 else 300)
        block_top = y + 14
        can_frame_block = estimated < 690
        draw_doc_header(h)
        draw_table_header()
        if not detalles:
            pdf_text(cmds, 52, y, "Sin detalle disponible para esta NV.", 9)
            y -= 28
        else:
            for idx, d in enumerate(detalles):
                ensure_space(80)
                if idx % 2 == 0:
                    pdf_rect(cmds, 40, y - 5, 515, 16, "0.98 0.98 0.98")
                pdf_text(cmds, 48, y, (clean_text(d.get('producto_codigo'), 12) or '-')[:12], 8)
                pdf_text(cmds, 112, y, (clean_text(d.get('venta_descripcion'), 24) or '-')[:24], 8)
                pdf_text(cmds, 302, y, f"{to_float(d.get('venta_unidadenvase'), 0) or 0:g}", 8)
                pdf_text(cmds, 335, y, money_clp(d.get('venta_precio')), 8)
                pdf_text(cmds, 388, y, money_clp(d.get('venta_lineaiva')), 8)
                pdf_text(cmds, 428, y, money_clp(d.get('venta_lineaila')), 8)
                pdf_text(cmds, 468, y, money_clp(d.get('venta_lineaneto')), 8)
                pdf_text(cmds, 520, y, money_clp(d.get('total_linea')), 8)
                pdf_line(cmds, 40, y - 8, 555, y - 8, "0.91 0.91 0.91", 0.4)
                y -= 18
        y -= 10
        total_ila = to_int(h.get('venta_ila1'), 0)
        box_h = 84 if total_ila > 0 else 64
        pdf_rect(cmds, 355, y - box_h + 10, 200, box_h, "0.96 0.96 0.96")
        pdf_stroke_rect(cmds, 355, y - box_h + 10, 200, box_h, "0.80 0.80 0.80", 0.7)
        pdf_text(cmds, 374, y - 8, "NETO", 9, True)
        pdf_text(cmds, 470, y - 8, money_clp(h.get('venta_neto1')), 9, True)
        pdf_text(cmds, 374, y - 26, "IVA 19%", 9, True)
        pdf_text(cmds, 470, y - 26, money_clp(h.get('venta_iva1')), 9, True)
        total_y = y - 49
        if total_ila > 0:
            pdf_text(cmds, 374, y - 44, "ILA", 9, True)
            pdf_text(cmds, 470, y - 44, money_clp(total_ila), 9, True)
            total_y = y - 67
        pdf_line(cmds, 368, total_y + 13, 542, total_y + 13, "0.73 0.11 0.11", 0.8)
        pdf_text(cmds, 374, total_y, "TOTAL", 13, True, "0.73 0.11 0.11")
        pdf_text(cmds, 470, total_y, money_clp(h.get('venta_totalventa')), 12, True, "0.73 0.11 0.11")
        y -= (92 if total_ila > 0 else 72)
        if can_frame_block:
            block_bottom = y + 2
            pdf_stroke_rect(cmds, 34, block_bottom, 527, max(40, block_top - block_bottom), "0.78 0.78 0.78", 0.8)
        y -= 34
    ensure_space(90)
    pdf_rect(cmds, 40, y - 46, 515, 54, "0.73 0.11 0.11")
    pdf_text(cmds, 60, y - 18, f"TOTAL GENERAL DEL DIA ({len(headers)} NV)", 13, True, "1 1 1")
    pdf_text(cmds, 395, y - 18, money_clp(total_dia), 17, True, "1 1 1")
    pages.append(cmds)

    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        None,
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>",
    ]
    kids = []
    for page_cmds in pages:
        stream = "\n".join(page_cmds).encode('latin1', errors='replace')
        content_obj = b"<< /Length %d >>\nstream\n%s\nendstream" % (len(stream), stream)
        page_num = len(objects) + 1
        content_num = len(objects) + 2
        kids.append(f"{page_num} 0 R")
        objects.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents {content_num} 0 R >>".encode('latin1'))
        objects.append(content_obj)
    objects[1] = f"<< /Type /Pages /Kids [{' '.join(kids)}] /Count {len(pages)} >>".encode('latin1')
    chunks = [b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n"]
    offsets = [0]
    for index, obj in enumerate(objects, start=1):
        offsets.append(sum(len(c) for c in chunks))
        chunks.append(f"{index} 0 obj\n".encode('ascii') + obj + b"\nendobj\n")
    xref_offset = sum(len(c) for c in chunks)
    chunks.append(f"xref\n0 {len(objects)+1}\n0000000000 65535 f \n".encode('ascii'))
    for off in offsets[1:]:
        chunks.append(f"{off:010d} 00000 n \n".encode('ascii'))
    chunks.append(f"trailer\n<< /Size {len(objects)+1} /Root 1 0 R /Title ({pdf_escape(title)}) >>\nstartxref\n{xref_offset}\n%%EOF".encode('latin1', errors='replace'))
    return b''.join(chunks)


def reporte_vendedor_pdf_bytes(
    title: str,
    vendedor: str,
    fecha: str,
    mes_label: str,
    summary: dict,
    prev_summary: dict,
    daily: list[dict],
    top_clientes: list[dict],
    monthly_year: list[dict],
) -> bytes:
    logo_jpeg: bytes | None = None
    logo_width = 1
    logo_height = 1
    try:
        logo_path = Path(__file__).resolve().parent / 'assets' / 'logo_risek.png'
        with Image.open(logo_path) as logo_image:
            logo_rgb = logo_image.convert('RGB')
            logo_rgb.thumbnail((460, 140))
            logo_width, logo_height = logo_rgb.size
            logo_buffer = io.BytesIO()
            logo_rgb.save(logo_buffer, format='JPEG', quality=92, optimize=True)
            logo_jpeg = logo_buffer.getvalue()
    except Exception:
        logo_jpeg = None

    pages: list[list[str]] = []
    cmds: list[str] = []
    y = 0

    def new_page(subtitle: str = ""):
        nonlocal cmds, y
        if cmds:
            pages.append(cmds)
        cmds = []
        pdf_rect(cmds, 0, 786, 595, 56, "0.88 0.01 0.06")
        if logo_jpeg:
            pdf_rect(cmds, 24, 796, 118, 36, "1 1 1")
            cmds.append("q 108 0 0 28 29 800 cm /Logo Do Q")
        else:
            pdf_text(cmds, 32, 807, "RISEK", 22, True, "1 1 1")
        pdf_text(cmds, 160, 813, "REPORTE COMERCIAL", 14, True, "1 1 1")
        pdf_text(cmds, 160, 798, "Gestion mensual del vendedor", 8, False, "1 1 1")
        pdf_text(cmds, 484, 814, fecha, 9, True, "1 1 1")
        if subtitle:
            pdf_text(cmds, 484, 799, subtitle, 8, False, "1 1 1")
        pdf_stroke_rect(cmds, 24, 24, 547, 800, "0.82 0.82 0.82", 0.8)
        pdf_text(cmds, 36, 34, "Distribuidora RISEK | Reporte confidencial para gestion comercial", 7, False, "0.42 0.44 0.50")
        y = 752

    def ensure_space(height: int):
        if y - height < 52:
            new_page("continuacion")

    def card(x: int, yy: int, w: int, h: int, label: str, value: str, accent: bool = False):
        pdf_rect(cmds, x, yy, w, h, "0.97 0.97 0.97")
        pdf_stroke_rect(cmds, x, yy, w, h, "0.84 0.84 0.84", 0.7)
        pdf_text(cmds, x + 12, yy + h - 18, label, 8, True, "0.35 0.37 0.42")
        value_size = 11 if len(value) > 15 else 14
        pdf_text(cmds, x + 12, yy + 16, value, value_size, True, "0.83 0.02 0.06" if accent else "0.08 0.10 0.14")

    def draw_bar_chart(x: int, yy: int, w: int, h: int, rows: list[dict], total_key: str, title_text: str):
        pdf_text(cmds, x, yy + h + 18, title_text, 11, True)
        pdf_line(cmds, x, yy, x + w, yy, "0.75 0.75 0.75", 0.7)
        max_total = max([to_int(r.get(total_key), 0) for r in rows] + [1])
        count = max(len(rows), 1)
        gap = 3
        bar_w = max(4, int((w - gap * (count - 1)) / count))
        for idx, row in enumerate(rows):
            total = to_int(row.get(total_key), 0)
            bh = int((total / max_total) * (h - 12)) if max_total > 0 else 0
            bx = x + idx * (bar_w + gap)
            pdf_rect(cmds, bx, yy + 1, bar_w, max(1, bh), "0.83 0.02 0.06" if total > 0 else "0.86 0.86 0.86")
        if rows:
            first = clean_text(rows[0].get('fecha'), 10) or ''
            last = clean_text(rows[-1].get('fecha'), 10) or ''
            first_label = clean_text(rows[0].get('mes_label'), 20) or (first[8:10] if len(first) >= 10 else first)
            last_label = clean_text(rows[-1].get('mes_label'), 20) or (last[8:10] if len(last) >= 10 else last)
            pdf_text(cmds, x, yy - 14, first_label[:10], 7, False, "0.42 0.44 0.50")
            pdf_text(cmds, x + w - 32, yy - 14, last_label[:10], 7, False, "0.42 0.44 0.50")
        pdf_text(cmds, x + w - 120, yy + h + 18, f"Max {money_clp(max_total)}", 8, False, "0.42 0.44 0.50")

    venta_total = to_int(summary.get('venta_total'), 0)
    venta_neta = to_int(summary.get('venta_neta'), 0)
    venta_iva = to_int(summary.get('venta_iva'), 0)
    venta_ila = to_int(summary.get('venta_ila'), 0)
    facturas = to_int(summary.get('facturas'), 0)
    boletas = to_int(summary.get('boletas'), 0)
    nc = to_int(summary.get('notas_credito'), 0)
    clientes = to_int(summary.get('clientes'), 0)
    prev_total = to_int(prev_summary.get('venta_total'), 0)
    diff = venta_total - prev_total
    pct = int(round((diff / prev_total) * 100)) if prev_total else (100 if venta_total else 0)

    new_page(mes_label)
    pdf_text(cmds, 40, y, title, 18, True, "0.08 0.10 0.14")
    pdf_text(cmds, 40, y - 18, f"Vendedor: {vendedor}", 10, True, "0.35 0.37 0.42")
    pdf_text(cmds, 40, y - 34, "Valores expresados en pesos chilenos. NC/CE descuentan ventas.", 8, False, "0.35 0.37 0.42")
    y -= 82
    card(40, y, 120, 58, "TOTAL FINAL", money_clp(venta_total), True)
    card(174, y, 120, 58, "NETO", money_clp(venta_neta))
    card(308, y, 112, 58, "IVA", money_clp(venta_iva))
    card(434, y, 120, 58, "ILA", money_clp(venta_ila))
    y -= 84
    card(40, y, 120, 52, "FE", f"{facturas} | {money_clp(summary.get('venta_facturas'))}")
    card(174, y, 120, 52, "BO", f"{boletas} | {money_clp(summary.get('venta_boletas'))}")
    card(308, y, 112, 52, "NC / CE", f"{nc} | {money_clp(summary.get('total_nc'))}", True)
    card(434, y, 120, 52, "DOCUMENTOS", str(to_int(summary.get('documentos'), 0)))
    y -= 96
    draw_bar_chart(44, y - 140, 502, 130, daily, 'venta_neta', f"Ventas netas por dia - {mes_label}")
    y -= 176
    pdf_rect(cmds, 40, y - 36, 515, 42, "0.11 0.13 0.17")
    pdf_text(cmds, 58, y - 10, "Comparativo mes anterior", 12, True, "1 1 1")
    pdf_text(cmds, 305, y - 10, f"Mes actual {money_clp(venta_total)}", 10, True, "1 1 1")
    pdf_text(cmds, 305, y - 26, f"Mes anterior {money_clp(prev_total)}", 9, False, "1 1 1")
    pdf_text(cmds, 455, y - 18, f"{money_clp(diff)}", 11, True, "1 1 1")
    y -= 72
    pdf_text(cmds, 40, y, "Mejores clientes del mes", 12, True)
    y -= 22
    pdf_rect(cmds, 40, y - 5, 515, 19, "0.83 0.02 0.06")
    pdf_text(cmds, 50, y, "Cliente", 8, True, "1 1 1")
    pdf_text(cmds, 332, y, "Docs", 8, True, "1 1 1")
    pdf_text(cmds, 382, y, "Ultima", 8, True, "1 1 1")
    pdf_text(cmds, 470, y, "Venta", 8, True, "1 1 1")
    y -= 21
    for idx, c in enumerate(top_clientes[:8]):
        if idx % 2 == 0:
            pdf_rect(cmds, 40, y - 5, 515, 17, "0.97 0.97 0.97")
        pdf_text(cmds, 50, y, (clean_text(c.get('cliente_nombre'), 42) or '-')[:42], 8)
        pdf_text(cmds, 332, y, str(to_int(c.get('documentos'), 0)), 8)
        pdf_text(cmds, 382, y, clean_text(c.get('ultima_fecha'), 10) or '-', 8)
        pdf_text(cmds, 470, y, money_clp(c.get('venta')), 8, True)
        y -= 18

    new_page("detalle")
    pdf_text(cmds, 40, y, "Detalle diario del mes", 14, True)
    y -= 24
    pdf_rect(cmds, 40, y - 5, 515, 19, "0.83 0.02 0.06")
    pdf_text(cmds, 52, y, "Fecha", 8, True, "1 1 1")
    pdf_text(cmds, 125, y, "Docs", 8, True, "1 1 1")
    pdf_text(cmds, 190, y, "Neto", 8, True, "1 1 1")
    pdf_text(cmds, 285, y, "IVA", 8, True, "1 1 1")
    pdf_text(cmds, 370, y, "ILA", 8, True, "1 1 1")
    pdf_text(cmds, 455, y, "Total", 8, True, "1 1 1")
    y -= 21
    for idx, row in enumerate(daily):
        ensure_space(30)
        if idx % 2 == 0:
            pdf_rect(cmds, 40, y - 5, 515, 17, "0.97 0.97 0.97")
        pdf_text(cmds, 52, y, clean_text(row.get('fecha'), 10) or '-', 8)
        pdf_text(cmds, 138, y, str(to_int(row.get('documentos'), 0)), 8)
        pdf_text(cmds, 190, y, money_clp(row.get('venta_neta')), 8, True)
        pdf_text(cmds, 285, y, money_clp(row.get('venta_iva')), 8)
        pdf_text(cmds, 370, y, money_clp(row.get('venta_ila')), 8)
        pdf_text(cmds, 455, y, money_clp(row.get('venta_total')), 8, True)
        y -= 18
    y -= 20
    ensure_space(160)
    pdf_text(cmds, 40, y, f"Ventas por mes del ano {mes_label[-4:]}", 14, True)
    y -= 26
    draw_bar_chart(44, y - 118, 502, 108, monthly_year, 'venta_total', "Evolucion mensual")
    y -= 150
    pdf_rect(cmds, 40, y - 5, 515, 19, "0.83 0.02 0.06")
    pdf_text(cmds, 52, y, "Mes", 8, True, "1 1 1")
    pdf_text(cmds, 130, y, "Docs", 8, True, "1 1 1")
    pdf_text(cmds, 190, y, "Neto", 8, True, "1 1 1")
    pdf_text(cmds, 285, y, "IVA", 8, True, "1 1 1")
    pdf_text(cmds, 370, y, "ILA", 8, True, "1 1 1")
    pdf_text(cmds, 455, y, "Total", 8, True, "1 1 1")
    y -= 21
    for idx, v in enumerate(monthly_year[:12]):
        ensure_space(30)
        if idx % 2 == 0:
            pdf_rect(cmds, 40, y - 5, 515, 17, "0.97 0.97 0.97")
        pdf_text(cmds, 52, y, clean_text(v.get('mes_label'), 20) or '-', 8)
        pdf_text(cmds, 142, y, str(to_int(v.get('documentos'), 0)), 8)
        pdf_text(cmds, 190, y, money_clp(v.get('venta_neta')), 8, True)
        pdf_text(cmds, 285, y, money_clp(v.get('venta_iva')), 8)
        pdf_text(cmds, 370, y, money_clp(v.get('venta_ila')), 8)
        pdf_text(cmds, 455, y, money_clp(v.get('venta_total')), 8, True)
        y -= 18
    pages.append(cmds)

    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        None,
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>",
    ]
    logo_object_number = None
    if logo_jpeg:
        logo_object_number = len(objects) + 1
        objects.append(
            b"<< /Type /XObject /Subtype /Image /Width %d /Height %d /ColorSpace /DeviceRGB "
            b"/BitsPerComponent 8 /Filter /DCTDecode /Length %d >>\nstream\n%s\nendstream"
            % (logo_width, logo_height, len(logo_jpeg), logo_jpeg)
        )
    kids = []
    for page_cmds in pages:
        stream = "\n".join(page_cmds).encode('latin1', errors='replace')
        content_obj = b"<< /Length %d >>\nstream\n%s\nendstream" % (len(stream), stream)
        page_num = len(objects) + 1
        content_num = len(objects) + 2
        kids.append(f"{page_num} 0 R")
        xobject = f" /XObject << /Logo {logo_object_number} 0 R >>" if logo_object_number else ""
        objects.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R /F2 4 0 R >>{xobject} >> /Contents {content_num} 0 R >>".encode('latin1'))
        objects.append(content_obj)
    objects[1] = f"<< /Type /Pages /Kids [{' '.join(kids)}] /Count {len(pages)} >>".encode('latin1')
    chunks = [b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n"]
    offsets = [0]
    for index, obj in enumerate(objects, start=1):
        offsets.append(sum(len(c) for c in chunks))
        chunks.append(f"{index} 0 obj\n".encode('ascii') + obj + b"\nendobj\n")
    xref_offset = sum(len(c) for c in chunks)
    chunks.append(f"xref\n0 {len(objects)+1}\n0000000000 65535 f \n".encode('ascii'))
    for off in offsets[1:]:
        chunks.append(f"{off:010d} 00000 n \n".encode('ascii'))
    chunks.append(f"trailer\n<< /Size {len(objects)+1} /Root 1 0 R /Title ({pdf_escape(title)}) >>\nstartxref\n{xref_offset}\n%%EOF".encode('latin1', errors='replace'))
    return b''.join(chunks)


def reporte_vendedor_mensual_pdf_bytes(
    title: str,
    vendedor: str,
    fecha: str,
    mes_label: str,
    summary: dict,
    daily: list[dict],
) -> bytes:
    logo_jpeg: bytes | None = None
    logo_width = 1
    logo_height = 1
    try:
        logo_path = Path(__file__).resolve().parent / 'assets' / 'logo_risek.png'
        with Image.open(logo_path) as logo_image:
            logo_rgb = logo_image.convert('RGB')
            logo_rgb.thumbnail((460, 140))
            logo_width, logo_height = logo_rgb.size
            logo_buffer = io.BytesIO()
            logo_rgb.save(logo_buffer, format='JPEG', quality=92, optimize=True)
            logo_jpeg = logo_buffer.getvalue()
    except Exception:
        logo_jpeg = None

    empresa_nombre = clean_text(os.getenv('EMPRESA_NOMBRE'), 90) or 'DISTRIBUIDORA RISEK'
    empresa_rut = clean_text(os.getenv('EMPRESA_RUT'), 30)
    empresa_direccion = clean_text(os.getenv('EMPRESA_DIRECCION'), 110)
    empresa_telefono = clean_text(os.getenv('EMPRESA_TELEFONO'), 40)
    empresa_email = clean_text(os.getenv('EMPRESA_EMAIL'), 80) or clean_text(os.getenv('SMTP_FROM'), 80)
    empresa_lines = [
        value for value in (
            f'RUT: {empresa_rut}' if empresa_rut else None,
            empresa_direccion,
            ' | '.join(value for value in (empresa_telefono, empresa_email) if value),
        ) if value
    ]

    pages: list[list[str]] = []
    cmds: list[str] = []
    y = 0

    def text_right(x_right: int, yy: int, text: str, size: int = 8, bold: bool = False, color: str = '0.12 0.14 0.18'):
        estimated_width = len(text) * size * 0.52
        pdf_text(cmds, int(x_right - estimated_width), yy, text, size, bold, color)

    def new_page(continuation: bool = False):
        nonlocal cmds, y
        if cmds:
            pages.append(cmds)
        cmds = []
        pdf_stroke_rect(cmds, 24, 24, 547, 794, '0.80 0.82 0.85', 0.8)
        pdf_rect(cmds, 24, 752, 547, 66, '0.97 0.97 0.97')
        pdf_rect(cmds, 24, 748, 547, 4, '0.88 0.01 0.06')
        if logo_jpeg:
            pdf_rect(cmds, 36, 766, 118, 40, '1 1 1')
            cmds.append('q 108 0 0 30 41 771 cm /Logo Do Q')
        else:
            pdf_text(cmds, 42, 782, 'RISEK', 22, True, '0.88 0.01 0.06')
        pdf_text(cmds, 174, 794, empresa_nombre, 14, True, '0.12 0.14 0.18')
        for idx, line in enumerate(empresa_lines[:3]):
            pdf_text(cmds, 174, 779 - idx * 12, line, 7, False, '0.36 0.39 0.44')
        text_right(550, 794, fecha, 8, True)
        if continuation:
            text_right(550, 779, 'Continuacion', 8, False, '0.36 0.39 0.44')
        pdf_text(cmds, 36, 34, 'Distribuidora RISEK | Reporte confidencial de gestion comercial', 7, False, '0.42 0.44 0.50')
        y = 722

    def table_header():
        nonlocal y
        pdf_rect(cmds, 40, y - 5, 515, 24, '0.03 0.27 0.38')
        pdf_text(cmds, 55, y + 2, 'DIA', 9, True, '1 1 1')
        pdf_text(cmds, 150, y + 2, 'NETO', 9, True, '1 1 1')
        pdf_text(cmds, 300, y + 2, 'TOTAL', 9, True, '1 1 1')
        pdf_text(cmds, 448, y + 2, 'PAGADO', 9, True, '1 1 1')
        y -= 25

    new_page()
    pdf_text(cmds, 40, y, title, 17, True, '0.12 0.14 0.18')
    pdf_text(cmds, 40, y - 20, f'Periodo consultado: {mes_label}', 10, True, '0.88 0.01 0.06')
    pdf_text(cmds, 40, y - 37, f'Vendedor: {vendedor}', 9, True, '0.35 0.38 0.43')
    y -= 70

    summary_values = (
        ('NETO', summary.get('venta_neta')),
        ('TOTAL', summary.get('venta_total')),
        ('PAGADO', summary.get('venta_pagado')),
    )
    for idx, (label, value) in enumerate(summary_values):
        x = 40 + idx * 172
        pdf_rect(cmds, x, y - 50, 158, 54, '0.97 0.97 0.97')
        pdf_stroke_rect(cmds, x, y - 50, 158, 54, '0.82 0.83 0.85', 0.7)
        pdf_text(cmds, x + 12, y - 14, label, 8, True, '0.36 0.39 0.44')
        text_right(x + 146, y - 37, money_clp(value), 13, True, '0.88 0.01 0.06' if label == 'TOTAL' else '0.12 0.14 0.18')
    y -= 76
    pdf_text(
        cmds, 40, y,
        f"FE: {to_int(summary.get('facturas'), 0)}   BO: {to_int(summary.get('boletas'), 0)}   "
        f"CE: {to_int(summary.get('notas_credito'), 0)}   Documentos: {to_int(summary.get('documentos'), 0)}",
        8, True, '0.35 0.38 0.43'
    )
    pdf_text(cmds, 40, y - 15, 'FE y BO suman. CE se descuenta en Neto, Total y Pagado.', 8, False, '0.35 0.38 0.43')
    y -= 48
    pdf_text(cmds, 40, y, 'RESUMEN DIARIO DEL PERIODO', 11, True, '0.12 0.14 0.18')
    y -= 26
    table_header()

    visible_daily = [
        row for row in daily
        if to_int(row.get('documentos'), 0) != 0
        or to_int(row.get('venta_neta'), 0) != 0
        or to_int(row.get('venta_total'), 0) != 0
        or to_int(row.get('venta_pagado'), 0) != 0
    ]
    for idx, row in enumerate(visible_daily):
        if y < 72:
            new_page(True)
            pdf_text(cmds, 40, y, f'RESUMEN DIARIO | {mes_label} | {vendedor}', 11, True, '0.12 0.14 0.18')
            y -= 28
            table_header()
        if idx % 2 == 0:
            pdf_rect(cmds, 40, y - 5, 515, 20, '0.96 0.97 0.98')
        pdf_stroke_rect(cmds, 40, y - 5, 515, 20, '0.84 0.85 0.87', 0.45)
        fecha_row = clean_text(row.get('fecha'), 10) or ''
        dia = fecha_row[8:10].lstrip('0') if len(fecha_row) >= 10 else fecha_row
        pdf_text(cmds, 58, y + 1, dia or '-', 8, False, '0.20 0.23 0.28')
        text_right(245, y + 1, money_clp(row.get('venta_neta')), 8)
        text_right(405, y + 1, money_clp(row.get('venta_total')), 8)
        text_right(545, y + 1, money_clp(row.get('venta_pagado')), 8)
        y -= 20

    if y < 82:
        new_page(True)
        pdf_text(cmds, 40, y, f'TOTALES | {mes_label} | {vendedor}', 11, True, '0.12 0.14 0.18')
        y -= 34
    pdf_rect(cmds, 40, y - 7, 515, 28, '0.75 0.82 0.84')
    pdf_text(cmds, 50, y + 1, 'TOTAL', 9, True, '0.18 0.22 0.25')
    text_right(245, y + 1, money_clp(summary.get('venta_neta')), 9, True)
    text_right(405, y + 1, money_clp(summary.get('venta_total')), 9, True)
    text_right(545, y + 1, money_clp(summary.get('venta_pagado')), 9, True)
    pages.append(cmds)

    objects = [
        b'<< /Type /Catalog /Pages 2 0 R >>',
        None,
        b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
        b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>',
    ]
    logo_object_number = None
    if logo_jpeg:
        logo_object_number = len(objects) + 1
        objects.append(
            b'<< /Type /XObject /Subtype /Image /Width %d /Height %d /ColorSpace /DeviceRGB '
            b'/BitsPerComponent 8 /Filter /DCTDecode /Length %d >>\nstream\n%s\nendstream'
            % (logo_width, logo_height, len(logo_jpeg), logo_jpeg)
        )
    kids = []
    for page_cmds in pages:
        stream = '\n'.join(page_cmds).encode('latin1', errors='replace')
        content_obj = b'<< /Length %d >>\nstream\n%s\nendstream' % (len(stream), stream)
        page_num = len(objects) + 1
        content_num = len(objects) + 2
        kids.append(f'{page_num} 0 R')
        xobject = f' /XObject << /Logo {logo_object_number} 0 R >>' if logo_object_number else ''
        objects.append(f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R /F2 4 0 R >>{xobject} >> /Contents {content_num} 0 R >>'.encode('latin1'))
        objects.append(content_obj)
    objects[1] = f"<< /Type /Pages /Kids [{' '.join(kids)}] /Count {len(pages)} >>".encode('latin1')
    chunks = [b'%PDF-1.4\n%\xe2\xe3\xcf\xd3\n']
    offsets = [0]
    for index, obj in enumerate(objects, start=1):
        offsets.append(sum(len(chunk) for chunk in chunks))
        chunks.append(f'{index} 0 obj\n'.encode('ascii') + obj + b'\nendobj\n')
    xref_offset = sum(len(chunk) for chunk in chunks)
    chunks.append(f'xref\n0 {len(objects)+1}\n0000000000 65535 f \n'.encode('ascii'))
    for offset in offsets[1:]:
        chunks.append(f'{offset:010d} 00000 n \n'.encode('ascii'))
    chunks.append(f'trailer\n<< /Size {len(objects)+1} /Root 1 0 R /Title ({pdf_escape(title)}) >>\nstartxref\n{xref_offset}\n%%EOF'.encode('latin1', errors='replace'))
    return b''.join(chunks)


def send_pdf_email(to_email: str, subject: str, body: str, filename: str, pdf: bytes):
    smtp_host = os.getenv('SMTP_HOST')
    smtp_from = os.getenv('SMTP_FROM') or os.getenv('SMTP_USER')
    if not smtp_host or not smtp_from:
        raise HTTPException(500, 'SMTP no configurado. Defina SMTP_HOST y SMTP_FROM/SMTP_USER en la API.')
    smtp_port = int(os.getenv('SMTP_PORT', '587'))
    smtp_user = os.getenv('SMTP_USER')
    smtp_pass = os.getenv('SMTP_PASS')
    use_tls = os.getenv('SMTP_TLS', '1').lower() not in ('0', 'false', 'no')
    msg = EmailMessage()
    msg['From'] = smtp_from
    msg['To'] = to_email
    msg['Subject'] = subject
    msg.set_content(body)
    msg.add_attachment(pdf, maintype='application', subtype='pdf', filename=filename)
    with smtplib.SMTP(smtp_host, smtp_port, timeout=30) as smtp:
        if use_tls:
            smtp.starttls()
        if smtp_user and smtp_pass:
            smtp.login(smtp_user, smtp_pass)
        smtp.send_message(msg)


def image_to_thumbnail_base64(raw: bytes, max_size: int = 180) -> str | None:
    if not raw:
        return None
    try:
        from PIL import Image
    except Exception:
        return base64.b64encode(raw).decode('ascii') if len(raw) <= 120_000 else None

    try:
        with Image.open(io.BytesIO(raw)) as img:
            img = img.convert('RGB')
            img.thumbnail((max_size, max_size))
            out = io.BytesIO()
            img.save(out, format='JPEG', quality=72, optimize=True)
            return base64.b64encode(out.getvalue()).decode('ascii')
    except Exception:
        return None


@app.get('/health')
def health():
    return {'ok': True, 'service': 'risek-api'}


@app.get('/app/version')
def app_version():
    metadata = {}
    if APP_VERSION_PATH.exists():
        try:
            metadata = json.loads(APP_VERSION_PATH.read_text(encoding='utf-8'))
        except Exception:
            metadata = {}
    apk_exists = APP_APK_PATH.exists()
    version_code = to_int(os.getenv('RISEK_APP_VERSION_CODE') or metadata.get('version_code'), 0)
    version_name = clean_text(os.getenv('RISEK_APP_VERSION_NAME') or metadata.get('version_name'), 40) or 'sin version publicada'
    notes = clean_text(os.getenv('RISEK_APP_NOTES') or metadata.get('notes'), 255) or 'Actualización RISEK Offline disponible'
    return {
        'available': apk_exists and version_code > 0,
        'version_code': version_code,
        'version_name': version_name,
        'apk_url': '/app/apk' if apk_exists and version_code > 0 else None,
        'notes': notes,
    }


@app.get('/app/apk')
def app_apk():
    if not APP_APK_PATH.exists():
        raise HTTPException(404, 'APK no publicado en servidor')
    return FileResponse(
        path=str(APP_APK_PATH),
        media_type='application/vnd.android.package-archive',
        filename='apprisek.apk',
    )



@app.get('/secusers')
def secusers():
    """Usuarios de login desde secuser, vinculados al vendedor real.

    Regla RISEK móvil: el usuario se selecciona en login, pero el vendedor que
    se graba en la NV sale de vendedores.vendedor_codigo. Se intenta primero
    la relación secuser.vendedor_codigo -> vendedores.vendedor_codigo. Si la
    base no tiene algún campo opcional, se usa fallback sin romper el login.
    """
    with cursor() as (_, cur):
        rows = []
        try:
            cur.execute("""
                SELECT
                    s.SecUserId AS sec_user_id,
                    s.SecUserName AS sec_user_name,
                    v.vendedor_codigo AS vendedor_codigo
                FROM secuser s
                LEFT JOIN vendedores v
                  ON CAST(v.vendedor_codigo AS CHAR) = CAST(s.vendedor_codigo AS CHAR)
                WHERE s.SecUserName IS NOT NULL
                  AND s.SecUserName <> ''
                ORDER BY s.SecUserName
            """)
            rows = cur.fetchall()
        except Exception:
            # Fallback para instalaciones donde todavía no existe la relación con vendedores.
            cur.execute("""
                SELECT
                    SecUserId AS sec_user_id,
                    SecUserName AS sec_user_name,
                    vendedor_codigo AS vendedor_codigo
                FROM secuser
                WHERE SecUserName IS NOT NULL AND SecUserName <> ''
                ORDER BY SecUserName
            """)
            rows = cur.fetchall()
        safe = []
        for r in clean_rows(rows):
            user_id = to_int(r.get('sec_user_id'), 0)
            name = clean_text(r.get('sec_user_name'), 100) or ''
            vend = clean_text(r.get('vendedor_codigo'), 20)
            if user_id > 0 and name:
                safe.append({'sec_user_id': user_id, 'sec_user_name': name, 'vendedor_codigo': vend})
        return json_safe(safe)


@app.post('/login', response_model=LoginResponse)
def login(req: LoginRequest):
    with cursor() as (_, cur):
        try:
            cur.execute("""
                SELECT
                    s.SecUserId,
                    s.SecUserName,
                    s.SecUserPassword,
                    v.vendedor_codigo AS vendedor_codigo
                FROM secuser s
                LEFT JOIN vendedores v
                  ON CAST(v.vendedor_codigo AS CHAR) = CAST(s.vendedor_codigo AS CHAR)
                WHERE s.SecUserId=%s
            """, (req.sec_user_id,))
            u = first_or_none(cur)
        except Exception:
            cur.execute(
                "SELECT SecUserId, SecUserName, SecUserPassword, vendedor_codigo FROM secuser WHERE SecUserId=%s",
                (req.sec_user_id,),
            )
            u = first_or_none(cur)
        if not u:
            return LoginResponse(ok=False, message='Usuario no existe')
        if (u.get('SecUserPassword') or '') != (req.password or ''):
            return LoginResponse(ok=False, message='Clave incorrecta')
        vendedor_codigo = clean_text(u.get('vendedor_codigo'), 20)
        token = create_token(u['SecUserId'], vendedor_codigo)
        message = 'Login OK' if vendedor_codigo else 'Login OK: usuario habilitado solo para repartos'
        return LoginResponse(ok=True, token=token, vendedor_codigo=vendedor_codigo, message=message)


def page_limit_offset(limit, offset) -> tuple[int, int]:
    page_limit = to_int(limit, 0)
    page_offset = to_int(offset, 0)
    if page_limit <= 0:
        page_limit = 0
    else:
        page_limit = min(page_limit, 3000)
    return page_limit, max(page_offset, 0)


@app.get('/bootstrap/clientes')
def bootstrap_clientes(limit: int = 0, offset: int = 0):
    page_limit, page_offset = page_limit_offset(limit, offset)
    page_sql = " LIMIT %s OFFSET %s" if page_limit > 0 else ""
    params = (page_limit, page_offset) if page_limit > 0 else ()
    with cursor() as (_, cur):
        cur.execute(f"""
            SELECT
                CAST(cliente_rut AS CHAR) AS cliente_rut,
                cliente_nombre AS cliente_nombre,
                cliente_direccion AS cliente_direccion,
                CAST(Ciudad_codigo AS CHAR) AS Ciudad_codigo,
                Comuna AS Comuna,
                cliente_estado AS cliente_estado,
                ruta_id AS ruta_id,
                CAST(lista_codigo AS CHAR) AS lista_codigo,
                cliente_geo AS cliente_geo,
                CAST(cliente_vendedor AS CHAR) AS cliente_vendedor
            FROM clientes
            WHERE cliente_rut IS NOT NULL AND cliente_rut <> ''
            ORDER BY cliente_nombre
        """ + page_sql, params)
        out = []
        for c in clean_rows(cur.fetchall()):
            rut = clean_text(c.get('cliente_rut'), 10) or ''
            if not rut:
                continue
            out.append({
                'cliente_rut': rut,
                'cliente_nombre': clean_text(c.get('cliente_nombre'), 50) or rut,
                'cliente_direccion': clean_text(c.get('cliente_direccion'), 50),
                'Ciudad_codigo': clean_text(c.get('Ciudad_codigo'), 20),
                'Comuna': clean_text(c.get('Comuna'), 20),
                'cliente_estado': clean_text(c.get('cliente_estado'), 1),
                'ruta_id': to_int(c.get('ruta_id'), None) if c.get('ruta_id') is not None else None,
                'lista_codigo': clean_text(c.get('lista_codigo'), 5),
                'cliente_geo': clean_text(c.get('cliente_geo'), 60),
                'cliente_vendedor': clean_text(c.get('cliente_vendedor'), 20),
            })
        return json_safe(out)


@app.get('/bootstrap/clientes-desbloqueados')
def bootstrap_clientes_desbloqueados(limit: int = 0, offset: int = 0):
    """Sincronización rápida para clientes habilitados.

    Actualiza clientes cuyo estado no está bloqueado. Sirve para reflejar en el
    móvil clientes recientemente desbloqueados sin descargar todo el catálogo.
    """
    page_limit, page_offset = page_limit_offset(limit, offset)
    page_sql = " LIMIT %s OFFSET %s" if page_limit > 0 else ""
    params = (page_limit, page_offset) if page_limit > 0 else ()
    with cursor() as (_, cur):
        cur.execute(f"""
            SELECT
                CAST(cliente_rut AS CHAR) AS cliente_rut,
                cliente_nombre AS cliente_nombre,
                cliente_direccion AS cliente_direccion,
                CAST(Ciudad_codigo AS CHAR) AS Ciudad_codigo,
                Comuna AS Comuna,
                cliente_estado AS cliente_estado,
                ruta_id AS ruta_id,
                CAST(lista_codigo AS CHAR) AS lista_codigo,
                cliente_geo AS cliente_geo,
                CAST(cliente_vendedor AS CHAR) AS cliente_vendedor
            FROM clientes
            WHERE cliente_rut IS NOT NULL
              AND cliente_rut <> ''
              AND COALESCE(cliente_estado,'') <> 'B'
            ORDER BY cliente_nombre
        """ + page_sql, params)
        out = []
        for c in clean_rows(cur.fetchall()):
            rut = clean_text(c.get('cliente_rut'), 10) or ''
            if not rut:
                continue
            out.append({
                'cliente_rut': rut,
                'cliente_nombre': clean_text(c.get('cliente_nombre'), 50) or rut,
                'cliente_direccion': clean_text(c.get('cliente_direccion'), 50),
                'Ciudad_codigo': clean_text(c.get('Ciudad_codigo'), 20),
                'Comuna': clean_text(c.get('Comuna'), 20),
                'cliente_estado': clean_text(c.get('cliente_estado'), 1),
                'ruta_id': to_int(c.get('ruta_id'), None) if c.get('ruta_id') is not None else None,
                'lista_codigo': clean_text(c.get('lista_codigo'), 5),
                'cliente_geo': clean_text(c.get('cliente_geo'), 60),
                'cliente_vendedor': clean_text(c.get('cliente_vendedor'), 20),
            })
        return json_safe(out)


@app.get('/bootstrap/clientes/{cliente_rut}')
def bootstrap_cliente(cliente_rut: str):
    rut = clean_text(cliente_rut, 20).strip().upper()
    if not rut:
        return json_safe([])
    with cursor() as (_, cur):
        cur.execute("""
            SELECT
                CAST(cliente_rut AS CHAR) AS cliente_rut,
                cliente_nombre AS cliente_nombre,
                cliente_direccion AS cliente_direccion,
                CAST(Ciudad_codigo AS CHAR) AS Ciudad_codigo,
                Comuna AS Comuna,
                cliente_estado AS cliente_estado,
                ruta_id AS ruta_id,
                CAST(lista_codigo AS CHAR) AS lista_codigo,
                cliente_geo AS cliente_geo,
                CAST(cliente_vendedor AS CHAR) AS cliente_vendedor
            FROM clientes
            WHERE UPPER(TRIM(CAST(cliente_rut AS CHAR))) = %s
            LIMIT 1
        """, (rut,))
        out = []
        for c in clean_rows(cur.fetchall()):
            crut = clean_text(c.get('cliente_rut'), 10) or ''
            if crut:
                out.append({
                    'cliente_rut': crut,
                    'cliente_nombre': clean_text(c.get('cliente_nombre'), 50) or crut,
                    'cliente_direccion': clean_text(c.get('cliente_direccion'), 50),
                    'Ciudad_codigo': clean_text(c.get('Ciudad_codigo'), 20),
                    'Comuna': clean_text(c.get('Comuna'), 20),
                    'cliente_estado': clean_text(c.get('cliente_estado'), 1),
                    'ruta_id': to_int(c.get('ruta_id'), None) if c.get('ruta_id') is not None else None,
                    'lista_codigo': clean_text(c.get('lista_codigo'), 5),
                    'cliente_geo': clean_text(c.get('cliente_geo'), 60),
                    'cliente_vendedor': clean_text(c.get('cliente_vendedor'), 20),
                })
        return json_safe(out)



@app.get('/bootstrap/direcciones')
def bootstrap_direcciones(limit: int = 0, offset: int = 0):
    """
    Devuelve EXCLUSIVAMENTE las direcciones de reparto desde clienteslevel4/clientelevel4.

    Regla RISEK corregida:
      - tabla fuente: clienteslevel4 si existe; si no, clientelevel4.
      - llave de nexo: cliente_rut.
      - dirección visible: cliente_direcciones.
      - ciudad visible: cliente_ciudad.

    No usa clientes.cliente_direccion como fallback porque eso muestra una sola dirección
    y rompe el flujo comercial cuando el cliente tiene varias direcciones de reparto.
    """
    out = []
    page_limit, page_offset = page_limit_offset(limit, offset)
    page_sql = " LIMIT %s OFFSET %s" if page_limit > 0 else ""
    params = (page_limit, page_offset) if page_limit > 0 else ()

    def table_exists(cur, table_name: str) -> bool:
        cur.execute("SHOW TABLES LIKE %s", (table_name,))
        return cur.fetchone() is not None

    def resolve_level4_table(cur) -> str:
        # El usuario ha usado ambos nombres en la documentación/conversación.
        # En MySQL Windows puede no importar mayúscula/minúscula, pero sí el nombre exacto.
        for table_name in ("clienteslevel4", "clientelevel4"):
            if table_exists(cur, table_name):
                return table_name
        raise RuntimeError("No existe tabla clienteslevel4/clientelevel4")

    with cursor() as (_, cur):
        table_name = resolve_level4_table(cur)
        cur.execute(f"SHOW COLUMNS FROM {table_name}")
        raw_cols = [clean_text(r.get('Field')) for r in clean_rows(cur.fetchall())]
        cols_lower = {c.lower(): c for c in raw_cols if c}

        rut_col = cols_lower.get('cliente_rut')
        dir_col = cols_lower.get('cliente_direcciones')
        ciudad_col = cols_lower.get('cliente_ciudad')

        if not rut_col or not dir_col:
            raise RuntimeError(f"{table_name} debe tener cliente_rut y cliente_direcciones")

        # cliente_ciudad es el campo solicitado. Si no existe, se deja vacío; no se toma ciudad desde clientes.
        select_cols = [
            f"CAST({rut_col} AS CHAR) AS cliente_rut",
            f"CAST({dir_col} AS CHAR) AS cliente_direcciones",
            f"CAST({ciudad_col} AS CHAR) AS cliente_ciudad" if ciudad_col else "NULL AS cliente_ciudad",
        ]
        cur.execute(f"""
            SELECT {', '.join(select_cols)}
            FROM {table_name}
            WHERE {rut_col} IS NOT NULL
              AND TRIM(CAST({rut_col} AS CHAR)) <> ''
              AND {dir_col} IS NOT NULL
              AND TRIM(CAST({dir_col} AS CHAR)) <> ''
            ORDER BY {rut_col}, {dir_col}
        """ + page_sql, params)

        for i, d in enumerate(clean_rows(cur.fetchall()), start=1):
            rut = clean_text(d.get('cliente_rut'), 30)
            direccion = clean_text(d.get('cliente_direcciones'), 180)
            ciudad = clean_text(d.get('cliente_ciudad'), 80)
            if not rut or not direccion:
                continue
            # ID estable por fila de lectura. No deduplicamos: si la tabla tiene 5 filas, Android debe recibir 5 opciones.
            out.append({
                'direccion_id': f"L4-{rut}-{i}",
                'cliente_rut': rut,
                'direccion': direccion,
                'comuna': ciudad,
                'ciudad_codigo': ciudad,
                'fuente': table_name,
            })

    return json_safe(out)

@app.get('/bootstrap/direcciones-cliente/{cliente_rut}')
def bootstrap_direcciones_cliente_directo(cliente_rut: str):
    rut = clean_text(cliente_rut, 20).strip().upper()
    if not rut:
        return json_safe([])
    out = []

    def table_exists(cur, table_name: str) -> bool:
        cur.execute("SHOW TABLES LIKE %s", (table_name,))
        return cur.fetchone() is not None

    def resolve_level4_table(cur) -> str:
        for table_name in ("clienteslevel4", "clientelevel4"):
            if table_exists(cur, table_name):
                return table_name
        raise RuntimeError("No existe tabla clienteslevel4/clientelevel4")

    with cursor() as (_, cur):
        table_name = resolve_level4_table(cur)
        cur.execute(f"SHOW COLUMNS FROM {table_name}")
        raw_cols = [clean_text(r.get('Field')) for r in clean_rows(cur.fetchall())]
        cols_lower = {c.lower(): c for c in raw_cols if c}
        rut_col = cols_lower.get('cliente_rut')
        dir_col = cols_lower.get('cliente_direcciones')
        ciudad_col = cols_lower.get('cliente_ciudad')
        if not rut_col or not dir_col:
            raise RuntimeError(f"{table_name} debe tener cliente_rut y cliente_direcciones")
        select_cols = [
            f"CAST({rut_col} AS CHAR) AS cliente_rut",
            f"CAST({dir_col} AS CHAR) AS cliente_direcciones",
            f"CAST({ciudad_col} AS CHAR) AS cliente_ciudad" if ciudad_col else "NULL AS cliente_ciudad",
        ]
        cur.execute(f"""
            SELECT {', '.join(select_cols)}
            FROM {table_name}
            WHERE UPPER(TRIM(CAST({rut_col} AS CHAR))) = %s
              AND {dir_col} IS NOT NULL
              AND TRIM(CAST({dir_col} AS CHAR)) <> ''
            ORDER BY {dir_col}
        """, (rut,))
        for i, d in enumerate(clean_rows(cur.fetchall()), start=1):
            crut = clean_text(d.get('cliente_rut'), 30)
            direccion = clean_text(d.get('cliente_direcciones'), 180)
            ciudad = clean_text(d.get('cliente_ciudad'), 80)
            if crut and direccion:
                out.append({
                    'direccion_id': f"L4-{crut}-{i}",
                    'cliente_rut': crut,
                    'direccion': direccion,
                    'comuna': ciudad,
                    'ciudad_codigo': ciudad,
                    'fuente': table_name,
                })
    return json_safe(out)

@app.get('/bootstrap/direcciones/{cliente_rut}')
def bootstrap_direcciones_cliente(cliente_rut: str):
    """Diagnóstico rápido: permite validar un cliente específico, por ejemplo 76311137-7."""
    resp = bootstrap_direcciones()
    rows = json.loads(resp.body.decode('utf-8'))
    rut = clean_text(cliente_rut, 20).strip().upper()
    return json_safe([r for r in rows if clean_text(r.get('cliente_rut'),20).strip().upper() == rut])


@app.get('/bootstrap/direcciones-desbloqueados')
def bootstrap_direcciones_desbloqueados(limit: int = 0, offset: int = 0):
    """Direcciones de reparto solo para clientes no bloqueados."""
    page_limit, page_offset = page_limit_offset(limit, offset)
    page_sql = " LIMIT %s OFFSET %s" if page_limit > 0 else ""
    params = (page_limit, page_offset) if page_limit > 0 else ()
    out = []

    def table_exists(cur, table_name: str) -> bool:
        cur.execute("SHOW TABLES LIKE %s", (table_name,))
        return cur.fetchone() is not None

    def resolve_level4_table(cur) -> str:
        for table_name in ("clienteslevel4", "clientelevel4"):
            if table_exists(cur, table_name):
                return table_name
        raise RuntimeError("No existe tabla clienteslevel4/clientelevel4")

    with cursor() as (_, cur):
        table_name = resolve_level4_table(cur)
        cur.execute(f"SHOW COLUMNS FROM {table_name}")
        raw_cols = [clean_text(r.get('Field')) for r in clean_rows(cur.fetchall())]
        cols_lower = {c.lower(): c for c in raw_cols if c}
        rut_col = cols_lower.get('cliente_rut')
        dir_col = cols_lower.get('cliente_direcciones')
        ciudad_col = cols_lower.get('cliente_ciudad')
        if not rut_col or not dir_col:
            raise RuntimeError(f"{table_name} debe tener cliente_rut y cliente_direcciones")

        select_cols = [
            f"CAST(l4.{rut_col} AS CHAR) AS cliente_rut",
            f"CAST(l4.{dir_col} AS CHAR) AS cliente_direcciones",
            f"CAST(l4.{ciudad_col} AS CHAR) AS cliente_ciudad" if ciudad_col else "NULL AS cliente_ciudad",
        ]
        cur.execute(f"""
            SELECT {', '.join(select_cols)}
            FROM {table_name} l4
            INNER JOIN clientes c
              ON TRIM(CAST(c.cliente_rut AS CHAR)) = TRIM(CAST(l4.{rut_col} AS CHAR))
            WHERE l4.{rut_col} IS NOT NULL
              AND TRIM(CAST(l4.{rut_col} AS CHAR)) <> ''
              AND l4.{dir_col} IS NOT NULL
              AND TRIM(CAST(l4.{dir_col} AS CHAR)) <> ''
              AND COALESCE(c.cliente_estado,'') <> 'B'
            ORDER BY l4.{rut_col}, l4.{dir_col}
        """ + page_sql, params)

        for i, d in enumerate(clean_rows(cur.fetchall()), start=page_offset + 1):
            rut = clean_text(d.get('cliente_rut'), 30)
            direccion = clean_text(d.get('cliente_direcciones'), 180)
            ciudad = clean_text(d.get('cliente_ciudad'), 80)
            if rut and direccion:
                out.append({
                    'direccion_id': f"L4-{rut}-{i}",
                    'cliente_rut': rut,
                    'direccion': direccion,
                    'comuna': ciudad,
                    'ciudad_codigo': ciudad,
                    'fuente': table_name,
                })
    return json_safe(out)


@app.get('/bootstrap/familias')
def bootstrap_familias():
    with cursor() as (_, cur):
        cur.execute("""
            SELECT
                familia_codigo AS familia_codigo,
                familia_descripcion AS familia_descripcion,
                familia_restaurant AS familia_restaurant
            FROM familias
            WHERE familia_codigo IS NOT NULL
              AND familia_codigo <> ''
              AND familia_codigo NOT IN ('24','29','30')
              AND UPPER(COALESCE(familia_descripcion,'')) <> 'INACTIVOS'
              AND (familia_restaurant IS NULL OR familia_restaurant <> 'I')
            ORDER BY familia_descripcion
        """)
        out = []
        for f in clean_rows(cur.fetchall()):
            codigo = clean_text(f.get('familia_codigo'), 10) or ''
            if codigo:
                out.append({
                    'familia_codigo': codigo,
                    'familia_descripcion': clean_text(f.get('familia_descripcion'), 20) or codigo,
                    'familia_restaurant': clean_text(f.get('familia_restaurant'), 1),
                })
        return json_safe(out)


@app.get('/bootstrap/productos')
def bootstrap_productos(limit: int = 0, offset: int = 0):
    page_limit, page_offset = page_limit_offset(limit, offset)
    page_sql = " LIMIT %s OFFSET %s" if page_limit > 0 else ""
    params = (page_limit, page_offset) if page_limit > 0 else ()
    with cursor() as (_, cur):
        producto_cols = table_columns(cur, 'productos')
        productoslevel2_cols = table_columns(cur, 'productoslevel2')
        if {'producto_codigo', 'bodega_codigo', 'producto_stockbodega'}.issubset(productoslevel2_cols):
            stock_join_sql = "LEFT JOIN productoslevel2 pl2 ON pl2.producto_codigo = p.producto_codigo AND pl2.bodega_codigo = '01'"
            stock_sql = "COALESCE(pl2.producto_stockbodega, 0) AS stock_actual"
            stock_fecha_sql = "NULL AS stock_fecha"
        else:
            stock_join_sql = ""
            stock_col = next((c for c in (
                'stock_actual',
                'producto_stock',
                'producto_existencia',
                'producto_saldo',
                'stock',
                'existencia',
                'saldo',
            ) if c in producto_cols), None)
            stock_fecha_col = next((c for c in (
                'stock_fecha',
                'producto_stock_fecha',
                'fecha_stock',
                'updated_at',
            ) if c in producto_cols), None)
            stock_sql = f"p.{stock_col} AS stock_actual" if stock_col else "0 AS stock_actual"
            stock_fecha_sql = f"p.{stock_fecha_col} AS stock_fecha" if stock_fecha_col else "NULL AS stock_fecha"

        cur.execute(f"""
            SELECT
                p.producto_codigo AS producto_codigo,
                p.producto_descripcion AS producto_descripcion,
                p.familia_codigo AS familia_codigo,
                f.familia_descripcion AS familia_descripcion,
                p.producto_estado AS producto_estado,
                p.producto_unidadenvase AS producto_unidadenvase,
                p.producto_gramaje AS producto_gramaje,
                p.producto_descuento AS producto_descuento,
                p.producto_venta AS producto_venta,
                {stock_sql},
                {stock_fecha_sql}
            FROM productos p
            LEFT JOIN familias f ON f.familia_codigo = p.familia_codigo
            {stock_join_sql}
            WHERE p.producto_codigo IS NOT NULL
              AND p.producto_codigo <> ''
              AND (p.producto_estado IS NULL OR p.producto_estado <> 'I')
              AND (p.familia_codigo IS NULL OR p.familia_codigo NOT IN ('24','29','30'))
              AND (f.familia_descripcion IS NULL OR UPPER(f.familia_descripcion) <> 'INACTIVOS')
              AND (f.familia_codigo IS NULL OR COALESCE(f.familia_restaurant,'') <> 'I')
            ORDER BY p.producto_descripcion
        """ + page_sql, params)
        out = []
        for p in clean_rows(cur.fetchall()):
            codigo = clean_text(p.get('producto_codigo'), 20) or ''
            if not codigo:
                continue
            out.append({
                'producto_codigo': codigo,
                'producto_descripcion': clean_text(p.get('producto_descripcion'), 50) or codigo,
                'familia_codigo': clean_text(p.get('familia_codigo'), 10),
                'familia_descripcion': clean_text(p.get('familia_descripcion'), 20),
                'producto_estado': clean_text(p.get('producto_estado'), 1),
                'producto_unidadenvase': to_float(p.get('producto_unidadenvase')),
                'producto_gramaje': to_float(p.get('producto_gramaje')),
                'producto_descuento': to_float(p.get('producto_descuento')),
                'producto_venta': to_int(p.get('producto_venta'), 0),
                'stock_actual': to_float(p.get('stock_actual'), 0.0),
                'stock_fecha': clean_text(p.get('stock_fecha'), 30),
            })
        return json_safe(out)


@app.get('/bootstrap/productos/{codigo}')
def bootstrap_producto_codigo(codigo: str):
    q = clean_text(codigo, 20) or ''
    if not q:
        return json_safe([])
    with cursor() as (_, cur):
        producto_cols = table_columns(cur, 'productos')
        productoslevel2_cols = table_columns(cur, 'productoslevel2')
        if {'producto_codigo', 'bodega_codigo', 'producto_stockbodega'}.issubset(productoslevel2_cols):
            stock_join_sql = "LEFT JOIN productoslevel2 pl2 ON pl2.producto_codigo = p.producto_codigo AND pl2.bodega_codigo = '01'"
            stock_sql = "COALESCE(pl2.producto_stockbodega, 0) AS stock_actual"
            stock_fecha_sql = "NULL AS stock_fecha"
        else:
            stock_join_sql = ""
            stock_col = next((c for c in ('stock_actual','producto_stock','producto_existencia','producto_saldo','stock','existencia','saldo') if c in producto_cols), None)
            stock_sql = f"p.{stock_col} AS stock_actual" if stock_col else "0 AS stock_actual"
            stock_fecha_sql = "NULL AS stock_fecha"
        params = [q]
        numeric_filter = ""
        if q.isdigit():
            numeric_filter = " OR CAST(p.producto_codigo AS UNSIGNED) = CAST(%s AS UNSIGNED)"
            params.append(q)
        cur.execute(f"""
            SELECT
                p.producto_codigo AS producto_codigo,
                p.producto_descripcion AS producto_descripcion,
                p.familia_codigo AS familia_codigo,
                f.familia_descripcion AS familia_descripcion,
                p.producto_estado AS producto_estado,
                p.producto_unidadenvase AS producto_unidadenvase,
                p.producto_gramaje AS producto_gramaje,
                p.producto_descuento AS producto_descuento,
                p.producto_venta AS producto_venta,
                {stock_sql},
                {stock_fecha_sql}
            FROM productos p
            LEFT JOIN familias f ON f.familia_codigo = p.familia_codigo
            {stock_join_sql}
            WHERE p.producto_codigo IS NOT NULL
              AND p.producto_codigo <> ''
              AND (TRIM(p.producto_codigo) = TRIM(%s){numeric_filter})
              AND (p.producto_estado IS NULL OR p.producto_estado <> 'I')
              AND (p.familia_codigo IS NULL OR p.familia_codigo NOT IN ('24','29','30'))
              AND (f.familia_descripcion IS NULL OR UPPER(f.familia_descripcion) <> 'INACTIVOS')
              AND (f.familia_codigo IS NULL OR COALESCE(f.familia_restaurant,'') <> 'I')
            ORDER BY p.producto_codigo
            LIMIT 20
        """, tuple(params))
        out = []
        for p in clean_rows(cur.fetchall()):
            producto = clean_text(p.get('producto_codigo'), 20) or ''
            if producto:
                out.append({
                    'producto_codigo': producto,
                    'producto_descripcion': clean_text(p.get('producto_descripcion'), 50) or producto,
                    'familia_codigo': clean_text(p.get('familia_codigo'), 10),
                    'familia_descripcion': clean_text(p.get('familia_descripcion'), 20),
                    'producto_estado': clean_text(p.get('producto_estado'), 1),
                    'producto_unidadenvase': to_float(p.get('producto_unidadenvase')),
                    'producto_gramaje': to_float(p.get('producto_gramaje')),
                    'producto_descuento': to_float(p.get('producto_descuento')),
                    'producto_venta': to_int(p.get('producto_venta'), 0),
                    'stock_actual': to_float(p.get('stock_actual'), 0.0),
                    'stock_fecha': clean_text(p.get('stock_fecha'), 30),
                })
        return json_safe(out)


@app.get('/bootstrap/productos-fotos')
def bootstrap_productos_fotos(max_size: int = 140, offset: int = 0, limit: int = 250):
    """Miniaturas de productos para consulta offline.

    No se mezclan con /bootstrap/productos porque las fotos originales pueden
    pesar cerca de 1 MB cada una. Aquí se envía una miniatura base64 pequeña.
    """
    max_size = max(80, min(to_int(max_size, 140), 360))
    offset = max(0, to_int(offset, 0))
    limit = max(1, min(to_int(limit, 250), 500))
    with cursor() as (_, cur):
        producto_cols = table_columns(cur, 'productos')
        if 'producto_foto' not in producto_cols:
            return json_safe([])

        cur.execute("""
            SELECT
                producto_codigo AS producto_codigo,
                producto_foto AS producto_foto
            FROM productos
            WHERE producto_codigo IS NOT NULL
              AND producto_codigo <> ''
              AND producto_foto IS NOT NULL
              AND OCTET_LENGTH(producto_foto) > 0
              AND (producto_estado IS NULL OR producto_estado <> 'I')
            ORDER BY producto_codigo
            LIMIT %s OFFSET %s
        """, (limit, offset))
        out = []
        for row in cur.fetchall():
            codigo = clean_text(row.get('producto_codigo'), 20) or ''
            raw = row.get('producto_foto')
            if not codigo or not raw:
                continue
            foto = image_to_thumbnail_base64(raw, max_size=max_size)
            if foto:
                out.append({'producto_codigo': codigo, 'foto_base64': foto})
        return json_safe(out)


@app.get('/bootstrap/precios')
def bootstrap_precios(limit: int = 0, offset: int = 0):
    page_limit, page_offset = page_limit_offset(limit, offset)
    page_sql = " LIMIT %s OFFSET %s" if page_limit > 0 else ""
    params = (page_limit, page_offset) if page_limit > 0 else ()
    with cursor() as (_, cur):
        cur.execute("""
            SELECT
                producto_codigo AS producto_codigo,
                lista_codigo AS lista_codigo,
                COALESCE(lista_venta, 0) AS lista_venta,
                COALESCE(lista_neto, 0) AS lista_neto,
                COALESCE(lista_ila, 0) AS lista_ila
            FROM precioslevel1
            WHERE producto_codigo IS NOT NULL
              AND producto_codigo <> ''
              AND lista_codigo IS NOT NULL
              AND lista_codigo <> ''
              AND lista_neto IS NOT NULL
            ORDER BY producto_codigo, lista_codigo
        """ + page_sql, params)
        out = []
        for pr in clean_rows(cur.fetchall()):
            producto = clean_text(pr.get('producto_codigo'), 20) or ''
            lista = clean_text(pr.get('lista_codigo'), 5) or ''
            neto = to_int(pr.get('lista_neto'), 0)
            venta = to_int(pr.get('lista_venta'), neto)
            ila = to_int(pr.get('lista_ila'), 0)
            if producto and lista and neto >= 0:
                out.append({'producto_codigo': producto, 'lista_codigo': lista, 'lista_neto': neto, 'lista_venta': venta, 'lista_ila': ila})
        return json_safe(out)


@app.get('/bootstrap/precios/{codigo}')
def bootstrap_precios_producto(codigo: str):
    q = clean_text(codigo, 20) or ''
    if not q:
        return json_safe([])
    params = [q]
    numeric_filter = ""
    if q.isdigit():
        numeric_filter = " OR CAST(producto_codigo AS UNSIGNED) = CAST(%s AS UNSIGNED)"
        params.append(q)
    with cursor() as (_, cur):
        cur.execute(f"""
            SELECT
                producto_codigo AS producto_codigo,
                lista_codigo AS lista_codigo,
                COALESCE(lista_venta, 0) AS lista_venta,
                COALESCE(lista_neto, 0) AS lista_neto,
                COALESCE(lista_ila, 0) AS lista_ila
            FROM precioslevel1
            WHERE producto_codigo IS NOT NULL
              AND producto_codigo <> ''
              AND (TRIM(producto_codigo) = TRIM(%s){numeric_filter})
              AND lista_codigo IS NOT NULL
              AND lista_codigo <> ''
              AND lista_neto IS NOT NULL
            ORDER BY producto_codigo, lista_codigo
            LIMIT 50
        """, tuple(params))
        out = []
        for pr in clean_rows(cur.fetchall()):
            producto = clean_text(pr.get('producto_codigo'), 20) or ''
            lista = clean_text(pr.get('lista_codigo'), 5) or ''
            neto = to_int(pr.get('lista_neto'), 0)
            venta = to_int(pr.get('lista_venta'), neto)
            ila = to_int(pr.get('lista_ila'), 0)
            if producto and lista and neto >= 0:
                out.append({'producto_codigo': producto, 'lista_codigo': lista, 'lista_neto': neto, 'lista_venta': venta, 'lista_ila': ila})
        return json_safe(out)


@app.get('/bootstrap/rutas')
def bootstrap_rutas():
    with cursor() as (_, cur):
        try:
            cur.execute("""
                SELECT ruta_id AS ruta_id, ruta_nombre AS ruta_nombre
                FROM rutas
                ORDER BY ruta_nombre
            """)
            out = []
            for r in clean_rows(cur.fetchall()):
                rid = to_int(r.get('ruta_id'), 0)
                if rid > 0:
                    out.append({'ruta_id': rid, 'ruta_nombre': clean_text(r.get('ruta_nombre'), 20)})
            return json_safe(out)
        except Exception:
            return json_safe([])


@app.get('/bootstrap')
def bootstrap():
    # Compatibilidad. Ya no debe ser usado por Android porque era un payload monolítico muy grande.
    clientes = json.loads(bootstrap_clientes().body.decode('utf-8'))
    productos = json.loads(bootstrap_productos().body.decode('utf-8'))
    familias = json.loads(bootstrap_familias().body.decode('utf-8'))
    rutas = json.loads(bootstrap_rutas().body.decode('utf-8'))
    precios = json.loads(bootstrap_precios().body.decode('utf-8'))
    direcciones = json.loads(bootstrap_direcciones().body.decode('utf-8'))
    return json_safe({
        'clientes': clientes,
        'productos': productos,
        'familias': familias,
        'rutas': rutas,
        'precios': precios,
        'direcciones': direcciones,
        'resumen': {
            'clientes': len(clientes),
            'productos': len(productos),
            'familias': len(familias),
            'rutas': len(rutas),
            'precios': len(precios),
            'direcciones': len(direcciones),
        },
    })


@app.get('/bootstrap/cuenta-corriente')
def bootstrap_cuenta_corriente(limit: int = 0, offset: int = 0, vendedor_codigo: str | None = None):
    """Cartola offline de cuenta corriente de los últimos 6 meses.

    Regla móvil: FE/BO suman deuda; NC/CE descuentan deuda. Se descarga como
    cartola informativa para consulta fuera de la generación de NV.
    """
    page_limit, page_offset = page_limit_offset(limit, offset)
    page_sql = " LIMIT %s OFFSET %s" if page_limit > 0 else ""
    vendedor_codigo = clean_text(vendedor_codigo, 20)
    vendedor_filter = " AND CAST(v.vendedor_codigo AS CHAR) = %s" if vendedor_codigo else ""
    params_list = []
    if vendedor_codigo:
        params_list.append(vendedor_codigo)
    if page_limit > 0:
        params_list.extend([page_limit, page_offset])
    params = tuple(params_list)
    with cursor() as (_, cur):
        cur.execute(f"""
            SELECT
                CAST(v.cliente_rut AS CHAR) AS cliente_rut,
                c.cliente_nombre AS cliente_nombre,
                v.venta_numero AS venta_numero,
                v.venta_tipo AS venta_tipo,
                DATE_FORMAT(v.venta_fecha, '%Y-%m-%d') AS venta_fecha,
                COALESCE(v.venta_totalventa,0) AS venta_totalventa,
                COALESCE(v.venta_pagototal,0) AS venta_pagototal,
                COALESCE(v.venta_folio,0) AS venta_folio,
                COALESCE(v.venta_foliosii,0) AS venta_foliosii,
                COALESCE(v.venta_estadosii,'') AS venta_estadosii,
                CASE
                  WHEN v.venta_tipo IN ('NC','CE')
                    THEN -1 * (COALESCE(v.venta_totalventa,0) - COALESCE(v.venta_pagototal,0))
                  ELSE (COALESCE(v.venta_totalventa,0) - COALESCE(v.venta_pagototal,0))
                END AS saldo
            FROM ventas v
            LEFT JOIN clientes c ON c.cliente_rut = v.cliente_rut
            WHERE v.venta_tipo IN ('FE','FA','BO','CH','NC','CE')
              AND v.venta_fecha >= DATE_SUB(CURDATE(), INTERVAL 6 MONTH)
              AND v.cliente_rut IS NOT NULL
              AND v.cliente_rut <> ''
              AND COALESCE(v.venta_numero,0) > 0
              AND COALESCE(v.venta_folio,0) > 0
              {vendedor_filter}
            ORDER BY c.cliente_nombre, v.cliente_rut, v.venta_fecha DESC, v.venta_numero DESC
        """ + page_sql, params)
        out = []
        for r in clean_rows(cur.fetchall()):
            rut = clean_text(r.get('cliente_rut'), 10)
            saldo = to_int(r.get('saldo'), 0)
            if not rut:
                continue
            out.append({
                'cliente_rut': rut,
                'cliente_nombre': clean_text(r.get('cliente_nombre'), 80) or rut,
                'venta_numero': to_int(r.get('venta_numero'), 0),
                'venta_tipo': clean_text(r.get('venta_tipo'), 2) or '',
                'venta_fecha': clean_text(r.get('venta_fecha'), 10),
                'venta_totalventa': to_int(r.get('venta_totalventa'), 0),
                'venta_pagototal': to_int(r.get('venta_pagototal'), 0),
                'venta_folio': to_int(r.get('venta_folio'), 0),
                'venta_foliosii': to_int(r.get('venta_foliosii'), 0),
                'venta_estadosii': clean_text(r.get('venta_estadosii'), 20) or '',
                'saldo': saldo,
            })
        return json_safe(out)


@app.get('/bootstrap/check')
def bootstrap_check():
    with cursor() as (_, cur):
        result = {}
        for name, sql in {
            'clientes': 'SELECT COUNT(*) n FROM clientes',
            'clientelevel4': 'SELECT COUNT(*) n FROM clientelevel4',
            'productos': 'SELECT COUNT(*) n FROM productos',
            'familias': 'SELECT COUNT(*) n FROM familias',
            'precioslevel1': 'SELECT COUNT(*) n FROM precioslevel1',
            'rutas': 'SELECT COUNT(*) n FROM rutas',
            'secuser': 'SELECT COUNT(*) n FROM secuser',
        }.items():
            try:
                cur.execute(sql)
                result[name] = int(cur.fetchone()['n'])
            except Exception as e:
                result[name] = f'ERROR: {e}'
        return result


def dte_tipo_codigo(tipo: str) -> str:
    t = clean_text(tipo, 8) or ''
    t = t.upper()
    return {
        'FE': '33',
        'FA': '33',
        'FACTURA': '33',
        'BO': '39',
        'BE': '39',
        'NC': '61',
        'CE': '61',
        '33': '33',
        '39': '39',
        '61': '61',
    }.get(t, t)


@app.get('/dte/pdf/{tipo}/{folio}')
def dte_pdf(tipo: str, folio: int, cliente_rut: str | None = None):
    dte_tipo = dte_tipo_codigo(tipo)
    if dte_tipo not in {'33', '39', '61'}:
        raise HTTPException(400, 'Tipo DTE no soportado')
    if folio <= 0:
        raise HTTPException(400, 'Folio invalido')
    rut_num = re.sub(r'\D+', '', clean_text(cliente_rut, 20) or '')
    if len(rut_num) > 1 and '-' in (cliente_rut or ''):
        rut_num = rut_num[:-1]
    folio_pad = f'{folio:09d}'
    candidates = []
    if rut_num:
        candidates.append(DTE_PDF_DIR / f'DTE_{dte_tipo}_{rut_num}_{folio_pad}.pdf')
    candidates.extend(sorted(DTE_PDF_DIR.glob(f'DTE_{dte_tipo}_*_{folio_pad}.pdf')) if DTE_PDF_DIR.exists() else [])
    for path in candidates:
        try:
            resolved = path.resolve()
            if resolved.is_file() and DTE_PDF_DIR.resolve() in resolved.parents:
                return FileResponse(str(resolved), media_type='application/pdf', filename=resolved.name)
        except Exception:
            continue
    raise HTTPException(404, f'PDF DTE no encontrado para tipo {dte_tipo}, folio {folio_pad}')


def table_signature(cur, sql: str) -> str:
    try:
        cur.execute(sql)
        row = clean_row(cur.fetchone() or {})
        return f"{to_int(row.get('n'), 0)}:{to_int(row.get('sig'), 0)}"
    except Exception:
        return "0:0"


@app.get('/bootstrap/manifest')
def bootstrap_manifest():
    """Firmas livianas para evitar descargar tablas que no cambiaron."""
    with cursor() as (_, cur):
        return json_safe({
            'clientes': table_signature(cur, """
                SELECT COUNT(DISTINCT CAST(cliente_rut AS CHAR)) n,
                       COALESCE(SUM(CRC32(CONCAT_WS('|',
                         COALESCE(CAST(cliente_rut AS CHAR),''),
                         COALESCE(cliente_nombre,''),
                         COALESCE(cliente_direccion,''),
                         COALESCE(CAST(Ciudad_codigo AS CHAR),''),
                         COALESCE(Comuna,''),
                         COALESCE(cliente_estado,''),
                         COALESCE(CAST(ruta_id AS CHAR),''),
                         COALESCE(CAST(lista_codigo AS CHAR),''),
                         COALESCE(cliente_geo,''),
                         COALESCE(CAST(cliente_vendedor AS CHAR),'')
                       ))),0) sig
                FROM clientes
                WHERE cliente_rut IS NOT NULL AND cliente_rut <> ''
            """),
            'direcciones': table_signature(cur, """
                SELECT COUNT(*) n,
                       COALESCE(SUM(CRC32(k)),0) sig
                FROM (
                    SELECT DISTINCT CONCAT_WS('|',
                        UPPER(TRIM(COALESCE(CAST(cliente_rut AS CHAR),''))),
                        UPPER(TRIM(COALESCE(CAST(cliente_direcciones AS CHAR),''))),
                        UPPER(TRIM(COALESCE(CAST(cliente_ciudad AS CHAR),''))),
                        UPPER(TRIM(COALESCE(CAST(cliente_ciudad AS CHAR),'')))
                    ) k
                    FROM clienteslevel4
                    WHERE cliente_rut IS NOT NULL AND cliente_rut <> ''
                      AND cliente_direcciones IS NOT NULL AND cliente_direcciones <> ''
                ) x
            """),
            'productos': table_signature(cur, """
                SELECT COUNT(*) n,
                       COALESCE(SUM(CRC32(CONCAT_WS('|',
                         COALESCE(CAST(p.producto_codigo AS CHAR),''),
                         COALESCE(p.producto_descripcion,''),
                         COALESCE(CAST(p.familia_codigo AS CHAR),''),
                         COALESCE(f.familia_descripcion,''),
                         COALESCE(p.producto_estado,''),
                         COALESCE(CAST(p.producto_unidadenvase AS CHAR),''),
                         COALESCE(CAST(p.producto_gramaje AS CHAR),''),
                         COALESCE(CAST(p.producto_descuento AS CHAR),''),
                         COALESCE(CAST(p.producto_venta AS CHAR),''),
                         COALESCE(CAST(pl2.producto_stockbodega AS CHAR),'')
                       ))),0) sig
                FROM productos p
                LEFT JOIN familias f ON f.familia_codigo = p.familia_codigo
                LEFT JOIN productoslevel2 pl2 ON pl2.producto_codigo = p.producto_codigo AND pl2.bodega_codigo = '01'
                WHERE p.producto_codigo IS NOT NULL
                  AND p.producto_codigo <> ''
                  AND (p.producto_estado IS NULL OR p.producto_estado <> 'I')
                  AND (p.familia_codigo IS NULL OR p.familia_codigo NOT IN ('24','29','30'))
                  AND (f.familia_descripcion IS NULL OR UPPER(f.familia_descripcion) <> 'INACTIVOS')
                  AND (f.familia_codigo IS NULL OR COALESCE(f.familia_restaurant,'') <> 'I')
            """),
            'familias': table_signature(cur, """
                SELECT COUNT(*) n,
                       COALESCE(SUM(CRC32(CONCAT_WS('|',
                         COALESCE(CAST(familia_codigo AS CHAR),''),
                         COALESCE(familia_descripcion,''),
                         COALESCE(familia_restaurant,'')
                       ))),0) sig
                FROM familias
                WHERE familia_codigo IS NOT NULL
                  AND familia_codigo <> ''
                  AND familia_codigo NOT IN ('24','29','30')
                  AND UPPER(COALESCE(familia_descripcion,'')) <> 'INACTIVOS'
                  AND (familia_restaurant IS NULL OR familia_restaurant <> 'I')
            """),
            'precios': table_signature(cur, """
                SELECT COUNT(*) n,
                       COALESCE(SUM(CRC32(CONCAT_WS('|',
                         COALESCE(CAST(producto_codigo AS CHAR),''),
                         COALESCE(CAST(lista_codigo AS CHAR),''),
                         COALESCE(CAST(lista_neto AS CHAR),''),
                         COALESCE(CAST(lista_venta AS CHAR),''),
                         COALESCE(CAST(lista_ila AS CHAR),''),
                         COALESCE(CAST(lista_fecha AS CHAR),'')
                       ))),0) sig
                FROM precioslevel1
                WHERE producto_codigo IS NOT NULL
                  AND producto_codigo <> ''
                  AND lista_codigo IS NOT NULL
                  AND lista_codigo <> ''
                  AND lista_neto IS NOT NULL
            """),
            'rutas': table_signature(cur, """
                SELECT COUNT(*) n,
                       COALESCE(SUM(CRC32(CONCAT_WS('|',
                         COALESCE(CAST(ruta_id AS CHAR),''),
                         COALESCE(ruta_nombre,'')
                       ))),0) sig
                FROM rutas
            """),
            'cuenta_corriente': table_signature(cur, """
                SELECT COUNT(*) n,
                       COALESCE(SUM(CRC32(CONCAT_WS('|',
                         COALESCE(CAST(v.cliente_rut AS CHAR),''),
                         COALESCE(CAST(v.venta_numero AS CHAR),''),
                         COALESCE(v.venta_tipo,''),
                         COALESCE(CAST(v.venta_fecha AS CHAR),''),
                         COALESCE(CAST(v.venta_totalventa AS CHAR),''),
                         COALESCE(CAST(v.venta_pagototal AS CHAR),'')
                       ))),0) sig
                FROM ventas v
                WHERE v.venta_tipo IN ('FE','FA','BO','CH','NC','CE')
                  AND v.venta_fecha >= DATE_SUB(CURDATE(), INTERVAL 6 MONTH)
                  AND v.cliente_rut IS NOT NULL
                  AND v.cliente_rut <> ''
            """),
        })


@app.get('/server/time')
def server_time():
    now = server_now_cl()
    return {
        'timezone': SERVER_TZ_NAME,
        'fecha': now.strftime('%Y-%m-%d'),
        'hora': now.strftime('%H:%M:%S'),
        'fecha_hora_cl': now.strftime('%d-%m-%Y %H:%M:%S'),
    }



def ensure_mobile_tables(cur):
    """Tablas técnicas para sincronización móvil idempotente y numeración segura.

    Regla: offline_id identifica una NV móvil de forma única. La API debe aceptar
    reintentos sin crear una segunda venta. nv_sequence evita usar MAX()+1 bajo
    concurrencia, que provoca bloqueos y números duplicados.
    """
    cur.execute("""
        CREATE TABLE IF NOT EXISTS mobile_sync_log (
          offline_id VARCHAR(120) NOT NULL,
          venta_numero BIGINT(20) NULL,
          local_codigo CHAR(10) NOT NULL,
          cliente_rut CHAR(10) NULL,
          estado VARCHAR(20) NOT NULL DEFAULT 'PROCESANDO',
          mensaje VARCHAR(255) NULL,
          request_json MEDIUMTEXT NULL,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
          PRIMARY KEY (offline_id),
          KEY ix_mobile_sync_venta (venta_numero, local_codigo),
          KEY ix_mobile_sync_estado (estado)
        ) ENGINE=InnoDB DEFAULT CHARSET=latin1
    """)
    cur.execute("""
        CREATE TABLE IF NOT EXISTS nv_sequence (
          local_codigo CHAR(10) NOT NULL,
          ultimo_numero BIGINT(20) NOT NULL DEFAULT 0,
          updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
          PRIMARY KEY (local_codigo)
        ) ENGINE=InnoDB DEFAULT CHARSET=latin1
    """)


def init_nv_sequence_if_needed(cur, local_codigo: str):
    cur.execute("""
        INSERT INTO nv_sequence (local_codigo, ultimo_numero)
        SELECT %s, COALESCE(MAX(venta_numero), 0)
        FROM ventas
        WHERE venta_tipo='NV' AND local_codigo=%s
        ON DUPLICATE KEY UPDATE ultimo_numero = ultimo_numero
    """, (local_codigo, local_codigo))


def next_nv_number(cur, local_codigo: str) -> int:
    """Correlativo NV seguro para concurrencia.

    LAST_INSERT_ID(expr) es atómico por conexión en MySQL. Bloquea solo la fila
    de nv_sequence del local, no la tabla ventas completa.
    """
    init_nv_sequence_if_needed(cur, local_codigo)
    cur.execute(
        "UPDATE nv_sequence SET ultimo_numero = LAST_INSERT_ID(ultimo_numero + 1) WHERE local_codigo=%s",
        (local_codigo,),
    )
    cur.execute("SELECT LAST_INSERT_ID() AS n")
    return int(cur.fetchone()['n'])


def payload_for_log(req: NvSyncRequest) -> str:
    try:
        data = req.model_dump() if hasattr(req, 'model_dump') else req.dict()
        return json.dumps(data, ensure_ascii=False, default=str)[:16000000]
    except Exception:
        return None




@app.get('/nv/statuses')
def nv_statuses():
    """Devuelve estado real de NV móviles de los últimos 7 días.

Permite que Android pinte en gris una NV cuando en RISEK ya quedó facturada
(venta_facturado='S') y bloquee su edición.
    """
    with cursor() as (_, cur):
        ensure_mobile_tables(cur)
        cur.execute("""
            SELECT
                m.offline_id AS offline_id,
                m.venta_numero AS venta_numero,
                v.venta_facturado AS venta_facturado,
                DATE_FORMAT(v.venta_fecha, '%Y-%m-%d') AS venta_fecha
            FROM mobile_sync_log m
            LEFT JOIN ventas v
              ON v.venta_numero = m.venta_numero
             AND v.venta_tipo = 'NV'
             AND v.local_codigo = m.local_codigo
            WHERE m.created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
            ORDER BY m.created_at DESC
        """)
        out = []
        for r in clean_rows(cur.fetchall()):
            out.append({
                'offline_id': clean_text(r.get('offline_id'), 120),
                'venta_numero': to_int(r.get('venta_numero'), None) if r.get('venta_numero') is not None else None,
                'venta_facturado': clean_text(r.get('venta_facturado'), 1) or 'N',
                'venta_fecha': clean_text(r.get('venta_fecha'), 10),
            })
        return json_safe(out)



@app.post('/nv/sync', response_model=NvSyncResponse)
def sync_nv(req: NvSyncRequest):
    """Sincroniza una NV móvil de forma transaccional e idempotente.

    Garantía: la API solo responde ok=True cuando la cabecera existe en ventas,
    el detalle existe en ventaslevel2 y mobile_sync_log quedó asociado al mismo
    offline_id. Cualquier falla hace rollback y Android debe dejar la NV en ERROR.
    """
    if not req.offline_id or len(req.offline_id.strip()) < 8:
        raise HTTPException(400, 'offline_id inválido')
    if len(req.lines) == 0:
        raise HTTPException(400, 'NV sin líneas')
    if len(req.lines) > 17:
        raise HTTPException(400, 'NV supera 17 líneas')
    if not req.venta_fechavto:
        raise HTTPException(400, 'Debe indicar fecha de reparto')
    if not req.venta_direccion:
        raise HTTPException(400, 'Debe indicar dirección de reparto')
    if not req.vendedor_codigo:
        raise HTTPException(400, 'Usuario sin vendedor_codigo; no se puede grabar NV')

    try:
        # La fecha de reparto sí viene desde Android porque la elige el vendedor.
        venta_fechavto = datetime.date.fromisoformat(req.venta_fechavto)
    except Exception:
        raise HTTPException(400, 'Fecha de reparto inválida. Use formato YYYY-MM-DD')

    # Fecha/hora de guardado de la NV. Android envía createdAt en epoch ms; la API
    # lo convierte a America/Santiago para que venta_fecha y venta_hora no queden
    # con la hora tardía de sincronización.
    guardado_at = fecha_hora_guardado(req)
    venta_fecha = guardado_at.date()

    local_codigo = '01'
    bodega_codigo = clean_text(getattr(req, 'bodega_codigo', None), 10) or '01'
    offline_id = clean_text(req.offline_id, 120)
    cliente_rut = clean_text(req.cliente_rut, 10)
    vendedor_codigo = clean_text(req.vendedor_codigo, 20)
    venta_direccion = clean_text(req.venta_direccion, 80) or ''
    if not venta_direccion:
        raise HTTPException(400, 'Debe indicar dirección de reparto')

    with cursor() as (cn, cur):
        try:
            cur.execute('SET innodb_lock_wait_timeout = 5')
        except Exception:
            pass

        ensure_mobile_tables(cur)
        update_venta_numero = None

        # Idempotencia real: si ya fue sincronizada, validar que ventas exista antes de responder OK.
        cur.execute('SELECT venta_numero, local_codigo, estado, mensaje FROM mobile_sync_log WHERE offline_id=%s', (offline_id,))
        old = first_or_none(cur)
        if old and clean_text(old.get('estado'), 20) == 'SINCRONIZADO' and old.get('venta_numero'):
            venta_numero_old = int(old.get('venta_numero'))
            old_local_codigo = clean_text(old.get('local_codigo'), 10) or local_codigo
            cur.execute("""
                SELECT COUNT(*) AS n, MAX(COALESCE(venta_facturado,'N')) AS venta_facturado
                FROM ventas
                WHERE venta_numero=%s AND venta_tipo='NV' AND local_codigo=%s
            """, (venta_numero_old, old_local_codigo))
            venta_state = cur.fetchone()
            exists_venta = int(venta_state['n'])
            venta_facturado = clean_text(venta_state.get('venta_facturado'), 1) or 'N'
            cur.execute("""
                SELECT COUNT(*) AS n
                FROM ventaslevel2
                WHERE venta_numero=%s AND venta_tipo='NV' AND local_codigo=%s
            """, (venta_numero_old, old_local_codigo))
            exists_detalle = int(cur.fetchone()['n'])
            if exists_venta > 0:
                if venta_facturado == 'S':
                    raise HTTPException(409, 'NV ya facturada: no se puede modificar')
                update_venta_numero = venta_numero_old
                local_codigo = old_local_codigo
                cur.execute("""
                    UPDATE mobile_sync_log
                    SET estado='PROCESANDO', mensaje='Actualizando NV ya sincronizada', request_json=%s, updated_at=NOW()
                    WHERE offline_id=%s
                """, (payload_for_log(req), offline_id))
            elif exists_venta > 0 and venta_facturado == 'S':
                raise HTTPException(409, 'NV ya facturada: no se puede modificar')
            else:
                # Corrección v22: no devolver 409 cuando el log quedó inconsistente.
                # Se elimina el log huérfano y se reintegra la NV completa en ventas/ventaslevel2.
                cur.execute('DELETE FROM mobile_sync_log WHERE offline_id=%s', (offline_id,))
        elif old:
            # Reintento de un offline_id que quedó PROCESANDO/ERROR: se limpia y se intenta completo nuevamente.
            cur.execute('DELETE FROM mobile_sync_log WHERE offline_id=%s', (offline_id,))

        cur.execute('SELECT cliente_estado, CAST(cliente_vendedor AS CHAR) AS cliente_vendedor FROM clientes WHERE cliente_rut=%s', (cliente_rut,))
        c = first_or_none(cur)
        if c and clean_text(c.get('cliente_estado'), 1) == 'B':
            raise HTTPException(409, 'Cliente bloqueado')
        venta_condicion = clean_text(c.get('cliente_vendedor') if c else None, 20)

        # Validar vendedor contra tabla vendedores. Si no existe, no se graba una NV huérfana.
        try:
            cur.execute('SELECT COUNT(*) AS n FROM vendedores WHERE vendedor_codigo=%s', (vendedor_codigo,))
            if int(cur.fetchone()['n']) == 0:
                raise HTTPException(400, f'vendedor_codigo no existe en vendedores: {vendedor_codigo}')
        except HTTPException:
            raise
        except Exception:
            # Si la tabla no puede validarse por estructura heredada, no se bloquea, pero se graba el código recibido.
            pass

        now = guardado_at
        venta_neto = int(req.venta_neto or sum(int(x.neto_linea or 0) for x in req.lines))
        venta_iva = int(req.venta_iva or round(venta_neto * 0.19))
        venta_ila = int(getattr(req, 'venta_ila', 0) or sum(int(getattr(x, 'ila_linea', 0) or 0) for x in req.lines))
        venta_total = int(req.venta_totalventa or (venta_neto + venta_iva))
        if venta_total <= 0:
            raise HTTPException(400, 'Total inválido')

        # Reservar offline_id dentro de la misma transacción.
        # Si Android reintenta dos veces el mismo envío, NO se debe transformar en HTTP 409.
        try:
            cur.execute(
                """
                INSERT INTO mobile_sync_log
                (offline_id, venta_numero, local_codigo, cliente_rut, estado, mensaje, request_json, created_at, updated_at)
                VALUES (%s, NULL, %s, %s, 'PROCESANDO', 'Recibida por API', %s, NOW(), NOW())
                """,
                (offline_id, local_codigo, cliente_rut, payload_for_log(req)),
            )
        except Exception as e:
            # Carrera de reintento: otro request pudo haber insertado el mismo offline_id.
            # Se reconsulta y, si ya quedó sincronizado, se devuelve OK idempotente.
            if update_venta_numero is not None:
                if 'Duplicate' not in str(e) and '1062' not in str(e):
                    raise
            elif 'Duplicate' in str(e) or '1062' in str(e):
                cur.execute('SELECT venta_numero, local_codigo, estado FROM mobile_sync_log WHERE offline_id=%s', (offline_id,))
                dup = first_or_none(cur)
                if dup and clean_text(dup.get('estado'), 20) == 'SINCRONIZADO' and dup.get('venta_numero'):
                    return NvSyncResponse(ok=True, venta_numero=int(dup.get('venta_numero')), already_synced=True, message='NV ya sincronizada previamente')
                cur.execute('DELETE FROM mobile_sync_log WHERE offline_id=%s', (offline_id,))
                cur.execute(
                    """
                    INSERT INTO mobile_sync_log
                    (offline_id, venta_numero, local_codigo, cliente_rut, estado, mensaje, request_json, created_at, updated_at)
                    VALUES (%s, NULL, %s, %s, 'PROCESANDO', 'Reintento recibido por API', %s, NOW(), NOW())
                    """,
                    (offline_id, local_codigo, cliente_rut, payload_for_log(req)),
                )
            else:
                raise

        venta_numero = update_venta_numero
        if venta_numero is not None:
            cur.execute("""
                SELECT COALESCE(venta_facturado,'N') AS venta_facturado
                FROM ventas
                WHERE venta_numero=%s AND venta_tipo='NV' AND local_codigo=%s
            """, (venta_numero, local_codigo))
            current = first_or_none(cur)
            if not current:
                raise HTTPException(409, 'NV sincronizada no existe en servidor para actualizar')
            if clean_text(current.get('venta_facturado'), 1) == 'S':
                raise HTTPException(409, 'NV ya facturada: no se puede modificar')
            cur.execute("""
                DELETE FROM ventaslevel2
                WHERE venta_numero=%s AND venta_tipo='NV' AND local_codigo=%s
            """, (venta_numero, local_codigo))
            cur.execute("""
                UPDATE ventas
                SET venta_fecha=%s, cliente_rut=%s, venta_mes=%s, venta_ano=%s, venta_estado='A', vendedor_codigo=%s,
                    venta_totalventa=%s, venta_hora=%s, venta_fechavto=%s, venta_direccion=%s, venta_tipoemision='F',
                    venta_pagototal=0, venta_totalchequesprot=0, venta_neto1=%s, venta_iva1=%s, venta_ila1=%s,
                    venta_condicion=%s, venta_observacion01=%s
                WHERE venta_numero=%s AND venta_tipo='NV' AND local_codigo=%s AND COALESCE(venta_facturado,'N') <> 'S'
            """, (
                venta_fecha, cliente_rut, venta_fecha.month, venta_fecha.year, vendedor_codigo,
                venta_total, now, venta_fechavto, venta_direccion[:50],
                venta_neto, venta_iva, venta_ila, venta_condicion, clean_text(req.venta_observacion01, 200),
                venta_numero, local_codigo,
            ))
        else:
            last_insert_error = None
            for _ in range(5):
                venta_numero = next_nv_number(cur, local_codigo)
                try:
                    cur.execute("""
                        INSERT INTO ventas
                        (venta_numero, venta_tipo, local_codigo, venta_fecha, cliente_rut, venta_mes, venta_ano, venta_estado, vendedor_codigo,
                         venta_totalventa, venta_hora, venta_fechavto, venta_direccion, venta_tipoemision, venta_facturado, venta_pagototal,
                         venta_totalchequesprot, venta_neto1, venta_iva1, venta_ila1, venta_condicion, venta_observacion01)
                        VALUES (%s,'NV',%s,%s,%s,%s,%s,'A',%s,%s,%s,%s,%s,'F','N',0,0,%s,%s,%s,%s,%s)
                    """, (
                        venta_numero, local_codigo, venta_fecha, cliente_rut, venta_fecha.month, venta_fecha.year,
                        vendedor_codigo, venta_total, now, venta_fechavto, venta_direccion[:50], venta_neto, venta_iva, venta_ila, venta_condicion, clean_text(req.venta_observacion01, 200),
                    ))
                    last_insert_error = None
                    break
                except Exception as e:
                    last_insert_error = e
                    if 'Duplicate' not in str(e) and '1062' not in str(e):
                        raise
                    continue
            if last_insert_error is not None:
                raise last_insert_error

        detalle_insertado = 0
        for idx, line in enumerate(req.lines, start=1):
            producto_codigo = clean_text(line.producto_codigo, 20)
            descripcion = clean_text(line.descripcion, 50) or producto_codigo
            neto_linea = int(line.neto_linea or 0)
            iva_linea = int(line.iva_linea or round(neto_linea * 0.19))
            ila_linea = int(getattr(line, 'ila_linea', 0) or 0)
            total_linea = int(line.total_linea or (neto_linea + iva_linea))
            venta_cantidad = float(line.cantidad or 0)
            venta_unidadenvase = float(line.uxe or 0)
            venta_precio_neto = precio_unitario_neto(line.precio, total_linea, venta_unidadenvase, line.descuento)
            cur.execute("SELECT producto_gramaje FROM productos WHERE producto_codigo=%s LIMIT 1", (producto_codigo,))
            producto_row = first_or_none(cur)
            producto_gramaje = to_float(producto_row.get('producto_gramaje') if producto_row else None, 0.0) or 0.0
            venta_kilos = venta_cantidad * (venta_unidadenvase * producto_gramaje) if producto_gramaje > 0 else venta_cantidad
            cur.execute("""
                INSERT INTO ventaslevel2
                (venta_numero, venta_tipo, local_codigo, producto_codigo, bodega_codigo, venta_lineaneto, venta_lineaiva, venta_lineaila, venta_precio,
                 venta_cantidad, venta_descuentol, venta_unidadenvase, venta_kilos, venta_descripcion, venta_totalneto, venta_precioventa)
                VALUES (%s,'NV',%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
            """, (
                venta_numero, local_codigo, producto_codigo, clean_text(getattr(line, 'bodega_codigo', None), 10) or bodega_codigo,
                neto_linea, iva_linea, ila_linea, venta_precio_neto,
                venta_cantidad, float(line.descuento or 0), venta_unidadenvase, venta_kilos, descripcion,
                neto_linea, total_linea,
            ))
            detalle_insertado += 1

        # Verificación obligatoria antes de responder OK al móvil.
        cur.execute("""
            SELECT COUNT(*) AS n
            FROM ventas
            WHERE venta_numero=%s AND venta_tipo='NV' AND local_codigo=%s
        """, (venta_numero, local_codigo))
        cab_count = int(cur.fetchone()['n'])
        cur.execute("""
            SELECT COUNT(*) AS n
            FROM ventaslevel2
            WHERE venta_numero=%s AND venta_tipo='NV' AND local_codigo=%s
        """, (venta_numero, local_codigo))
        det_count = int(cur.fetchone()['n'])
        if cab_count != 1 or det_count != detalle_insertado or det_count <= 0:
            raise HTTPException(500, f'NV incompleta: cabecera={cab_count}, detalle={det_count}, esperado={detalle_insertado}')

        cur.execute(
            """
            UPDATE mobile_sync_log
            SET venta_numero=%s, estado='SINCRONIZADO', mensaje=%s, updated_at=NOW()
            WHERE offline_id=%s
            """,
            (venta_numero, f'NV sincronizada correctamente. Total CLP ${venta_total:,.0f}'.replace(',', '.'), offline_id),
        )
        # Commit explícito antes de responder al móvil. El context manager hará un commit adicional inocuo.
        cn.commit()
        return NvSyncResponse(ok=True, venta_numero=venta_numero, already_synced=False, message=f'NV {venta_numero} integrada en ventas/ventaslevel2 con bodega {bodega_codigo}')


@app.post('/nv/delete', response_model=NvDeleteResponse)
def delete_nv(req: NvDeleteRequest):
    """Elimina una NV ya sincronizada, solo si todavía no está facturada.

    Es idempotente: si ya no existe en ventas/ventaslevel2, responde OK para que
    Android pueda limpiar la cola local. Nunca elimina si venta_facturado='S'.
    """
    offline_id = clean_text(req.offline_id, 120)
    if not offline_id:
        raise HTTPException(400, 'offline_id inválido')
    local_codigo = '01'
    with cursor() as (cn, cur):
        ensure_mobile_tables(cur)
        venta_numero = req.venta_numero
        if not venta_numero:
            cur.execute('SELECT venta_numero, local_codigo FROM mobile_sync_log WHERE offline_id=%s', (offline_id,))
            row = first_or_none(cur)
            if row:
                venta_numero = to_int(row.get('venta_numero'), None)
                local_codigo = clean_text(row.get('local_codigo'), 10) or '01'
        if not venta_numero:
            # No hay rastro servidor. Para una cola offline, esto es eliminación ya resuelta.
            return NvDeleteResponse(ok=True, venta_numero=None, message='NV no existe en servidor; eliminación local permitida')

        cur.execute("""
            SELECT COALESCE(venta_facturado,'N') AS venta_facturado
            FROM ventas
            WHERE venta_numero=%s AND venta_tipo='NV' AND local_codigo=%s
            LIMIT 1
        """, (venta_numero, local_codigo))
        row = first_or_none(cur)
        if not row:
            cur.execute("UPDATE mobile_sync_log SET estado='ELIMINADO', mensaje='NV no existía en ventas', updated_at=NOW() WHERE offline_id=%s", (offline_id,))
            cn.commit()
            return NvDeleteResponse(ok=True, venta_numero=venta_numero, message='NV ya no existe en servidor')
        if clean_text(row.get('venta_facturado'), 1) == 'S':
            raise HTTPException(409, 'NV facturada: no se puede eliminar')

        cur.execute("DELETE FROM ventaslevel2 WHERE venta_numero=%s AND venta_tipo='NV' AND local_codigo=%s", (venta_numero, local_codigo))
        cur.execute("DELETE FROM ventas WHERE venta_numero=%s AND venta_tipo='NV' AND local_codigo=%s AND COALESCE(venta_facturado,'N') <> 'S'", (venta_numero, local_codigo))
        if cur.rowcount != 1:
            raise HTTPException(409, 'No se eliminó cabecera; revise si fue facturada por otro proceso')
        cur.execute("UPDATE mobile_sync_log SET estado='ELIMINADO', mensaje='NV eliminada desde móvil', updated_at=NOW() WHERE offline_id=%s", (offline_id,))
        cn.commit()
        return NvDeleteResponse(ok=True, venta_numero=venta_numero, message='NV eliminada de ventas y ventaslevel2')


@app.get('/nv/sync/status/{offline_id}')
def nv_sync_status(offline_id: str):
    with cursor() as (_, cur):
        ensure_mobile_tables(cur)
        cur.execute('SELECT offline_id, venta_numero, local_codigo, cliente_rut, estado, mensaje, created_at, updated_at FROM mobile_sync_log WHERE offline_id=%s', (offline_id,))
        row = first_or_none(cur)
        if not row:
            raise HTTPException(404, 'offline_id no existe en servidor')
        return clean_row(row)


@app.post('/ventas/resumen-dia-email')
def ventas_resumen_dia_email(req: ResumenVentasEmailRequest):
    email = clean_text(req.email, 120)
    vendedor_codigo = clean_text(req.vendedor_codigo, 20)
    if not email or '@' not in email or '.' not in email:
        raise HTTPException(400, 'Debe indicar un correo valido')
    if not vendedor_codigo:
        raise HTTPException(400, 'No se pudo identificar el vendedor logueado para generar el reporte')
    today = server_now_cl().date()
    report_month = to_int(req.mes, today.month)
    report_year = to_int(req.ano, today.year)
    if report_month < 1 or report_month > 12:
        raise HTTPException(400, 'Mes invalido')
    if report_year < 2018 or report_year > today.year:
        raise HTTPException(400, 'Ano invalido')
    month_start = datetime.date(report_year, report_month, 1)
    next_month = (month_start.replace(day=28) + datetime.timedelta(days=4)).replace(day=1)
    month_names = ['Enero', 'Febrero', 'Marzo', 'Abril', 'Mayo', 'Junio', 'Julio', 'Agosto', 'Septiembre', 'Octubre', 'Noviembre', 'Diciembre']
    mes_label = f'{month_names[report_month - 1]} {report_year}'
    with cursor() as (_, cur):
        vendedor_nombre = vendedor_codigo
        vendedor_cols = table_columns(cur, 'vendedores')
        vendedor_name_col = next((c for c in ('vendedor_nombre', 'vendedor_descripcion', 'vendedor_name', 'nombre') if c in vendedor_cols), None)
        if vendedor_codigo and vendedor_name_col:
            cur.execute(f"""
                SELECT COALESCE({vendedor_name_col}, CAST(vendedor_codigo AS CHAR)) AS nombre
                FROM vendedores
                WHERE CAST(vendedor_codigo AS CHAR)=%s
                LIMIT 1
            """, (vendedor_codigo,))
            vendedor_nombre = clean_text((clean_row(cur.fetchone() or {})).get('nombre'), 80) or vendedor_codigo

        if vendedor_codigo:
            vendedor_filter = ' AND CAST(v.vendedor_codigo AS CHAR)=%s'
        else:
            vendedor_filter = ''

        def fetch_summary(start_date: datetime.date, end_date: datetime.date) -> dict:
            params = [start_date, end_date]
            if vendedor_codigo:
                params.append(vendedor_codigo)
            cur.execute(f"""
                SELECT
                    SUM(CASE WHEN v.venta_tipo='FE' THEN 1 ELSE 0 END) AS facturas,
                    SUM(CASE WHEN v.venta_tipo='BO' THEN 1 ELSE 0 END) AS boletas,
                    SUM(CASE WHEN v.venta_tipo='CE' THEN 1 ELSE 0 END) AS notas_credito,
                    COUNT(*) AS documentos,
                    COUNT(DISTINCT CASE WHEN v.venta_tipo IN ('FE','BO') THEN v.cliente_rut END) AS clientes,
                    COALESCE(SUM(CASE WHEN v.venta_tipo='FE' THEN COALESCE(v.venta_totalventa,0) ELSE 0 END),0) AS venta_facturas,
                    COALESCE(SUM(CASE WHEN v.venta_tipo='BO' THEN COALESCE(v.venta_totalventa,0) ELSE 0 END),0) AS venta_boletas,
                    COALESCE(SUM(CASE WHEN v.venta_tipo='CE' THEN -1 * COALESCE(v.venta_totalventa,0) ELSE 0 END),0) AS total_nc,
                    COALESCE(SUM(
                        COALESCE(v.venta_pagototal,0)
                    ),0) AS venta_pagado,
                    COALESCE(SUM(
                        CASE
                          WHEN v.venta_tipo='CE' THEN -1 * COALESCE(v.venta_totalventa,0)
                          WHEN v.venta_tipo IN ('FE','BO') THEN COALESCE(v.venta_totalventa,0)
                          ELSE 0
                        END
                    ),0) AS venta_total,
                    COALESCE(SUM(
                        COALESCE(v.venta_neto_03,0)
                    ),0) AS venta_neta
                FROM ventas v
                WHERE v.venta_tipo IN ('FE','CE','BO')
                  AND v.venta_fecha >= %s
                  AND v.venta_fecha < %s
                  {vendedor_filter}
            """, tuple(params))
            return clean_row(cur.fetchone() or {})

        summary = fetch_summary(month_start, next_month)

        params = [month_start, next_month]
        if vendedor_codigo:
            params.append(vendedor_codigo)
        cur.execute(f"""
            SELECT
                DATE_FORMAT(v.venta_fecha, '%Y-%m-%d') AS venta_fecha,
                COUNT(*) AS documentos,
                COALESCE(SUM(
                    CASE
                      WHEN v.venta_tipo='CE' THEN -1 * COALESCE(v.venta_totalventa,0)
                      WHEN v.venta_tipo IN ('FE','BO') THEN COALESCE(v.venta_totalventa,0)
                      ELSE 0
                    END
                ),0) AS venta_total,
                COALESCE(SUM(
                    COALESCE(v.venta_neto_03,0)
                ),0) AS venta_neta,
                COALESCE(SUM(
                    COALESCE(v.venta_pagototal,0)
                ),0) AS venta_pagado
            FROM ventas v
            WHERE v.venta_tipo IN ('FE','CE','BO')
              AND v.venta_fecha >= %s
              AND v.venta_fecha < %s
              {vendedor_filter}
            GROUP BY DATE(v.venta_fecha)
            ORDER BY DATE(v.venta_fecha)
        """, tuple(params))
        daily_rows = clean_rows(cur.fetchall())
        daily_by_date = {clean_text(r.get('venta_fecha'), 10): r for r in daily_rows}
        daily = []
        cursor_day = month_start
        report_last_day = min(today, next_month - datetime.timedelta(days=1)) if month_start <= today else month_start - datetime.timedelta(days=1)
        while cursor_day <= report_last_day:
            key = cursor_day.isoformat()
            row = daily_by_date.get(key, {})
            daily.append({
                'fecha': key,
                'documentos': to_int(row.get('documentos'), 0),
                'venta_total': to_int(row.get('venta_total'), 0),
                'venta_neta': to_int(row.get('venta_neta'), 0),
                'venta_pagado': to_int(row.get('venta_pagado'), 0),
            })
            cursor_day += datetime.timedelta(days=1)

        if to_int(summary.get('documentos'), 0) <= 0 and not daily_rows:
            raise HTTPException(404, 'No existen documentos de venta del mes para enviar')

    title = f"Reporte comercial vendedor {mes_label}"
    pdf = reporte_vendedor_mensual_pdf_bytes(
        title=title,
        vendedor=vendedor_nombre,
        fecha=today.strftime('%d-%m-%Y'),
        mes_label=mes_label,
        summary=summary,
        daily=daily,
    )
    send_pdf_email(
        to_email=email,
        subject=f"Reporte comercial RISEK {mes_label}",
        body=(
            f"Estimado vendedor,\n\n"
            f"Adjunto reporte comercial RISEK del mes {mes_label}.\n"
            f"El informe incluye FE y BO; CE se descuenta. "
            f"Presenta resumen diario con Neto, Total y Pagado.\n\n"
            f"Vendedor: {vendedor_nombre}\n"
            f"Neto: {money_clp(summary.get('venta_neta'))}\n"
            f"CE descontadas: {money_clp(summary.get('total_nc'))}\n"
            f"Pagado: {money_clp(summary.get('venta_pagado'))}\n"
            f"Total final: {money_clp(summary.get('venta_total'))}\n\n"
            f"Valores expresados en pesos chilenos."
        ),
        filename=f"reporte_vendedor_risek_{report_year}{report_month:02d}.pdf",
        pdf=pdf,
    )
    return json_safe({
        'ok': True,
        'message': f'Reporte comercial enviado a {email}',
        'documentos': to_int(summary.get('documentos'), 0),
        'total': to_int(summary.get('venta_total'), 0),
    })


@app.get('/clientes/{rut}/ultimas-nv')
def ultimas_nv_cliente(rut: str):
    cliente_rut = clean_text(rut, 10)
    with cursor() as (_, cur):
        cur.execute("""
            SELECT
                venta_numero,
                DATE_FORMAT(venta_fecha, '%Y-%m-%d') AS venta_fecha,
                DATE_FORMAT(venta_fechavto, '%Y-%m-%d') AS venta_fechavto,
                COALESCE(venta_facturado,'N') AS venta_facturado,
                COALESCE(venta_totalventa,0) AS venta_totalventa,
                COALESCE(venta_neto1,0) AS venta_neto1,
                COALESCE(venta_iva1,0) AS venta_iva1,
                venta_observacion01
            FROM ventas
            WHERE cliente_rut=%s
              AND venta_tipo='NV'
              AND venta_fecha >= DATE_SUB(CURDATE(), INTERVAL 7 DAY)
            ORDER BY venta_fecha DESC, venta_numero DESC
            LIMIT 20
        """, (cliente_rut,))
        return json_safe(clean_rows(cur.fetchall()))


@app.get('/supervisor/dashboard')
def supervisor_dashboard():
    now = time.time()
    cached = _SUPERVISOR_DASHBOARD_CACHE.get('data')
    if cached is not None and now - float(_SUPERVISOR_DASHBOARD_CACHE.get('ts') or 0) < _SUPERVISOR_DASHBOARD_CACHE_SECONDS:
        return json_safe(cached)

    with cursor() as (_, cur):
        vendedor_cols = table_columns(cur, 'vendedores')
        vendedor_name_col = None
        for c in ('vendedor_nombre', 'vendedor_descripcion', 'vendedor_name', 'nombre'):
            if c in vendedor_cols:
                vendedor_name_col = c
                break
        vendedor_name_expr = f"COALESCE(vend.{vendedor_name_col}, CAST(v.vendedor_codigo AS CHAR))" if vendedor_name_col else "CAST(v.vendedor_codigo AS CHAR)"

        cur.execute("""
            SELECT
                SUM(CASE WHEN venta_tipo IN ('FE','FA') AND venta_fecha >= CURDATE() AND venta_fecha < CURDATE() + INTERVAL 1 DAY THEN 1 ELSE 0 END) AS facturas_hoy,
                SUM(CASE WHEN venta_fecha >= CURDATE() AND venta_fecha < CURDATE() + INTERVAL 1 DAY THEN 1 ELSE 0 END) AS documentos_hoy,
                SUM(CASE WHEN venta_tipo IN ('NC','CE') AND venta_fecha >= CURDATE() AND venta_fecha < CURDATE() + INTERVAL 1 DAY THEN 1 ELSE 0 END) AS nc_hoy,
                COALESCE(SUM(CASE WHEN venta_tipo IN ('FE','FA') AND venta_fecha >= CURDATE() AND venta_fecha < CURDATE() + INTERVAL 1 DAY THEN COALESCE(venta_totalventa,0) ELSE 0 END),0) AS venta_total,
                COUNT(DISTINCT CASE WHEN venta_fecha >= CURDATE() AND venta_fecha < CURDATE() + INTERVAL 1 DAY THEN cliente_rut END) AS clientes,
                SUM(CASE WHEN venta_tipo IN ('FE','FA') THEN 1 ELSE 0 END) AS facturas_30,
                COUNT(*) AS documentos_30,
                SUM(CASE WHEN venta_tipo IN ('BO') THEN 1 ELSE 0 END) AS boletas_30,
                SUM(CASE WHEN venta_tipo IN ('NC','CE') THEN 1 ELSE 0 END) AS nc_30,
                COALESCE(SUM(CASE WHEN venta_tipo IN ('FE','FA') THEN COALESCE(venta_totalventa,0) ELSE 0 END),0) AS venta_30,
                COUNT(DISTINCT cliente_rut) AS clientes_30,
                0 AS pendientes
            FROM ventas
            WHERE venta_tipo IN ('FE','FA','BO','NC','CE')
              AND venta_fecha >= DATE_FORMAT(CURDATE(), '%Y-%m-01')
        """)
        summary = clean_row(cur.fetchone() or {})

        cur.execute(f"""
            SELECT
                COALESCE(CAST(v.vendedor_codigo AS CHAR), '-') AS vendedor_codigo,
                {vendedor_name_expr} AS vendedor_nombre,
                SUM(CASE WHEN v.venta_tipo IN ('FE','FA') THEN 1 ELSE 0 END) AS facturas,
                COUNT(*) AS documentos,
                COALESCE(SUM(
                    CASE
                      WHEN v.venta_tipo IN ('NC','CE') THEN -1 * COALESCE(v.venta_totalventa,0)
                      ELSE COALESCE(v.venta_totalventa,0)
                    END
                ),0) AS total,
                COUNT(DISTINCT v.cliente_rut) AS clientes
            FROM ventas v
            LEFT JOIN vendedores vend ON CAST(vend.vendedor_codigo AS CHAR)=CAST(v.vendedor_codigo AS CHAR)
            WHERE v.venta_tipo IN ('FE','FA','BO','NC','CE')
              AND v.venta_fecha >= DATE_FORMAT(CURDATE(), '%Y-%m-01')
            GROUP BY COALESCE(CAST(v.vendedor_codigo AS CHAR), '-'), {vendedor_name_expr}
            ORDER BY total DESC
            LIMIT 12
        """)
        vendedores = clean_rows(cur.fetchall())

        cur.execute("""
            SELECT
                COALESCE(CAST(c.ruta_id AS CHAR), '-') AS ruta_id,
                COALESCE(r.ruta_nombre, CONCAT('Ruta ', COALESCE(CAST(c.ruta_id AS CHAR), '-'))) AS ruta_nombre,
                SUM(CASE WHEN v.venta_tipo IN ('FE','FA') THEN 1 ELSE 0 END) AS facturas,
                COUNT(*) AS documentos,
                COALESCE(SUM(
                    CASE
                      WHEN v.venta_tipo IN ('NC','CE') THEN -1 * COALESCE(v.venta_totalventa,0)
                      ELSE COALESCE(v.venta_totalventa,0)
                    END
                ),0) AS venta,
                COUNT(DISTINCT v.cliente_rut) AS clientes
            FROM ventas v
            LEFT JOIN clientes c ON c.cliente_rut = v.cliente_rut
            LEFT JOIN rutas r ON r.ruta_id = c.ruta_id
            WHERE v.venta_tipo IN ('FE','FA','BO','NC','CE')
              AND v.venta_fecha >= DATE_FORMAT(CURDATE(), '%Y-%m-01')
            GROUP BY COALESCE(CAST(c.ruta_id AS CHAR), '-'), COALESCE(r.ruta_nombre, CONCAT('Ruta ', COALESCE(CAST(c.ruta_id AS CHAR), '-')))
            ORDER BY venta DESC
            LIMIT 12
        """)
        rutas = clean_rows(cur.fetchall())

        cur.execute("""
            SELECT
                DATE_FORMAT(d.dt, '%Y-%m-%d') AS fecha,
                COALESCE(SUM(CASE WHEN v.venta_tipo IN ('FE','FA') THEN COALESCE(v.venta_totalventa,0) ELSE 0 END),0) AS total
            FROM (
                SELECT CURDATE() - INTERVAL 6 DAY AS dt UNION ALL
                SELECT CURDATE() - INTERVAL 5 DAY UNION ALL
                SELECT CURDATE() - INTERVAL 4 DAY UNION ALL
                SELECT CURDATE() - INTERVAL 3 DAY UNION ALL
                SELECT CURDATE() - INTERVAL 2 DAY UNION ALL
                SELECT CURDATE() - INTERVAL 1 DAY UNION ALL
                SELECT CURDATE()
            ) d
            LEFT JOIN ventas v ON v.venta_tipo IN ('FE','FA','BO','NC','CE')
             AND v.venta_fecha >= d.dt
             AND v.venta_fecha < d.dt + INTERVAL 1 DAY
            GROUP BY d.dt
            ORDER BY d.dt
        """)
        trend = clean_rows(cur.fetchall())

        productos = []
        familias_top = []

        result = {
            'summary': {
                'nv_hoy': to_int(summary.get('facturas_hoy'), 0),
                'facturas_hoy': to_int(summary.get('facturas_hoy'), 0),
                'documentos_hoy': to_int(summary.get('documentos_hoy'), 0),
                'nc_hoy': to_int(summary.get('nc_hoy'), 0),
                'venta_total': to_int(summary.get('venta_total'), 0),
                'clientes': to_int(summary.get('clientes'), 0),
                'facturas_30': to_int(summary.get('facturas_30'), 0),
                'documentos_30': to_int(summary.get('documentos_30'), 0),
                'boletas_30': to_int(summary.get('boletas_30'), 0),
                'nc_30': to_int(summary.get('nc_30'), 0),
                'venta_30': to_int(summary.get('venta_30'), 0),
                'clientes_30': to_int(summary.get('clientes_30'), 0),
                'ticket_promedio': to_int(to_int(summary.get('venta_30'), 0) / max(to_int(summary.get('facturas_30'), 0), 1), 0),
                'pendientes': to_int(summary.get('pendientes'), 0),
            },
            'vendedores': [
                {
                    'vendedor_codigo': clean_text(r.get('vendedor_codigo'), 20) or '-',
                    'vendedor_nombre': clean_text(r.get('vendedor_nombre'), 80) or clean_text(r.get('vendedor_codigo'), 20) or '-',
                    'nv': to_int(r.get('facturas'), 0),
                    'facturas': to_int(r.get('facturas'), 0),
                    'documentos': to_int(r.get('documentos'), 0),
                    'total': to_int(r.get('total'), 0),
                    'clientes': to_int(r.get('clientes'), 0),
                }
                for r in vendedores
            ],
            'rutas': [
                {
                    'ruta_id': clean_text(r.get('ruta_id'), 20) or '-',
                    'ruta_nombre': clean_text(r.get('ruta_nombre'), 80) or '-',
                    'nv': to_int(r.get('facturas'), 0),
                    'facturas': to_int(r.get('facturas'), 0),
                    'documentos': to_int(r.get('documentos'), 0),
                    'venta': to_int(r.get('venta'), 0),
                    'clientes': to_int(r.get('clientes'), 0),
                }
                for r in rutas
            ],
            'trend': [
                {'fecha': clean_text(r.get('fecha'), 10), 'total': to_int(r.get('total'), 0)}
                for r in trend
            ],
            'productos': [
                {
                    'producto_codigo': clean_text(r.get('producto_codigo'), 20) or '-',
                    'producto_descripcion': clean_text(r.get('producto_descripcion'), 80) or '-',
                    'familia_codigo': clean_text(r.get('familia_codigo'), 20) or '-',
                    'familia_descripcion': clean_text(r.get('familia_descripcion'), 80) or '-',
                    'uxe_total': to_float(r.get('uxe_total'), 0) or 0,
                    'total': to_int(r.get('total'), 0),
                }
                for r in productos
            ],
            'familias': [
                {
                    'familia_codigo': clean_text(r.get('familia_codigo'), 20) or '-',
                    'familia_descripcion': clean_text(r.get('familia_descripcion'), 80) or '-',
                    'uxe_total': to_float(r.get('uxe_total'), 0) or 0,
                    'total': to_int(r.get('total'), 0),
                }
                for r in familias_top
            ],
        }
        _SUPERVISOR_DASHBOARD_CACHE['ts'] = time.time()
        _SUPERVISOR_DASHBOARD_CACHE['data'] = result
        return json_safe(result)

@app.get('/supervisor/vendedores/{vendedor_codigo}/resumen')
def supervisor_vendedor_resumen(vendedor_codigo: str):
    codigo = clean_text(vendedor_codigo, 20)
    with cursor() as (_, cur):
        vendedor_cols = table_columns(cur, 'vendedores')
        vendedor_name_col = None
        for c in ('vendedor_nombre', 'vendedor_descripcion', 'vendedor_name', 'nombre'):
            if c in vendedor_cols:
                vendedor_name_col = c
                break
        vendedor_nombre = codigo
        if vendedor_name_col:
            cur.execute(f"""
                SELECT COALESCE({vendedor_name_col}, CAST(vendedor_codigo AS CHAR)) AS nombre
                FROM vendedores
                WHERE CAST(vendedor_codigo AS CHAR)=%s
                LIMIT 1
            """, (codigo,))
            vendedor_nombre = clean_text((clean_row(cur.fetchone() or {})).get('nombre'), 80) or codigo

        cur.execute("""
            SELECT
                SUM(CASE WHEN venta_tipo IN ('FE','FA') THEN 1 ELSE 0 END) AS facturas,
                SUM(CASE WHEN venta_tipo IN ('BO') THEN 1 ELSE 0 END) AS boletas,
                SUM(CASE WHEN venta_tipo IN ('NC','CE') THEN 1 ELSE 0 END) AS notas_credito,
                COUNT(*) AS documentos,
                COUNT(DISTINCT cliente_rut) AS clientes,
                COALESCE(SUM(CASE WHEN venta_tipo IN ('FE','FA') THEN COALESCE(venta_totalventa,0) ELSE 0 END),0) AS venta_facturas,
                COALESCE(SUM(CASE WHEN venta_tipo IN ('BO') THEN COALESCE(venta_totalventa,0) ELSE 0 END),0) AS venta_boletas,
                COALESCE(SUM(CASE WHEN venta_tipo IN ('NC','CE') THEN COALESCE(venta_totalventa,0) ELSE 0 END),0) AS total_nc
            FROM ventas
            WHERE CAST(vendedor_codigo AS CHAR)=%s
              AND venta_tipo IN ('FE','FA','BO','NC','CE')
              AND venta_fecha >= DATE_SUB(CURDATE(), INTERVAL 30 DAY)
        """, (codigo,))
        summary = clean_row(cur.fetchone() or {})

        cur.execute("""
            SELECT
                DATE_FORMAT(d.dt, '%Y-%m-%d') AS fecha,
                COALESCE(SUM(CASE WHEN v.venta_tipo IN ('FE','FA','BO') THEN COALESCE(v.venta_totalventa,0) ELSE 0 END),0) AS total
            FROM (
                SELECT CURDATE() - INTERVAL 6 DAY AS dt UNION ALL
                SELECT CURDATE() - INTERVAL 5 DAY UNION ALL
                SELECT CURDATE() - INTERVAL 4 DAY UNION ALL
                SELECT CURDATE() - INTERVAL 3 DAY UNION ALL
                SELECT CURDATE() - INTERVAL 2 DAY UNION ALL
                SELECT CURDATE() - INTERVAL 1 DAY UNION ALL
                SELECT CURDATE()
            ) d
            LEFT JOIN ventas v ON CAST(v.vendedor_codigo AS CHAR)=%s
             AND v.venta_tipo IN ('FE','FA','BO','NC','CE')
             AND DATE(v.venta_fecha)=d.dt
            GROUP BY d.dt
            ORDER BY d.dt
        """, (codigo,))
        trend = clean_rows(cur.fetchall())

        cur.execute("""
            SELECT
                v.cliente_rut,
                COALESCE(c.cliente_nombre, v.cliente_rut) AS cliente_nombre,
                COUNT(*) AS documentos,
                COALESCE(SUM(CASE WHEN v.venta_tipo IN ('FE','FA','BO') THEN COALESCE(v.venta_totalventa,0) ELSE 0 END),0) AS total,
                MAX(DATE_FORMAT(v.venta_fecha, '%Y-%m-%d')) AS ultima_fecha
            FROM ventas v
            LEFT JOIN clientes c ON c.cliente_rut = v.cliente_rut
            WHERE CAST(v.vendedor_codigo AS CHAR)=%s
              AND v.venta_tipo IN ('FE','FA','BO','NC','CE')
              AND v.venta_fecha >= DATE_SUB(CURDATE(), INTERVAL 30 DAY)
            GROUP BY v.cliente_rut, COALESCE(c.cliente_nombre, v.cliente_rut)
            ORDER BY total DESC
            LIMIT 8
        """, (codigo,))
        clientes_top = clean_rows(cur.fetchall())

        cur.execute("""
            SELECT
                vl.producto_codigo,
                COALESCE(NULLIF(vl.venta_descripcion,''), p.producto_descripcion, vl.producto_codigo) AS producto_descripcion,
                COALESCE(SUM(COALESCE(vl.venta_unidadenvase,0)),0) AS uxe_total,
                COALESCE(SUM(COALESCE(vl.venta_totalneto, vl.venta_lineaneto, 0)),0) AS total
            FROM ventas v
            INNER JOIN ventaslevel2 vl
              ON vl.venta_numero = v.venta_numero
             AND vl.venta_tipo = v.venta_tipo
             AND vl.local_codigo = v.local_codigo
            LEFT JOIN productos p ON p.producto_codigo = vl.producto_codigo
            WHERE CAST(v.vendedor_codigo AS CHAR)=%s
              AND v.venta_tipo IN ('FE','FA','BO','CH')
              AND v.venta_fecha >= DATE_SUB(CURDATE(), INTERVAL 30 DAY)
            GROUP BY vl.producto_codigo, COALESCE(NULLIF(vl.venta_descripcion,''), p.producto_descripcion, vl.producto_codigo)
            ORDER BY uxe_total DESC, total DESC
            LIMIT 8
        """, (codigo,))
        productos_top = clean_rows(cur.fetchall())

        venta_facturas = to_int(summary.get('venta_facturas'), 0)
        venta_boletas = to_int(summary.get('venta_boletas'), 0)
        facturas = to_int(summary.get('facturas'), 0)
        boletas = to_int(summary.get('boletas'), 0)
        return json_safe({
            'vendedor_codigo': codigo,
            'vendedor_nombre': vendedor_nombre,
            'summary': {
                'facturas': facturas,
                'boletas': boletas,
                'notas_credito': to_int(summary.get('notas_credito'), 0),
                'documentos': to_int(summary.get('documentos'), 0),
                'clientes': to_int(summary.get('clientes'), 0),
                'venta_facturas': venta_facturas,
                'venta_boletas': venta_boletas,
                'venta_total': venta_facturas + venta_boletas,
                'total_nc': to_int(summary.get('total_nc'), 0),
                'ticket_promedio': to_int((venta_facturas + venta_boletas) / max(facturas + boletas, 1), 0),
            },
            'trend': [{'fecha': clean_text(r.get('fecha'), 10), 'total': to_int(r.get('total'), 0)} for r in trend],
            'clientes': [
                {
                    'cliente_rut': clean_text(r.get('cliente_rut'), 20) or '-',
                    'cliente_nombre': clean_text(r.get('cliente_nombre'), 80) or '-',
                    'documentos': to_int(r.get('documentos'), 0),
                    'total': to_int(r.get('total'), 0),
                    'ultima_fecha': clean_text(r.get('ultima_fecha'), 10) or '-',
                }
                for r in clientes_top
            ],
            'productos': [
                {
                    'producto_codigo': clean_text(r.get('producto_codigo'), 20) or '-',
                    'producto_descripcion': clean_text(r.get('producto_descripcion'), 80) or '-',
                    'uxe_total': to_float(r.get('uxe_total'), 0) or 0,
                    'total': to_int(r.get('total'), 0),
                }
                for r in productos_top
            ],
        })

@app.get('/gerente/dashboard')
def gerente_dashboard():
    now = time.time()
    cached = _GERENTE_DASHBOARD_CACHE.get('data')
    if cached is not None and now - float(_GERENTE_DASHBOARD_CACHE.get('ts') or 0) < _GERENTE_DASHBOARD_CACHE_SECONDS:
        return json_safe(cached)

    with cursor() as (_, cur):
        vendedor_cols = table_columns(cur, 'vendedores')
        vendedor_name_col = None
        for c in ('vendedor_nombre', 'vendedor_descripcion', 'vendedor_name', 'nombre'):
            if c in vendedor_cols:
                vendedor_name_col = c
                break
        vendedor_name_expr = f"COALESCE(vend.{vendedor_name_col}, CAST(v.vendedor_codigo AS CHAR))" if vendedor_name_col else "CAST(v.vendedor_codigo AS CHAR)"

        cur.execute("""
            SELECT
                SUM(CASE WHEN venta_tipo IN ('FE','FA') THEN 1 ELSE 0 END) AS facturas_mes,
                SUM(CASE WHEN venta_tipo='BO' THEN 1 ELSE 0 END) AS boletas_mes,
                SUM(CASE WHEN venta_tipo IN ('NC','CE') THEN 1 ELSE 0 END) AS nc_mes,
                COALESCE(SUM(CASE WHEN venta_tipo IN ('FE','FA','BO') THEN COALESCE(venta_totalventa,0) ELSE 0 END),0) AS ventas_mes,
                COALESCE(SUM(CASE WHEN venta_tipo IN ('FE','FA','BO') THEN COALESCE(venta_pagototal,0) ELSE 0 END),0) AS cobranza_mes,
                COUNT(DISTINCT CASE WHEN venta_tipo IN ('FE','FA','BO') THEN cliente_rut END) AS clientes_mes
            FROM ventas
            WHERE venta_tipo IN ('FE','FA','BO','NC','CE')
              AND venta_fecha >= DATE_FORMAT(CURDATE(), '%Y-%m-01')
        """)
        summary = clean_row(cur.fetchone() or {})

        cur.execute(f"""
            SELECT
                COALESCE(CAST(v.vendedor_codigo AS CHAR), '-') AS vendedor_codigo,
                {vendedor_name_expr} AS vendedor_nombre,
                SUM(CASE WHEN v.venta_tipo IN ('FE','FA') THEN 1 ELSE 0 END) AS facturas,
                COUNT(*) AS documentos,
                COALESCE(SUM(CASE WHEN v.venta_tipo IN ('FE','FA','BO') THEN COALESCE(v.venta_totalventa,0) ELSE 0 END),0) AS total,
                COUNT(DISTINCT v.cliente_rut) AS clientes
            FROM ventas v
            LEFT JOIN vendedores vend ON CAST(vend.vendedor_codigo AS CHAR)=CAST(v.vendedor_codigo AS CHAR)
            WHERE v.venta_tipo IN ('FE','FA','BO','NC','CE')
              AND v.venta_fecha >= DATE_FORMAT(CURDATE(), '%Y-%m-01')
            GROUP BY COALESCE(CAST(v.vendedor_codigo AS CHAR), '-'), {vendedor_name_expr}
            ORDER BY total DESC
            LIMIT 10
        """)
        vendedores = clean_rows(cur.fetchall())

        cur.execute("""
            SELECT
                DATE_FORMAT(d.dt, '%Y-%m-%d') AS fecha,
                COALESCE(SUM(CASE WHEN v.venta_tipo IN ('FE','FA','BO') THEN COALESCE(v.venta_totalventa,0) ELSE 0 END),0) AS total
            FROM (
                SELECT CURDATE() - INTERVAL 6 DAY AS dt UNION ALL
                SELECT CURDATE() - INTERVAL 5 DAY UNION ALL
                SELECT CURDATE() - INTERVAL 4 DAY UNION ALL
                SELECT CURDATE() - INTERVAL 3 DAY UNION ALL
                SELECT CURDATE() - INTERVAL 2 DAY UNION ALL
                SELECT CURDATE() - INTERVAL 1 DAY UNION ALL
                SELECT CURDATE()
            ) d
            LEFT JOIN ventas v ON v.venta_tipo IN ('FE','FA','BO','NC','CE') AND DATE(v.venta_fecha)=d.dt
            GROUP BY d.dt
            ORDER BY d.dt
        """)
        trend_7 = clean_rows(cur.fetchall())

        cur.execute("""
            SELECT
                DATE_FORMAT(venta_fecha, '%Y-%m-%d') AS fecha,
                COALESCE(SUM(CASE WHEN venta_tipo IN ('FE','FA','BO') THEN COALESCE(venta_totalventa,0) ELSE 0 END),0) AS total
            FROM ventas
            WHERE venta_tipo IN ('FE','FA','BO','NC','CE')
              AND venta_fecha >= DATE_FORMAT(CURDATE(), '%Y-%m-01')
            GROUP BY DATE(venta_fecha)
            ORDER BY DATE(venta_fecha)
        """)
        ventas_mes_diarias = clean_rows(cur.fetchall())

        cur.execute("""
            SELECT
                COALESCE(SUM(
                    CASE
                      WHEN venta_tipo IN ('NC','CE') THEN -1 * (COALESCE(venta_totalventa,0) - COALESCE(venta_pagototal,0))
                      ELSE (COALESCE(venta_totalventa,0) - COALESCE(venta_pagototal,0))
                    END
                ),0) AS deuda_total,
                COUNT(DISTINCT cliente_rut) AS clientes_deuda
            FROM ventas
            WHERE venta_tipo IN ('FE','FA','BO','CH','NC','CE')
              AND venta_fecha >= DATE_FORMAT(CURDATE(), '%Y-%m-01')
              AND (COALESCE(venta_totalventa,0) - COALESCE(venta_pagototal,0)) <> 0
        """)
        deuda_summary = clean_row(cur.fetchone() or {})

        cur.execute("""
            SELECT
                x.cliente_rut,
                COALESCE(c.cliente_nombre, x.cliente_rut) AS cliente_nombre,
                x.saldo,
                x.documentos,
                x.ultima_fecha
            FROM (
                SELECT
                    cliente_rut,
                    SUM(
                        CASE
                          WHEN venta_tipo IN ('NC','CE') THEN -1 * (COALESCE(venta_totalventa,0) - COALESCE(venta_pagototal,0))
                          ELSE (COALESCE(venta_totalventa,0) - COALESCE(venta_pagototal,0))
                        END
                    ) AS saldo,
                    COUNT(*) AS documentos,
                    MAX(DATE_FORMAT(venta_fecha, '%Y-%m-%d')) AS ultima_fecha
                FROM ventas
                WHERE venta_tipo IN ('FE','FA','BO','CH','NC','CE')
                  AND venta_fecha >= DATE_FORMAT(CURDATE(), '%Y-%m-01')
                  AND (COALESCE(venta_totalventa,0) - COALESCE(venta_pagototal,0)) <> 0
                GROUP BY cliente_rut
            ) x
            LEFT JOIN clientes c ON c.cliente_rut = x.cliente_rut
            WHERE x.saldo > 0
            ORDER BY x.saldo DESC
            LIMIT 6
        """)
        deudas = clean_rows(cur.fetchall())

        cur.execute("""
            SELECT
                COALESCE(SUM(CASE WHEN venta_tipo IN ('FE','FA','BO') AND venta_fecha >= CURDATE() AND venta_fecha < CURDATE() + INTERVAL 1 DAY THEN COALESCE(venta_totalventa,0) ELSE 0 END),0) AS ventas_dia,
                COALESCE(SUM(CASE WHEN venta_tipo IN ('FE','FA','BO') AND COALESCE(CAST(local_codigo AS CHAR),'01')='01' THEN COALESCE(venta_totalventa,0) ELSE 0 END),0) AS ventas_local_01,
                COALESCE(SUM(CASE WHEN venta_tipo IN ('FE','FA','BO') AND COALESCE(CAST(local_codigo AS CHAR),'01')='02' THEN COALESCE(venta_totalventa,0) ELSE 0 END),0) AS ventas_local_02
            FROM ventas
            WHERE venta_tipo IN ('FE','FA','BO','NC','CE')
              AND venta_fecha >= DATE_FORMAT(CURDATE(), '%Y-%m-01')
        """)
        local_summary = clean_row(cur.fetchone() or {})

        cur.execute("""
            SELECT
                COALESCE(CAST(local_codigo AS CHAR),'01') AS local_codigo,
                COALESCE(SUM(CASE WHEN venta_tipo IN ('FE','FA','BO') THEN COALESCE(venta_totalventa,0) ELSE 0 END),0) AS total
            FROM ventas
            WHERE venta_tipo IN ('FE','FA','BO')
              AND venta_fecha >= DATE_FORMAT(CURDATE(), '%Y-%m-01')
              AND COALESCE(CAST(local_codigo AS CHAR),'01') IN ('01','02')
            GROUP BY COALESCE(CAST(local_codigo AS CHAR),'01')
            ORDER BY local_codigo
        """)
        ventas_locales = clean_rows(cur.fetchall())

        cur.execute("""
            SELECT
                DATE_FORMAT(venta_fecha, '%Y-%m') AS fecha,
                COALESCE(SUM(CASE WHEN COALESCE(CAST(local_codigo AS CHAR),'01')='01' THEN COALESCE(venta_totalventa,0) ELSE 0 END),0) AS total_01,
                COALESCE(SUM(CASE WHEN COALESCE(CAST(local_codigo AS CHAR),'01')='02' THEN COALESCE(venta_totalventa,0) ELSE 0 END),0) AS total_02
            FROM ventas
            WHERE venta_tipo IN ('FE','FA','BO')
              AND venta_fecha >= DATE_FORMAT(CURDATE(), '%Y-01-01')
              AND venta_fecha < DATE_ADD(DATE_FORMAT(CURDATE(), '%Y-01-01'), INTERVAL 1 YEAR)
              AND COALESCE(CAST(local_codigo AS CHAR),'01') IN ('01','02')
            GROUP BY DATE_FORMAT(venta_fecha, '%Y-%m')
            ORDER BY fecha
        """)
        ventas_mensuales_locales = clean_rows(cur.fetchall())

        cur.execute("""
            SELECT
                DATE_FORMAT(venta_fecha, '%Y-%m-%d') AS fecha,
                COALESCE(SUM(CASE WHEN COALESCE(CAST(local_codigo AS CHAR),'01')='01' THEN COALESCE(venta_totalventa,0) ELSE 0 END),0) AS total_01,
                COALESCE(SUM(CASE WHEN COALESCE(CAST(local_codigo AS CHAR),'01')='02' THEN COALESCE(venta_totalventa,0) ELSE 0 END),0) AS total_02
            FROM ventas
            WHERE venta_tipo IN ('FE','FA','BO')
              AND venta_fecha >= DATE_FORMAT(CURDATE(), '%Y-%m-01')
              AND venta_fecha < DATE_ADD(DATE_FORMAT(CURDATE(), '%Y-%m-01'), INTERVAL 1 MONTH)
              AND COALESCE(CAST(local_codigo AS CHAR),'01') IN ('01','02')
            GROUP BY DATE(venta_fecha)
            ORDER BY DATE(venta_fecha)
        """)
        ventas_mes_locales = clean_rows(cur.fetchall())

        clientes_cols = table_columns(cur, 'clientes')
        cols_lower = {str(c).lower(): c for c in clientes_cols}
        comuna_col = cols_lower.get('comuna') or cols_lower.get('cliente_comuna') or cols_lower.get('cliente_ciudad')
        sector_expr = f"COALESCE(NULLIF(c.{comuna_col},''), r.ruta_nombre, 'Sin sector')" if comuna_col else "COALESCE(r.ruta_nombre, 'Sin sector')"
        cur.execute(f"""
            SELECT
                {sector_expr} AS sector,
                COUNT(DISTINCT v.cliente_rut) AS clientes,
                COALESCE(SUM(CASE WHEN v.venta_tipo IN ('FE','FA','BO') THEN COALESCE(v.venta_totalventa,0) ELSE 0 END),0) AS total
            FROM ventas v
            LEFT JOIN clientes c ON c.cliente_rut = v.cliente_rut
            LEFT JOIN rutas r ON r.ruta_id = c.ruta_id
            WHERE v.venta_tipo IN ('FE','FA','BO')
              AND v.venta_fecha >= DATE_FORMAT(CURDATE(), '%Y-%m-01')
            GROUP BY {sector_expr}
            ORDER BY total DESC
            LIMIT 8
        """)
        sectores = clean_rows(cur.fetchall())

        cur.execute("""
            SELECT
                m.mes,
                COALESCE(a.total,0) AS actual,
                COALESCE(p.total,0) AS anterior
            FROM (
                SELECT 1 AS mes UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6
                UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12
            ) m
            LEFT JOIN (
                SELECT MONTH(venta_fecha) AS mes, SUM(COALESCE(venta_totalventa,0)) AS total
                FROM ventas
                WHERE venta_tipo IN ('FE','FA','BO')
                  AND YEAR(venta_fecha)=YEAR(CURDATE())
                GROUP BY MONTH(venta_fecha)
            ) a ON a.mes=m.mes
            LEFT JOIN (
                SELECT MONTH(venta_fecha) AS mes, SUM(COALESCE(venta_totalventa,0)) AS total
                FROM ventas
                WHERE venta_tipo IN ('FE','FA','BO')
                  AND YEAR(venta_fecha)=YEAR(CURDATE())-1
                GROUP BY MONTH(venta_fecha)
            ) p ON p.mes=m.mes
            ORDER BY m.mes
        """)
        ventas_anio_comparativo = clean_rows(cur.fetchall())

        cur.execute("""
            SELECT
                v.venta_numero,
                v.cliente_rut,
                COALESCE(c.cliente_nombre, v.cliente_rut) AS cliente_nombre,
                COALESCE(c.cliente_geo, '') AS cliente_geo,
                COALESCE(v.venta_totalventa,0) AS total
            FROM ventas v
            LEFT JOIN clientes c ON c.cliente_rut = v.cliente_rut
            WHERE v.venta_tipo='NV'
              AND v.venta_fecha >= CURDATE()
              AND v.venta_fecha < CURDATE() + INTERVAL 1 DAY
              AND COALESCE(c.cliente_geo,'') <> ''
            ORDER BY v.venta_numero DESC
            LIMIT 80
        """)
        nv_hoy_mapa = clean_rows(cur.fetchall())

        ventas_mes = to_int(summary.get('ventas_mes'), 0)
        facturas_mes = to_int(summary.get('facturas_mes'), 0)
        boletas_mes = to_int(summary.get('boletas_mes'), 0)
        deuda_total = to_int(deuda_summary.get('deuda_total'), 0)
        cobranza_mes = to_int(summary.get('cobranza_mes'), 0)
        mes_actual = datetime.datetime.now(SERVER_TZ).strftime('%Y-%m')

        result = {
            'summary': {
                'ventas_30': ventas_mes,
                'facturas_30': facturas_mes,
                'boletas_30': boletas_mes,
                'nc_30': to_int(summary.get('nc_mes'), 0),
                'ventas_mes': ventas_mes,
                'facturas_mes': facturas_mes,
                'boletas_mes': boletas_mes,
                'nc_mes': to_int(summary.get('nc_mes'), 0),
                'deuda_total': deuda_total,
                'cobranza_30': cobranza_mes,
                'cobranza_mes': cobranza_mes,
                'clientes_30': to_int(summary.get('clientes_mes'), 0),
                'clientes_mes': to_int(summary.get('clientes_mes'), 0),
                'clientes_deuda': to_int(deuda_summary.get('clientes_deuda'), 0),
                'mes_actual': mes_actual,
                'ventas_dia': to_int(local_summary.get('ventas_dia'), 0),
                'ventas_local_01': to_int(local_summary.get('ventas_local_01'), 0),
                'ventas_local_02': to_int(local_summary.get('ventas_local_02'), 0),
            },
            'vendedores': [
                {
                    'vendedor_codigo': clean_text(r.get('vendedor_codigo'), 20) or '-',
                    'vendedor_nombre': clean_text(r.get('vendedor_nombre'), 80) or '-',
                    'facturas': to_int(r.get('facturas'), 0),
                    'documentos': to_int(r.get('documentos'), 0),
                    'total': to_int(r.get('total'), 0),
                    'clientes': to_int(r.get('clientes'), 0),
                }
                for r in vendedores
            ],
            'trend': [{'fecha': clean_text(r.get('fecha'), 10), 'total': to_int(r.get('total'), 0)} for r in trend_7],
            'ventas_mes': [{'fecha': clean_text(r.get('fecha'), 10), 'total': to_int(r.get('total'), 0)} for r in ventas_mes_diarias],
            'deudas': [
                {
                    'cliente_rut': clean_text(r.get('cliente_rut'), 20) or '-',
                    'cliente_nombre': clean_text(r.get('cliente_nombre'), 80) or '-',
                    'saldo': to_int(r.get('saldo'), 0),
                    'documentos': to_int(r.get('documentos'), 0),
                    'ultima_fecha': clean_text(r.get('ultima_fecha'), 10) or '-',
                }
                for r in deudas
            ],
            'ventas_locales': [
                {'local_codigo': clean_text(r.get('local_codigo'), 10) or '-', 'total': to_int(r.get('total'), 0)}
                for r in ventas_locales
            ],
            'ventas_mensuales_locales': [
                {
                    'fecha': clean_text(r.get('fecha'), 10) or '-',
                    'total_01': to_int(r.get('total_01'), 0),
                    'total_02': to_int(r.get('total_02'), 0),
                }
                for r in ventas_mensuales_locales
            ],
            'ventas_mes_locales': [
                {
                    'fecha': clean_text(r.get('fecha'), 10) or '-',
                    'total_01': to_int(r.get('total_01'), 0),
                    'total_02': to_int(r.get('total_02'), 0),
                }
                for r in ventas_mes_locales
            ],
            'sectores': [
                {
                    'sector': clean_text(r.get('sector'), 80) or 'Sin sector',
                    'total': to_int(r.get('total'), 0),
                    'clientes': to_int(r.get('clientes'), 0),
                }
                for r in sectores
            ],
            'ventas_anio_comparativo': [
                {
                    'mes': to_int(r.get('mes'), 0),
                    'mes_label': ['Ene','Feb','Mar','Abr','May','Jun','Jul','Ago','Sep','Oct','Nov','Dic'][max(1, min(12, to_int(r.get('mes'), 1))) - 1],
                    'actual': to_int(r.get('actual'), 0),
                    'anterior': to_int(r.get('anterior'), 0),
                }
                for r in ventas_anio_comparativo
            ],
            'nv_hoy_mapa': [
                {
                    'venta_numero': to_int(r.get('venta_numero'), 0),
                    'cliente_rut': clean_text(r.get('cliente_rut'), 20) or '-',
                    'cliente_nombre': clean_text(r.get('cliente_nombre'), 80) or '-',
                    'cliente_geo': clean_text(r.get('cliente_geo'), 80) or '',
                    'total': to_int(r.get('total'), 0),
                }
                for r in nv_hoy_mapa
            ],
            'alertas': [],
        }
        _GERENTE_DASHBOARD_CACHE['ts'] = time.time()
        _GERENTE_DASHBOARD_CACHE['data'] = result
        return json_safe(result)

@app.get('/logistica/dashboard')
def logistica_dashboard(fecha: str | None = None):
    with cursor() as (_, cur):
        ensure_logistica_table(cur)
        ventas_cols = table_columns(cur, 'ventas')
        fecha_reparto_col = 'venta_fechavto' if 'venta_fechavto' in ventas_cols else 'venta_fecha'
        direccion_expr = "COALESCE(v.venta_direccion, c.cliente_direccion, '')" if 'venta_direccion' in ventas_cols else "COALESCE(c.cliente_direccion, '')"
        try:
            fecha_reparto = datetime.date.fromisoformat(clean_text(fecha, 10) or datetime.datetime.now(SERVER_TZ).strftime('%Y-%m-%d'))
        except Exception:
            raise HTTPException(status_code=400, detail='Fecha de reparto invalida. Use formato YYYY-MM-DD')

        cur.execute(f"""
            SELECT
                v.venta_numero,
                v.venta_tipo,
                COALESCE(CAST(v.local_codigo AS CHAR), '01') AS local_codigo,
                DATE_FORMAT(v.{fecha_reparto_col}, '%Y-%m-%d') AS fecha_reparto,
                COALESCE(v.cliente_rut, '') AS cliente_rut,
                COALESCE(c.cliente_nombre, v.cliente_rut, '') AS cliente_nombre,
                {direccion_expr} AS direccion,
                COALESCE(c.Comuna, '') AS comuna,
                COALESCE(CAST(c.ruta_id AS CHAR), '-') AS ruta_id,
                COALESCE(r.ruta_nombre, CONCAT('Ruta ', COALESCE(CAST(c.ruta_id AS CHAR), '-'))) AS ruta_nombre,
                COALESCE(c.cliente_geo, '') AS cliente_geo,
                COALESCE(v.venta_totalventa,0) AS total,
                COALESCE(le.estado, 'PENDIENTE') AS estado,
                COALESCE(le.observacion, '') AS observacion,
                DATE_FORMAT(le.updated_at, '%Y-%m-%d %H:%i') AS actualizado
            FROM ventas v
            LEFT JOIN clientes c ON c.cliente_rut = v.cliente_rut
            LEFT JOIN rutas r ON r.ruta_id = c.ruta_id
            LEFT JOIN mobile_logistica_estado le
              ON le.venta_numero = v.venta_numero
             AND le.venta_tipo = v.venta_tipo
             AND le.local_codigo = COALESCE(CAST(v.local_codigo AS CHAR), '01')
            WHERE v.venta_tipo IN ('FE','FA','BO','NV')
              AND v.{fecha_reparto_col} >= %s
              AND v.{fecha_reparto_col} < DATE_ADD(%s, INTERVAL 1 DAY)
            ORDER BY COALESCE(c.ruta_id, 999999), c.cliente_nombre, v.venta_numero
            LIMIT 200
        """, (fecha_reparto, fecha_reparto))
        docs = clean_rows(cur.fetchall())

        total_docs = len(docs)
        entregados = sum(1 for d in docs if clean_text(d.get('estado'), 20) == 'ENTREGADO')
        no_entregados = sum(1 for d in docs if clean_text(d.get('estado'), 20) == 'NO_ENTREGADO')
        en_ruta = sum(1 for d in docs if clean_text(d.get('estado'), 20) == 'EN_RUTA')
        pendientes = max(total_docs - entregados - no_entregados - en_ruta, 0)
        total_monto = sum(to_int(d.get('total'), 0) for d in docs)

        rutas = {}
        for d in docs:
            key = clean_text(d.get('ruta_id'), 20) or '-'
            row = rutas.setdefault(key, {
                'ruta_id': key,
                'ruta_nombre': clean_text(d.get('ruta_nombre'), 80) or f'Ruta {key}',
                'documentos': 0,
                'entregados': 0,
                'pendientes': 0,
                'total': 0,
            })
            row['documentos'] += 1
            row['total'] += to_int(d.get('total'), 0)
            if clean_text(d.get('estado'), 20) == 'ENTREGADO':
                row['entregados'] += 1
            elif clean_text(d.get('estado'), 20) != 'NO_ENTREGADO':
                row['pendientes'] += 1

        return json_safe({
            'fecha': fecha_reparto.strftime('%Y-%m-%d'),
            'summary': {
                'documentos': total_docs,
                'pendientes': pendientes,
                'en_ruta': en_ruta,
                'entregados': entregados,
                'no_entregados': no_entregados,
                'total': total_monto,
            },
            'rutas': list(rutas.values()),
            'documentos': [
                {
                    'venta_numero': to_int(d.get('venta_numero'), 0),
                    'venta_tipo': clean_text(d.get('venta_tipo'), 4) or '',
                    'local_codigo': clean_text(d.get('local_codigo'), 10) or '01',
                    'fecha_reparto': clean_text(d.get('fecha_reparto'), 10) or '',
                    'cliente_rut': clean_text(d.get('cliente_rut'), 20) or '',
                    'cliente_nombre': clean_text(d.get('cliente_nombre'), 80) or '',
                    'direccion': clean_text(d.get('direccion'), 160) or '',
                    'comuna': clean_text(d.get('comuna'), 80) or '',
                    'ruta_id': clean_text(d.get('ruta_id'), 20) or '-',
                    'ruta_nombre': clean_text(d.get('ruta_nombre'), 80) or '-',
                    'cliente_geo': clean_text(d.get('cliente_geo'), 60) or '',
                    'total': to_int(d.get('total'), 0),
                    'estado': clean_text(d.get('estado'), 20) or 'PENDIENTE',
                    'observacion': clean_text(d.get('observacion'), 200) or '',
                    'actualizado': clean_text(d.get('actualizado'), 20) or '',
                }
                for d in docs
            ],
        })

@app.post('/logistica/estado')
def logistica_estado(req: LogisticaEstadoRequest):
    allowed = {'PENDIENTE', 'EN_RUTA', 'ENTREGADO', 'NO_ENTREGADO'}
    estado = clean_text(req.estado, 20) or 'PENDIENTE'
    if estado not in allowed:
        raise HTTPException(status_code=400, detail='Estado de reparto invalido')
    venta_tipo = clean_text(req.venta_tipo, 4) or ''
    local_codigo = clean_text(req.local_codigo, 10) or '01'
    observacion = clean_text(req.observacion, 200)
    if estado == 'NO_ENTREGADO' and not observacion:
        raise HTTPException(status_code=400, detail='Debe indicar observacion para rechazo/no entrega')
    with cursor() as (_, cur):
        ensure_logistica_table(cur)
        cur.execute("""
            INSERT INTO mobile_logistica_estado
              (venta_numero, venta_tipo, local_codigo, estado, observacion, updated_at)
            VALUES (%s, %s, %s, %s, %s, NOW())
            ON DUPLICATE KEY UPDATE
              estado=VALUES(estado),
              observacion=VALUES(observacion),
              updated_at=NOW()
        """, (req.venta_numero, venta_tipo, local_codigo, estado, observacion))
        return json_safe({'ok': True, 'message': 'Estado de reparto actualizado', 'estado': estado})

@app.get('/clientes/{rut}/cuenta-corriente')
def cuenta_corriente_cliente(rut: str):
    cliente_rut = clean_text(rut, 10)
    with cursor() as (_, cur):
        cur.execute("""
            SELECT
                COUNT(*) AS documentos,
                COALESCE(SUM(
                    CASE
                      WHEN venta_tipo IN ('NC','CE') THEN -1 * (COALESCE(venta_totalventa,0) - COALESCE(venta_pagototal,0))
                      ELSE (COALESCE(venta_totalventa,0) - COALESCE(venta_pagototal,0))
                    END
                ),0) AS saldo
            FROM ventas
            WHERE cliente_rut=%s
              AND venta_tipo IN ('FE','BO','FA','CH','NC','CE')
              AND (COALESCE(venta_totalventa,0) - COALESCE(venta_pagototal,0)) <> 0
        """, (cliente_rut,))
        r = clean_row(cur.fetchone() or {})
        return json_safe({
            'cliente_rut': cliente_rut,
            'documentos': to_int(r.get('documentos'), 0),
            'saldo': to_int(r.get('saldo'), 0),
        })


@app.get('/clientes/{rut}/cuenta-corriente-detalle')
def cuenta_corriente_cliente_detalle(rut: str):
    """Detalle online de cuenta corriente por cliente, solo documentos con folio fiscal asignado."""
    cliente_rut = clean_text(rut, 10)
    with cursor() as (_, cur):
        cur.execute("""
            SELECT
                CAST(v.cliente_rut AS CHAR) AS cliente_rut,
                COALESCE(c.cliente_nombre, '') AS cliente_nombre,
                v.venta_numero AS venta_numero,
                v.venta_tipo AS venta_tipo,
                DATE_FORMAT(v.venta_fecha, '%Y-%m-%d') AS venta_fecha,
                COALESCE(v.venta_totalventa,0) AS venta_totalventa,
                COALESCE(v.venta_pagototal,0) AS venta_pagototal,
                COALESCE(v.venta_folio,0) AS venta_folio,
                COALESCE(v.venta_foliosii,0) AS venta_foliosii,
                COALESCE(v.venta_estadosii,'') AS venta_estadosii,
                CASE
                  WHEN v.venta_tipo IN ('NC','CE')
                    THEN -1 * (COALESCE(v.venta_totalventa,0) - COALESCE(v.venta_pagototal,0))
                  ELSE (COALESCE(v.venta_totalventa,0) - COALESCE(v.venta_pagototal,0))
                END AS saldo
            FROM ventas v
            LEFT JOIN clientes c ON c.cliente_rut = v.cliente_rut
            WHERE v.cliente_rut = %s
              AND v.venta_tipo IN ('FE','FA','BO','CH','NC','CE')
              AND COALESCE(v.venta_folio,0) > 0
            ORDER BY v.venta_fecha DESC, v.venta_numero DESC
        """, (cliente_rut,))
        out = []
        for r in clean_rows(cur.fetchall()):
            rut_doc = clean_text(r.get('cliente_rut'), 10)
            if not rut_doc:
                continue
            out.append({
                'cliente_rut': rut_doc,
                'cliente_nombre': clean_text(r.get('cliente_nombre'), 80) or rut_doc,
                'venta_numero': to_int(r.get('venta_numero'), 0),
                'venta_tipo': clean_text(r.get('venta_tipo'), 2) or '',
                'venta_fecha': clean_text(r.get('venta_fecha'), 10),
                'venta_totalventa': to_int(r.get('venta_totalventa'), 0),
                'venta_pagototal': to_int(r.get('venta_pagototal'), 0),
                'venta_folio': to_int(r.get('venta_folio'), 0),
                'venta_foliosii': to_int(r.get('venta_foliosii'), 0),
                'venta_estadosii': clean_text(r.get('venta_estadosii'), 20) or '',
                'saldo': to_int(r.get('saldo'), 0),
            })
        return json_safe(out)


@app.get('/clientes/{rut}/ultima-venta-detalle')
def ultima_venta_detalle_cliente(rut: str):
    cliente_rut = clean_text(rut, 10)
    with cursor() as (_, cur):
        cur.execute("""
            SELECT
                venta_numero,
                venta_tipo,
                DATE_FORMAT(venta_fecha, '%Y-%m-%d') AS venta_fecha,
                COALESCE(venta_totalventa,0) AS venta_totalventa
            FROM ventas
            WHERE cliente_rut=%s
              AND venta_tipo IN ('FE','FA','BO','CH','NV')
            ORDER BY venta_fecha DESC, venta_numero DESC
            LIMIT 1
        """, (cliente_rut,))
        header = clean_row(cur.fetchone() or {})
        if not header:
            return json_safe({
                'cliente_rut': cliente_rut,
                'venta_numero': None,
                'venta_tipo': None,
                'venta_fecha': None,
                'venta_totalventa': 0,
                'lines': [],
            })
        venta_numero = to_int(header.get('venta_numero'), 0)
        venta_tipo = clean_text(header.get('venta_tipo'), 2) or ''
        cur.execute("""
            SELECT
                v.producto_codigo,
                COALESCE(NULLIF(v.venta_descripcion,''), p.producto_descripcion, v.producto_codigo) AS descripcion,
                COALESCE(v.venta_cantidad,0) AS cantidad,
                COALESCE(v.venta_unidadenvase,0) AS uxe,
                COALESCE(v.venta_precioventa, v.venta_precio, 0) AS precio,
                COALESCE(v.venta_totalneto, v.venta_lineaneto, 0) AS total_linea
            FROM ventaslevel2 v
            LEFT JOIN productos p ON p.producto_codigo = v.producto_codigo
            WHERE v.venta_numero=%s
              AND v.venta_tipo=%s
            ORDER BY v.producto_codigo
            LIMIT 30
        """, (venta_numero, venta_tipo))
        lines = clean_rows(cur.fetchall())
        return json_safe({
            'cliente_rut': cliente_rut,
            'venta_numero': venta_numero,
            'venta_tipo': venta_tipo,
            'venta_fecha': header.get('venta_fecha'),
            'venta_totalventa': to_int(header.get('venta_totalventa'), 0),
            'lines': lines,
        })


@app.get('/ventas/{venta_tipo}/{venta_numero}/detalle')
def venta_documento_detalle(venta_tipo: str, venta_numero: int, cliente_rut: str | None = None, local_codigo: str = '01'):
    tipo = clean_text(venta_tipo, 4) or ''
    rut = clean_text(cliente_rut, 10)
    local = clean_text(local_codigo, 10) or '01'
    with cursor() as (_, cur):
        params = [venta_numero, tipo, local]
        rut_filter = ''
        if rut:
            rut_filter = ' AND cliente_rut=%s'
            params.append(rut)
        cur.execute(f"""
            SELECT
                cliente_rut,
                venta_numero,
                venta_tipo,
                DATE_FORMAT(venta_fecha, '%Y-%m-%d') AS venta_fecha,
                COALESCE(venta_totalventa,0) AS venta_totalventa
            FROM ventas
            WHERE venta_numero=%s
              AND venta_tipo=%s
              AND local_codigo=%s
              {rut_filter}
            LIMIT 1
        """, tuple(params))
        header = clean_row(cur.fetchone() or {})
        if not header:
            return json_safe({
                'cliente_rut': rut,
                'venta_numero': None,
                'venta_tipo': tipo,
                'venta_fecha': None,
                'venta_totalventa': 0,
                'lines': [],
            })
        cur.execute("""
            SELECT
                v.producto_codigo,
                COALESCE(NULLIF(v.venta_descripcion,''), p.producto_descripcion, v.producto_codigo) AS descripcion,
                COALESCE(v.venta_cantidad,0) AS cantidad,
                COALESCE(v.venta_unidadenvase,0) AS uxe,
                COALESCE(v.venta_precioventa, v.venta_precio, 0) AS precio,
                COALESCE(v.venta_totalneto, v.venta_lineaneto, 0) AS total_linea
            FROM ventaslevel2 v
            LEFT JOIN productos p ON p.producto_codigo = v.producto_codigo
            WHERE v.venta_numero=%s
              AND v.venta_tipo=%s
              AND v.local_codigo=%s
            ORDER BY v.producto_codigo
            LIMIT 80
        """, (venta_numero, tipo, local))
        return json_safe({
            'cliente_rut': clean_text(header.get('cliente_rut'), 10) or rut,
            'venta_numero': to_int(header.get('venta_numero'), 0),
            'venta_tipo': clean_text(header.get('venta_tipo'), 4) or tipo,
            'venta_fecha': header.get('venta_fecha'),
            'venta_totalventa': to_int(header.get('venta_totalventa'), 0),
            'lines': clean_rows(cur.fetchall()),
        })
