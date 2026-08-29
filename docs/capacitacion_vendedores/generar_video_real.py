from __future__ import annotations

import subprocess
import textwrap
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageFilter


ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs" / "capacitacion_vendedores"
FRAMES = OUT / "video_real_frames"
AUDIO = OUT / "video_real_audio"
SEGMENTS = OUT / "video_real_segments"
IMG = ROOT / "docs" / "img"
LOGO = ROOT / "android_app" / "app" / "src" / "main" / "res" / "drawable" / "logo_risek.png"
BG = ROOT / "android_app" / "app" / "src" / "main" / "res" / "drawable-nodpi" / "login_sales_bg_soft.png"

W, H = 1280, 720
RED = "#e30613"
DARK = "#111827"
MUTED = "#6b7280"
BLUE = "#073b4c"


def ensure_dirs():
    for p in (FRAMES, AUDIO, SEGMENTS):
        p.mkdir(parents=True, exist_ok=True)


def font(size: int, bold: bool = False):
    candidates = [
        "C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf",
        "C:/Windows/Fonts/seguisb.ttf" if bold else "C:/Windows/Fonts/segoeui.ttf",
    ]
    for c in candidates:
        if Path(c).exists():
            return ImageFont.truetype(c, size)
    return ImageFont.load_default()


def fit_image(path: Path, box: tuple[int, int], crop: tuple[float, float, float, float] | None = None) -> Image.Image:
    im = Image.open(path).convert("RGB")
    if crop:
        w, h = im.size
        x1, y1, x2, y2 = crop
        im = im.crop((int(w * x1), int(h * y1), int(w * x2), int(h * y2)))
    im.thumbnail(box, Image.LANCZOS)
    return im


def background() -> Image.Image:
    base = Image.new("RGB", (W, H), "#f6f7f9")
    if BG.exists():
        bg = Image.open(BG).convert("RGB")
        bg = bg.resize((W, H), Image.LANCZOS).filter(ImageFilter.GaussianBlur(1.4))
        overlay = Image.new("RGB", (W, H), "#111827")
        base = Image.blend(bg, overlay, 0.70)
    return base


def draw_logo(im: Image.Image):
    if LOGO.exists():
        logo = Image.open(LOGO).convert("RGBA")
        logo.thumbnail((180, 62))
        im.paste(logo, (44, 34), logo)


def draw_wrapped(draw: ImageDraw.ImageDraw, xy: tuple[int, int], text: str, fnt, fill: str, width: int, leading: int):
    x, y = xy
    for line in textwrap.wrap(text, width=width):
        draw.text((x, y), line, font=fnt, fill=fill)
        y += leading
    return y


def make_frame(idx: int, title: str, subtitle: str, bullets: list[str], image: Path | None, callout: str, crop=None) -> Path:
    im = background()
    d = ImageDraw.Draw(im)
    draw_logo(im)
    d.rounded_rectangle((28, 24, W - 28, H - 24), radius=26, outline=RED, width=3)
    d.rounded_rectangle((52, 112, 500, 668), radius=24, fill="#0f172a", outline="#ffffff", width=2)
    if image and image.exists():
        phone = fit_image(image, (392, 520), crop)
        x = 52 + (448 - phone.width) // 2
        y = 132 + (512 - phone.height) // 2
        d.rounded_rectangle((x - 10, y - 10, x + phone.width + 10, y + phone.height + 10), radius=22, fill="#111827")
        im.paste(phone, (x, y))
        if title == "Sync":
            d.rounded_rectangle((x + 30, y + 178, x + phone.width - 30, y + 225), radius=10, fill="#ffffff", outline="#d1d5db", width=1)
            d.text((x + 48, y + 191), "Sincronizacion de datos", font=font(18, True), fill=DARK)
        if title == "Login":
            d.rounded_rectangle((x + 36, y + 250, x + phone.width - 36, y + 325), radius=10, fill="#ffffff", outline="#d1d5db", width=1)
            d.text((x + 56, y + 262), "Usuario", font=font(16), fill=MUTED)
            d.text((x + 56, y + 294), "Clave", font=font(16), fill=MUTED)
    else:
        d.text((130, 360), "RISEK VENTAS", font=font(38, True), fill="white")
    d.rectangle((548, 96, 1188, 168), fill=RED)
    d.text((580, 114), title, font=font(34, True), fill="white")
    d.text((580, 194), subtitle, font=font(30, True), fill="white")
    y = 256
    for b in bullets:
        d.ellipse((586, y + 12, 604, y + 30), fill=RED)
        y = draw_wrapped(d, (622, y), b, font(25), "#f9fafb", 37, 33) + 12
    d.rounded_rectangle((580, 572, 1184, 640), radius=16, fill="#eef7fa", outline="#b9dce5", width=2)
    draw_wrapped(d, (604, 592), callout, font(21, True), BLUE, 58, 27)
    d.text((1084, 42), f"{idx + 1:02d}", font=font(24, True), fill="#e5e7eb")
    out = FRAMES / f"frame_{idx:02d}.png"
    im.save(out)
    return out


def tts(text: str, wav: Path) -> bool:
    text = text.replace("'", "''")
    script = (
        "Add-Type -AssemblyName System.Speech; "
        "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer; "
        "try { $s.SelectVoice('Microsoft Helena Desktop') } catch {} "
        "$s.Rate=-1; $s.Volume=100; "
        f"$s.SetOutputToWaveFile('{str(wav)}'); "
        f"$s.Speak('{text}'); "
        "$s.Dispose();"
    )
    try:
        subprocess.run(["powershell", "-NoProfile", "-Command", script], check=True, timeout=60, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return wav.exists() and wav.stat().st_size > 1000
    except Exception:
        return False


SCENES = [
    ("RISEK Ventas", "Capacitacion vendedores", ["Vamos a ver el uso diario de la app.", "La idea es vender rapido, sin enredos.", "Siempre revisar datos antes de salir."], None, "Material para usar en terreno.", "Ya equipo, en este video vamos a revisar la app RISEK Ventas, paso a paso, bien aterrizado para el trabajo en ruta.", None),
    ("Login", "Ingreso a la app", ["Seleccione su usuario.", "Ingrese su clave.", "Presione INGRESAR.", "Si la clave esta mala, la app avisa."], IMG / "01_login.png", "No comparta su clave.", "Primero, abra la app. Seleccione su usuario, escriba la clave y presione ingresar. Ojo, la clave es personal.", None),
    ("Menu principal", "Opciones de trabajo", ["Pedidos muestra notas guardadas.", "Nueva NV crea la venta.", "Cta Cte revisa deuda.", "Consulta muestra productos y precios.", "Sync actualiza y envia datos."], IMG / "02_pedidos.png", "Use cada modulo segun momento de la venta.", "Al entrar, vera el menu inferior. Pedidos, Nueva nota de venta, Dia, Cuenta corriente, Consulta y Sync.", None),
    ("Sync", "Antes de salir a ruta", ["Presione Descargar datos.", "Espere mensaje final.", "Si dice Ventas operativas, puede vender.", "Al cierre, envie NV pendientes."], IMG / "06_sync.png", "Sincronice antes de salir.", "Antes de partir la ruta, entre a Sync y descargue datos. Si aparece ventas operativas, puede seguir trabajando con la ultima descarga buena.", None),
    ("Nueva NV", "Datos del cliente", ["Busque cliente.", "Seleccione direccion de reparto.", "Revise Fecha Reparto o Entrega.", "Luego busque productos."], IMG / "03_nueva_nv.png", "Cliente y direccion correctos evitan reclamos.", "Para vender, entre a Nueva NV. Primero el cliente, despues la direccion de reparto y la fecha de entrega.", (0, 0, 1, 0.55)),
    ("Agregar producto", "Cantidad, cajas y descuento", ["Busque por codigo, descripcion o familia.", "Ingrese UXE o cajas.", "Aplique descuento solo si corresponde.", "Presione Agregar."], IMG / "03_nueva_nv.png", "Revise precio y descuento antes de guardar.", "En productos, busque por codigo o descripcion. Ingrese UXE o cajas. Si hay descuento, pongalo dentro del maximo permitido.", (0, 0.30, 1, 1)),
    ("Carrito", "Validar la NV", ["Abra carrito de la cabecera.", "Revise productos agregados.", "Confirme cantidad, precio y descuento.", "Guarde la NV."], IMG / "03_nueva_nv.png", "Antes de guardar, mirar carrito.", "Antes de guardar, toque el carrito y revise todo. Esto evita que falten productos o cantidades.", None),
    ("Pedidos", "Revisar notas", ["Vea NV pendientes.", "Abra detalle cuando necesite.", "Sincronizada muestra estado.", "Si queda pendiente, enviar desde Sync."], IMG / "02_pedidos.png", "Pedidos es control diario de NV.", "En Pedidos puede revisar las notas guardadas, ver detalle y confirmar si ya fueron sincronizadas.", None),
    ("Cuenta corriente", "Informacion al cliente", ["Busque cliente.", "Revise documentos y saldo.", "Abra detalle de factura.", "Genere PDF o envie por WhatsApp."], IMG / "04_cta_cte.png", "Entregue informacion clara al cliente.", "En Cuenta corriente puede revisar documentos, saldos y enviar la cartola por PDF o WhatsApp cuando el cliente lo pida.", None),
    ("Consulta", "Productos y precios", ["Busque codigo exacto.", "O busque por descripcion.", "Revise precio Lista 01.", "Revise stock y descuento maximo."], IMG / "05_consulta.png", "Consulta sirve sin crear una venta.", "Consulta permite responder rapido precios, stock y descuento maximo, sin abrir una nota de venta.", None),
    ("Dia", "Cierre de jornada", ["Revise ventas del dia.", "Abra detalle si hay duda.", "Mire total general.", "Envíe pendientes antes de cerrar."], IMG / "07_dia.png", "Cierre ordenado evita diferencias.", "Al final del dia revise sus ventas, abra detalles si hace falta y envie todo lo pendiente.", None),
    ("Reporte mensual", "PDF por correo", ["Entre a Sync.", "Seleccione mes y ano.", "Ingrese correo.", "Envie reporte del vendedor logueado."], IMG / "06_sync.png", "Reporte en pesos chilenos.", "Para reporte mensual, desde Sync seleccione mes y ano, ingrese correo y envie. El reporte corresponde al vendedor conectado.", None),
    ("Buenas practicas", "Trabajo seguro", ["Sincronizar antes de ruta.", "Revisar carrito.", "Enviar pendientes al cierre.", "Si hay error, enviar captura y mensaje exacto."], None, "Con orden, venta queda lista altiro.", "Listo. Sincronice antes de salir, revise el carrito, envie pendientes al cierre y si hay un error, mande captura con el mensaje exacto.", None),
]


def main():
    ensure_dirs()
    segments = []
    script_lines = []
    for i, (title, subtitle, bullets, img, callout, voice, crop) in enumerate(SCENES):
        frame = make_frame(i, title, subtitle, bullets, img, callout, crop)
        wav = AUDIO / f"voice_{i:02d}.wav"
        mp4 = SEGMENTS / f"segment_{i:02d}.mp4"
        script_lines.append(f"{i+1}. {title}: {voice}")
        has_audio = tts(voice, wav)
        if has_audio:
            cmd = ["ffmpeg", "-y", "-loop", "1", "-i", str(frame), "-i", str(wav), "-c:v", "libx264", "-tune", "stillimage", "-c:a", "aac", "-b:a", "128k", "-pix_fmt", "yuv420p", "-shortest", str(mp4)]
        else:
            cmd = ["ffmpeg", "-y", "-loop", "1", "-i", str(frame), "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100", "-t", "9", "-c:v", "libx264", "-c:a", "aac", "-pix_fmt", "yuv420p", "-shortest", str(mp4)]
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        segments.append(mp4)
    concat = OUT / "concat_video_real.txt"
    concat.write_text("\n".join([f"file '{str(p).replace(chr(92), '/')}'" for p in segments]), encoding="utf-8")
    final = OUT / "video_capacitacion_risek_vendedores_real.mp4"
    subprocess.run(["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", str(concat), "-c", "copy", str(final)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    (OUT / "guion_video_capacitacion_chileno.txt").write_text("\n\n".join(script_lines), encoding="utf-8")
    print(final)


if __name__ == "__main__":
    main()
