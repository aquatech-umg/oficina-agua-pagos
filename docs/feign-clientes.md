# Consumir el banco y el ERP con Feign

Las llamadas a los sistemas externos se hacen con **clientes Feign de Spring Boot generados desde los contratos del docente** (`library = spring-cloud`), en vez de escribirlos a mano. Solo aplica a `pagos-consumer`.

## Versión Confirmada

| Spring Boot | Spring Cloud | OpenFeign |
|---|---|---|
| 4.1.1 | **2025.1.3** (Oakwood) | 5.0.3 |

El anuncio de Spring Cloud 2025.1.3 dice que el tren es compatible con la línea 4.1.x: https://spring.io/blog/2026/08/20/spring-cloud-2025-1-3-has-been-released/

## Cómo está implementado

### 1. Generación de los clientes

Los contratos están en `pagos-consumer/src/main/resources/openapi/` y **no se modifican**. El `openapi-generator-maven-plugin` (versión 7.21.0, la misma del producer) tiene una ejecución por contrato:

| Ejecución | Contrato | Paquetes generados | Salida |
|---|---|---|---|
| `banco` | `bank-payments-api.yaml` | `...pagosconsumer.cliente.banco` (`.api`, `.model`, `.config`) | `target/generated-sources/banco` |
| `erp` | `erp-payments-api.yaml` | `...pagosconsumer.cliente.erp` (`.api`, `.model`, `.config`) | `target/generated-sources/erp` |

Opciones: `generatorName = spring`, `library = spring-cloud`, `useSpringBoot4`, `useJackson3`, `useTags`, `openApiNullable = false` y `documentationProvider = none`. Cada ejecución tiene su propio `configPackage`: sin eso los dos contratos generan la misma clase `ClientConfiguration` y chocan.

Clientes que se generan: `BankPaymentsApiClient`, `BankHealthApiClient`, `ErpPagosApiClient` y `ErpSaludApiClient`. Los dos primeros de cada contrato envían los pagos; los de salud no se usan en el flujo.

### 2. Configuración (`application.yaml`)

| Qué | Cómo |
|---|---|
| URL de cada cliente | `bankPayments.url`, `bankHealth.url`, `erpPagos.url`, `erpSalud.url`, tomadas de `BANK_BASE_URL` y `ERP_BASE_URL`. Los nombres de propiedad salen de la anotación `@FeignClient` generada |
| Nombre del cliente | `bankPayments.name` (y los otros tres) es **obligatorio**: el `contextId` generado lo usa por defecto y sin él el contexto de Spring no arranca |
| Timeouts | conexión 2 s y lectura 3 s para todos (`spring.cloud.openfeign.client.config.default`) |
| Campos opcionales | `spring.jackson.default-property-inclusion: non_null`: los simuladores responden `400 VALIDATION_ERROR` si un campo opcional (por ejemplo `payer.taxId`) viaja en `null` |
| Confirmaciones | `spring.rabbitmq.publisher-confirm-type: correlated`, necesario para publicar en las colas de espera y en `pagos.dlq` |

### 3. Autenticación

| Mecanismo | Variables | Detalle |
|---|---|---|
| `x-api-key` (lo que exige el contrato) | `BANK_API_KEY`, `ERP_API_KEY` | Lo agrega el interceptor que genera openapi-generator. Una variable vacía no envía el header |
| `Authorization: Basic` (opcional) | `SIMULADOR_USUARIO`, `SIMULADOR_PASSWORD` | Lo agrega `AutenticacionDestinosConfig`. Vacías: no se envía |

Ninguna llave va en el código ni en el repositorio.

### 4. Adaptadores

`DestinoClient` es el puerto hacia un destino. `BancoDestinoClient` y `ErpDestinoClient` lo implementan: convierten el payload guardado en `pagos-control` al modelo generado, llaman al cliente Feign y devuelven un `RespuestaDestino` sin tipos de Feign (`RespuestasFeign` hace esa traducción).

- **Idempotencia del banco:** el header `Idempotency-Key` es un parámetro del método generado; se pasa `idempotencyKey` del ítem.
- **Idempotencia del ERP:** va en el cuerpo (`documento_origen.numero`), con el mismo valor.
- **Payload inválido:** si el payload guardado no entra al modelo del contrato o no pasa su validación, se registra como `400 PAYLOAD_INVALIDO` y el ítem queda `FALLIDO` (ningún reintento lo arregla).
- **Escenarios del simulador:** si se definen `BANK_SIMULATION_SCENARIO` o `ERP_SIMULATION_SCENARIO`, el consumer envía el header `X-Simulation-Scenario`. Solo para pruebas.

### 5. Traducción de las respuestas

Feign lanza `FeignException` (estado en `status()`) cuando el destino responde con error, y `RetryableException` ante timeouts o fallos de conexión (estado 0). La tabla completa está en [`diseno-control-pagos.md`](diseno-control-pagos.md), sección 6.

| Situación | Acción |
|---|---|
| 201 | Registrar el éxito y el comprobante; el ítem pasa a `ENVIADO` |
| 409 de duplicado | Éxito: `ENVIADO`, con el comprobante original que viene en el cuerpo |
| 409 de llave reutilizada, 400, 403, 422 | Registrar el fallo, mandar a `pagos.dlq` y marcar `FALLIDO`, sin reintento |
| 429, 500, 502, 503, 504, timeout | Registrar el fallo y mandar a la cola de espera (respeta `Retry-After`) |

Para distinguir los dos `409` se lee el código de error del cuerpo, no solo el estado HTTP.

**Reintentos:** el `Retryer` de Feign **no se activa** (por defecto es `NEVER_RETRY`). Los reintentos los controla RabbitMQ con las colas de espera, así cada intento queda registrado en `pagos-logs`.
