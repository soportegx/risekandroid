-- Ejecutar una sola vez en la base RISEK antes de sincronizar desde Android.
-- Idempotencia server-side: evita duplicar NV aunque el teléfono reenvíe el mismo offline_id.
CREATE TABLE IF NOT EXISTS mobile_sync_log (
  offline_id VARCHAR(80) NOT NULL,
  venta_numero BIGINT(20) NOT NULL,
  local_codigo CHAR(10) NOT NULL,
  cliente_rut CHAR(10) NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (offline_id),
  KEY ix_mobile_sync_venta (venta_numero, local_codigo)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;

-- Opcional recomendado, si quieres rastrear el offline_id dentro de ventas.
-- ALTER TABLE ventas ADD COLUMN offline_id VARCHAR(80) NULL;
-- CREATE UNIQUE INDEX ux_ventas_offline_id ON ventas(offline_id);
