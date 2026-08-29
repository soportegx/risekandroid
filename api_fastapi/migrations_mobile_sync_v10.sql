-- RISEK Android Offline v10
-- Ejecutar una vez sobre la base MySQL RISEK.
-- Objetivo: sincronización idempotente y correlativo NV seguro bajo concurrencia.

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
) ENGINE=InnoDB DEFAULT CHARSET=latin1;

CREATE TABLE IF NOT EXISTS nv_sequence (
  local_codigo CHAR(10) NOT NULL,
  ultimo_numero BIGINT(20) NOT NULL DEFAULT 0,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (local_codigo)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;

-- Inicialización segura por local ya existente en ventas NV.
INSERT INTO nv_sequence (local_codigo, ultimo_numero)
SELECT local_codigo, COALESCE(MAX(venta_numero), 0)
FROM ventas
WHERE venta_tipo = 'NV'
GROUP BY local_codigo
ON DUPLICATE KEY UPDATE ultimo_numero = GREATEST(nv_sequence.ultimo_numero, VALUES(ultimo_numero));

-- Recomendado para mejorar búsqueda posterior de NV móviles.
-- Si ya existe un índice equivalente, MySQL puede informar duplicado; no es crítico.
-- CREATE INDEX ix_ventas_nv_local_numero ON ventas (venta_tipo, local_codigo, venta_numero);
