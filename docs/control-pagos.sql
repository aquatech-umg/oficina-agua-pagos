-- =============================================================
-- CONTROL DE TRANSMISION DE PAGOS (AQ-98)
-- Esquema propio del servicio de pagos. NO modifica ninguna tabla
-- de oficina_agua (el monolito). Motor: MariaDB / InnoDB.
-- =============================================================

CREATE DATABASE IF NOT EXISTS `pagos_control`
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE `pagos_control`;

-- Una fila por cada (pago, destino). Un pago se entrega a dos destinos: BANCO y ERP.
CREATE TABLE IF NOT EXISTS `transmisiones` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `pago_id` BIGINT UNSIGNED NOT NULL COMMENT 'Id en oficina_agua.pagos. Sin FK a proposito: el monolito no se toca.',
  `destino` ENUM('BANCO','ERP') NOT NULL,
  `idempotency_key` VARCHAR(40) NOT NULL COMMENT 'Llave estable derivada del pago, ej. EQA-000123. Igual en todos los reintentos.',
  `payload` JSON NOT NULL COMMENT 'Cuerpo exacto que se envia al destino. Se guarda una sola vez para que los reintentos sean identicos.',
  `estado` ENUM('PENDIENTE','EN_COLA','ENVIADO','FALLIDO') NOT NULL DEFAULT 'PENDIENTE',
  `intentos` INT UNSIGNED NOT NULL DEFAULT 0,
  `ultimo_error` VARCHAR(100) NULL COMMENT 'Codigo del ultimo error del destino, ej. SERVICE_UNAVAILABLE.',
  `referencia_externa` VARCHAR(100) NULL COMMENT 'Comprobante devuelto por el destino (receiptId o numero_comprobante).',
  `encolado_en` DATETIME NULL,
  `ultimo_intento_en` DATETIME NULL,
  `enviado_en` DATETIME NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  CONSTRAINT `pk_transmisiones` PRIMARY KEY (`id`),
  -- Guarda principal contra duplicados: un pago solo puede tener una fila por destino.
  CONSTRAINT `uq_transmisiones_pago_destino` UNIQUE (`pago_id`, `destino`),
  CONSTRAINT `uq_transmisiones_destino_llave` UNIQUE (`destino`, `idempotency_key`),

  KEY `ix_transmisiones_estado` (`estado`, `encolado_en`),

  CONSTRAINT `ck_transmisiones_enviado_con_fecha`
    CHECK (`estado` <> 'ENVIADO' OR `enviado_en` IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================
-- Usuario del servicio: lee el monolito, escribe solo su propio control.
-- Cambiar la clave y restringir el host segun donde corra el servicio.
-- =============================================================
CREATE USER IF NOT EXISTS 'pagos_svc'@'%' IDENTIFIED BY 'CAMBIAR_ESTA_CLAVE';

GRANT SELECT ON oficina_agua.pagos        TO 'pagos_svc'@'%';
GRANT SELECT ON oficina_agua.recibos      TO 'pagos_svc'@'%';
GRANT SELECT ON oficina_agua.lecturas     TO 'pagos_svc'@'%';
GRANT SELECT ON oficina_agua.contadores   TO 'pagos_svc'@'%';
GRANT SELECT ON oficina_agua.clientes     TO 'pagos_svc'@'%';
GRANT SELECT ON oficina_agua.metodos_pago TO 'pagos_svc'@'%';

GRANT SELECT, INSERT, UPDATE ON pagos_control.* TO 'pagos_svc'@'%';

FLUSH PRIVILEGES;
