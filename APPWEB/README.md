# RISEK APPWEB - Picking

Modulo web independiente para generar picking desde facturas/boletas emitidas.

## Ejecutar

1. Configure variables de base de datos en `.env` o use las mismas del entorno:

```env
DB_HOST=127.0.0.1
DB_PORT=3306
DB_USER=root
DB_PASSWORD=
DB_NAME=risek
```

2. Instale dependencias:

```powershell
pip install -r requirements.txt
```

3. Levante el servidor:

```powershell
python -m uvicorn app.main:app --app-dir . --host 127.0.0.1 --port 9100 --reload
```

4. Abra:

```text
http://localhost:9100
```

## Endpoints

- `GET /api/health`
- `GET /api/rutas`
- `GET /api/vendedores`
- `GET /api/picking/resumen?fecha=YYYY-MM-DD&ruta_id=&vendedor_codigo=`
- `GET /api/picking/pdf?fecha=YYYY-MM-DD&ruta_id=&vendedor_codigo=`

El picking considera documentos `FE`, `FA` y `BO`, agrupados por producto.
