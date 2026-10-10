# Diseño del control de pagos pendientes y transmitidos (AQ-98)

Feature 3 · Envío de pagos a sistemas externos. Define la colección de control, los estados de un pago y cómo se evita enviar el mismo pago dos veces. **El monolito no se modifica.**

## 1. Resumen

- Cada pago registrado en el monolito se entrega a **dos destinos**: el banco y el ERP. Cada entrega se controla por separado.
- El control vive en **DynamoDB**, en dos tablas: `pagos-control` (estado actual de cada entrega) y `pagos-logs` (un registro por cada intento, exitoso o fallido).
- Del monolito solo se **lee**, desde la réplica de solo lectura. No se escribe nada en MySQL.
- **La idempotencia es por destino:** un mismo pago no se envía dos veces al mismo destino, aunque el mensaje se reprocese (sección 5).
- **Si un simulador está caído, el pago no se pierde:** queda reintentando o pasa a la cola de mensajes fallidos (*dead-letter queue*). Ambos casos están en la sección 6.

## 2. Dónde vive el control

El diagrama del docente dibuja la bandera de "pago enviado" en MySQL. El diagrama del equipo y la infraestructura de Marvin lo impiden:

- `db-replica` corre con `--read-only=1` y el usuario de los microservicios solo tiene `SELECT`.
- `db-primary` es la base del monolito, y el README de `oficina-agua-infra` dice que los microservicios **nunca escriben** ahí.
- El diagrama del equipo ya incluye DynamoDB Local en las features 2 y 4, y deja en la feature 3 la nota "registro de pagos exitosos/fallidos y marca de pagos enviados".

| Opción | Cambia el monolito | Evaluación |
|---|---|---|
| A. Columna nueva en `oficina_agua.pagos` | Sí | Choca con la regla y con la réplica de solo lectura |
| B. Esquema aparte en MySQL | Escribe en el servidor del monolito | Choca con "nunca escriben"; la réplica no acepta escrituras |
| **C. DynamoDB** | **No** | **Propuesta.** Cumple todas las reglas y encaja con el diagrama del equipo |

> **Por confirmar con el docente:** dónde vive la bandera. Esta propuesta la pone en DynamoDB (`pagos-control`) en vez de MySQL. Si el docente exige MySQL, haría falta una columna por destino en `pagos` y un cambio de esquema que él debe autorizar; la idempotencia y los reintentos no cambian.

## 3. Colección de control (DynamoDB)

DynamoDB Local en el puerto **8012** (las features 2 y 4 usan 8010 y 8011). Las tablas se crean solas con el servicio `dynamodb-pagos-init` del `docker-compose.yml`.

### `pagos-control`: estado actual por (pago, destino)

Clave de partición: `pagoDestino` (String), por ejemplo `EQA-000123#BANCO`. Sin clave de ordenamiento: una sola fila por pago y destino.

| Atributo | Tipo | Para qué sirve |
|---|---|---|
| `pagoDestino` | S | Clave: llave de idempotencia + `#` + destino |
| `pagoId` | N | Id en `oficina_agua.pagos` |
| `destino` | S | `BANCO` o `ERP` |
| `idempotencyKey` | S | Llave estable, p. ej. `EQA-000123` |
| `payload` | S | Cuerpo exacto a enviar (JSON como texto); se guarda una vez |
| `estado` | S | `PENDIENTE`, `EN_COLA`, `ENVIADO`, `FALLIDO` |
| `intentos` | N | Cantidad de intentos de entrega |
| `ultimoError` | S | Código del último error del destino |
| `referenciaExterna` | S | Comprobante que devolvió el destino |
| `creadoEn`, `actualizadoEn`, `encoladoEn`, `enviadoEn` | S | Fechas ISO 8601 |

Índice secundario `estado-actualizadoEn-index` (partición `estado`, orden `actualizadoEn`): sirve para encontrar los `PENDIENTE` y los `EN_COLA` atascados sin recorrer toda la tabla.

Un ítem especial `pagoDestino = "_CURSOR"` guarda `ultimoPagoId`, el último pago del monolito ya procesado. No tiene atributo `estado`, así que no aparece en el índice.

### `pagos-logs`: un registro por intento

Clave de partición `pagoDestino` (S), clave de ordenamiento `intento` (N). Atributos: `resultado` (`EXITOSO` o `FALLIDO`), `httpStatus`, `codigoError`, `reintentable` (BOOL), `referenciaExterna`, `duracionMs`, `registradoEn`.

Es el "DynamoDB Logs" del diagrama: solo se agrega, nunca se modifica.

## 4. Estados de un pago

```mermaid
stateDiagram-v2
    [*] --> PENDIENTE: el producer detecta un pago nuevo
    PENDIENTE --> EN_COLA: reclamado y publicado en RabbitMQ
    EN_COLA --> ENVIADO: el destino responde 201 o 409 de duplicado
    EN_COLA --> FALLIDO: error sin reintento o se agotan los reintentos
    EN_COLA --> EN_COLA: sin resultado tras 15 min, se vuelve a publicar
    FALLIDO --> PENDIENTE: reintento manual (POST /retry)
    ENVIADO --> [*]
```

| Estado | Significado |
|---|---|
| `PENDIENTE` | Existe el ítem pero todavía no se publicó en la cola |
| `EN_COLA` | Está en manos de RabbitMQ y del consumer (incluye los reintentos con espera) |
| `ENVIADO` | El destino confirmó la recepción. Estado final |
| `FALLIDO` | Rechazo definitivo o reintentos agotados. Solo sale con un reintento manual |

El pago está completamente transmitido cuando sus dos ítems están en `ENVIADO`; eso se calcula, no se guarda.

**Quién escribe qué** (cada transición tiene un único responsable):

| Actor | Escribe |
|---|---|
| Producer | Crea los ítems `PENDIENTE`; pasa a `EN_COLA` al publicar; vuelve a publicar los `EN_COLA` vencidos |
| Consumer | Registra cada intento en `pagos-logs`; actualiza `intentos` y `ultimoError`; marca `FALLIDO` |
| Función aparte: Lambda en AWS, proceso programado en local | Toma los éxitos de `pagos-logs` y pasa `EN_COLA` → `ENVIADO` (la marca de "pago enviado" del diagrama) |
| API `/retry` | `FALLIDO` → `PENDIENTE` |

Todos los cambios de estado son escrituras condicionales: por ejemplo, pasar a `ENVIADO` solo funciona si el estado actual es `EN_COLA`.

**La función aparte, en local:** cada 30 segundos consulta el índice por `estado = EN_COLA`; para cada ítem busca en `pagos-logs` un registro `EXITOSO` y, si existe, lo pasa a `ENVIADO` guardando `referenciaExterna` y `enviadoEn`. En AWS lo mismo lo haría una Lambda activada por DynamoDB Streams sobre `pagos-logs`.

## 5. Cómo se evita enviar el mismo pago dos veces

**La regla es por destino:** el pago 123 puede ir una vez al banco y una vez al ERP, pero nunca dos veces al mismo. Por eso la unidad de control es la pareja (pago, destino).

RabbitMQ garantiza entrega **al menos una vez**, así que un mensaje repetido es posible. **Caso "el mensaje se reprocesa":** el consumer recibe otra vez el mensaje de un pago ya entregado. Antes de llamar al destino lee su ítem en `pagos-control`: si está en `ENVIADO`, descarta el mensaje con *ack* sin llamar a nadie. Si por una carrera igual llega a llamar, el destino responde `409` de duplicado y se trata como éxito. Las capas son:

1. **Un ítem por (pago, destino).** Se crea con `PutItem` y la condición `attribute_not_exists(pagoDestino)`: si ya existe, falla sin duplicar.
2. **Reclamar antes de publicar.** Solo se publican los `PENDIENTE`, y el paso a `EN_COLA` es un `UpdateItem` con la condición `estado = PENDIENTE`. Si dos corridas coinciden, solo una gana cada ítem.
3. **Llave de idempotencia estable.** `<EQUIPO>-<pago_id con 6 dígitos>` (p. ej. `EQA-000123`), calculada una vez y reutilizada en todos los reintentos. Nunca se genera una llave nueva por intento.
4. **Cuerpo idéntico en cada reintento.** El banco y el ERP responden `409` con `IDEMPOTENCY_KEY_REUSED` / `DOCUMENTO_REUTILIZADO` si la misma llave llega con datos distintos. Por eso el cuerpo se guarda en `payload` al crear el ítem y los reintentos lo envían tal cual, aunque el pago cambie después. La fecha enviada es `pagos.fecha_pago`, nunca la hora actual.
5. **Los destinos también son idempotentes.** Un `409` con `DUPLICATE_PAYMENT` / `DOCUMENTO_DUPLICADO` (mismos datos) significa que ya estaba entregado: se trata como éxito.

El registro de cada intento en `pagos-logs` es también idempotente: se escribe con la condición `attribute_not_exists(pagoDestino)` sobre la clave (`pagoDestino`, `intento`), así que reprocesar el mismo intento no crea un registro doble.

En RabbitMQ: cola durable, mensajes persistentes, *publisher confirms* en el producer y *ack* manual en el consumer solo después de registrar el resultado.

## 6. Reintentos y errores

Reglas tomadas de los specs del docente (iguales para el banco y el ERP):

| Respuesta | Qué hace el consumer | Estado |
|---|---|---|
| 201 | Registra el éxito y el comprobante | `ENVIADO` (lo confirma la función aparte) |
| 409 duplicado | Ya estaba entregado | `ENVIADO` |
| 409 llave reutilizada | Error del productor, no reintenta; dead-letter | `FALLIDO` |
| 400, 403, 422 | No reintenta; dead-letter | `FALLIDO` |
| 429, 500, 502, 503, 504, timeout | Reintenta con espera creciente; respeta `Retry-After` si viene | `EN_COLA` |

Espera propuesta: 5 intentos en total, con esperas de 2, 4, 8 y 16 segundos más un poco de variación aleatoria. Timeouts: conexión 2 s, lectura 3 s. El `429` del gateway puede venir sin `Retry-After`; en ese caso se usa la espera propia.

Hay una cola por destino (`pagos.banco`, `pagos.erp`) y un mensaje por destino, para que la caída de uno no frene al otro.

### Si un simulador está caído: el pago no se pierde

La cola es el seguro: el mensaje no se descarta hasta que el destino responde o se manda a la cola de fallidos. Hay dos casos, y el pago termina en uno de los dos.

**Caso 1: la caída es corta, queda reintentando.**
- El destino responde `503`, `504`, `429` o no contesta (timeout).
- El consumer no da el mensaje por entregado: lo manda a una cola de espera (`pagos.banco.retry` o `pagos.erp.retry`) con un retardo creciente, y vuelve a la cola principal.
- El ítem sigue en `EN_COLA`, con `intentos` y `ultimoError` actualizados y un registro `FALLIDO` en `pagos-logs` por cada vuelta.
- Si el destino se recupera dentro de los intentos permitidos, responde `201` y el pago termina en `ENVIADO`.

**Caso 2: la caída es larga o el error es definitivo, va a la cola de fallidos.**
- Se agotan los 5 intentos, o llega un error que no se reintenta (`400`, `403`, `422`, llave reutilizada).
- El mensaje pasa a la *dead-letter queue* (`pagos.dlq`) con el motivo, y el ítem queda en `FALLIDO`.
- **El pago no se pierde:** sigue en `pagos-control` con su `payload` y su llave intactos. Cuando el simulador vuelva, `POST /api/v1/payments/transmissions/{paymentId}/retry` lo pasa a `PENDIENTE` y se reenvía con la misma llave, sin riesgo de duplicado.
- Los errores definitivos del tipo `422` no se arreglan reintentando: hay que corregir el dato y recién entonces reintentar.

## 7. Cómo encuentra el producer lo pendiente

1. **Pagos nuevos, desde la réplica de solo lectura** (`localhost:3307`, o `db-replica:3306` dentro de Docker):
   ```sql
   SELECT p.id, p.monto, p.fecha_pago, p.referencia,
          m.nombre AS metodo_pago,
          r.numero_recibo, r.monto AS monto_recibo,
          l.periodo,
          c.numero_registro,
          cl.id AS cliente_id, cl.nombre AS cliente
   FROM pagos p
   JOIN recibos r       ON r.id = p.recibo_id
   JOIN lecturas l      ON l.id = r.lectura_id
   JOIN contadores c    ON c.id = l.contador_id
   JOIN clientes cl     ON cl.id = c.cliente_id
   JOIN metodos_pago m  ON m.id = p.metodo_pago_id
   WHERE p.id > :ultimoPagoId AND p.fecha_pago >= :desde
   ORDER BY p.id
   LIMIT :limite;
   ```
   La consulta retrocede unos 100 ids bajo el cursor para no saltarse pagos que se confirmen tarde en la réplica; los repetidos se descartan en el paso siguiente.
2. **Crear el control:** por cada pago, el producer arma los dos `payload` y crea los dos ítems (`BANCO` y `ERP`) con `PutItem` condicional (o una transacción de dos escrituras). Después actualiza `_CURSOR`.
3. **Reclamar y publicar:** consulta el índice por `estado = PENDIENTE`, pasa cada ítem a `EN_COLA` con la condición y publica el mensaje `{ pagoId, destino, idempotencyKey }` con confirmación.
4. **Recuperar atascados:** los `EN_COLA` con `actualizadoEn` de hace más de 15 minutos se vuelven a publicar. Es seguro por las capas 3 a 5.

Si el producer se cae después de reclamar y antes de publicar, el ítem queda `EN_COLA` sin mensaje: el paso 4 lo recupera. Si publica dos veces, el mensaje se duplica pero el pago no, por la capa 5.

## 8. Datos que se envían

| Campo del destino | Origen en el monolito | Nota |
|---|---|---|
| Banco `amount.valueInCents` | `pagos.monto` × 100 | Con `BigDecimal`, nunca `double` |
| ERP `total` y `detalle[].monto` | `pagos.monto` como texto con 2 decimales | `detalle` debe sumar el `total` |
| ERP rubro `AGUA_POTABLE` | `recibos.monto` | |
| ERP rubro `MORA` | `pagos.monto` − `recibos.monto` | Solo si es mayor que cero |
| Fecha de pago | `pagos.fecha_pago` | Enviar con zona horaria de Guatemala (−06:00) |
| Canal (banco) / forma de pago (ERP) | `metodos_pago.nombre` | Tabla de equivalencias en configuración |
| ERP `contribuyente.codigo_contador` | `contadores.numero_registro` | |
| ERP `periodo_fiscal` | `lecturas.periodo` (año y mes) | |
| Llave / `documento_origen.numero` | `idempotencyKey` | Cumple el patrón `^[A-Za-z0-9_-]+$` |

Equivalencias de forma de pago propuestas: `EFECTIVO` → `CASH`, `TARJETA` → `CARD`, `TRANSFERENCIA` → `BANK_TRANSFER`, `CHEQUE` → `CHECK`.

## 9. Decisiones abiertas

Las propongo yo y conviene confirmarlas con Marvin o el docente:

1. **Dónde vive la bandera (por confirmar con el docente, según Jira).** Propuesta: DynamoDB, porque la réplica es de solo lectura y los microservicios no escriben en la base del monolito. El diagrama del docente la dibuja en MySQL.
2. **Pagos con cheque:** el banco responde `422 UNSUPPORTED_CHANNEL` a `CHECK`. ¿Se omite el banco para esos pagos o se dejan en `FALLIDO`?
3. **Límites del banco:** más de Q25,000.00 por pago o una fecha más de 5 minutos en el futuro dan `422`. Esos pagos terminarían en `FALLIDO`.
4. **Pagos antiguos:** el ERP rechaza períodos con más de 12 meses (`PERIODO_CERRADO`). Por eso existe el parámetro `:desde`; para la demo, usar datos recientes.
5. **Códigos de cliente** (`customerCode`, `codigo_cliente`): el monolito no tiene un código con ese formato. Propuesta: `CLI-` más el id con 4 dígitos.
6. **Datos personales:** el DPI es opcional en ambos destinos (`taxId`, `nit`). Propongo no enviarlo.
7. **`documento_origen.tipo` del ERP** (`RECIBO_CAJA`, `BOLETA_DEPOSITO`, `VOUCHER_TARJETA`): por los nombres, `EFECTIVO` → `RECIBO_CAJA`, `TRANSFERENCIA` → `BOLETA_DEPOSITO`, `TARJETA` → `VOUCHER_TARJETA`. Falta definir el caso `CHEQUE`.
8. **Versión de Spring Cloud para Feign:** hay que confirmar cuál es compatible con Spring Boot 4.1.1 (ver [`feign-clientes.md`](feign-clientes.md)).

## 10. Configuración local

Variables de entorno (ver `.env.example`):

| Variable | Valor local |
|---|---|
| `DYNAMODB_ENDPOINT` | `http://localhost:8012` (dentro de Docker: `http://dynamodb-pagos:8000`) |
| `DYNAMODB_TABLE_CONTROL` | `pagos-control` |
| `DYNAMODB_TABLE_LOGS` | `pagos-logs` |
| `AWS_REGION` | `us-east-1` |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | `local` (solo para desarrollo) |
| `DB_REPLICA_HOST`, `DB_REPLICA_PORT` | `localhost`, `3307` |
| `DB_LECTURA_USER`, `DB_LECTURA_PASSWORD` | Las de `oficina-agua-infra/.env` |

En AWS se omite `DYNAMODB_ENDPOINT` y se usan las credenciales de la cuenta.
