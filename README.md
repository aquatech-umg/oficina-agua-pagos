# oficina-agua-pagos

Feature 3 de la Fase 2 de Oficina del Agua: envío de pagos a sistemas externos (banco/ERP) mediante RabbitMQ, con reintento de los fallidos y sin duplicar pagos.

El monolito Laravel no se modifica. Este repo contiene servicios independientes que **leen** los pagos desde la réplica de solo lectura (`oficina-agua-infra`) y llevan su propio control de lo transmitido en DynamoDB.

## Requisitos

- JDK 21 (`java -version` debe mostrar 21)
- Maven 3.9+ (opcional: cada servicio trae su Maven Wrapper)
- Docker Desktop

## Convenciones (mismas del repo `oficina-agua-rag`)

- Spring Boot 4.1.1 con Java 21.
- `groupId`: `gt.edu.umg.aquatech`; paquete base del productor: `gt.edu.umg.aquatech.pagosproducer`.
- El contrato vive en `src/main/resources/static/openapi/` y Swagger UI lo sirve desde ahí.
- Para generar código desde el contrato se usa `openapi-generator-maven-plugin` con `useSpringBoot4` y `useJackson3`, igual que el pom de `oficina-agua-rag/rag-service`.

## Estructura

```
oficina-agua-pagos/
├── README.md
├── docker-compose.yml          RabbitMQ y DynamoDB Local (control y logs de pagos)
├── .env.example                Variables de ejemplo (copiar a .env)
├── docs/
│   ├── diseno-control-pagos.md  Diseño del control de pagos (AQ-98)
│   └── feign-clientes.md        Cómo consumir el banco y el ERP con Feign
├── pagos-producer/             Spring Boot: toma pagos pendientes y los publica en la cola
│   ├── pom.xml
│   ├── mvnw, mvnw.cmd, .mvn/   Maven Wrapper (no hace falta instalar Maven)
│   └── src/
│       ├── main/java/gt/edu/umg/aquatech/pagosproducer/
│       │   ├── PagosProducerApplication.java
│       │   └── SaludController.java
│       ├── main/resources/
│       │   ├── application.yaml
│       │   └── openapi/payments-api.yaml      Contrato propio (contract-first)
│       └── test/java/gt/edu/umg/aquatech/pagosproducer/
└── pagos-consumer/             Spring Boot: toma pagos de la cola y los entrega al banco y al ERP
    ├── pom.xml
    ├── mvnw, mvnw.cmd, .mvn/
    └── src/
        ├── main/java/gt/edu/umg/aquatech/pagosconsumer/
        ├── main/resources/
        │   ├── application.yaml
        │   └── openapi/bank-payments-api.yaml, erp-payments-api.yaml   (del docente, sin modificar)
        └── test/java/gt/edu/umg/aquatech/pagosconsumer/
```

Misma plantilla que `oficina-agua-rag/rag-service`: Spring Boot 4.1.1, Java 21, springdoc 3.0.2 y `openapi-generator-maven-plugin` 7.21.0.

## Cómo correr los servicios

Con RabbitMQ arriba (`docker compose up -d`), desde la carpeta de cada servicio:

```bash
./mvnw spring-boot:run          # en Windows: mvnw.cmd spring-boot:run
```

| Servicio | Puerto | Comprobación |
|---|---|---|
| pagos-producer | 8083 | http://localhost:8083/api/salud |
| pagos-consumer | 8084 | http://localhost:8084/api/salud |
| RabbitMQ (AMQP / web) | 5672 / 15672 | http://localhost:15672 |
| DynamoDB Local (pagos) | 8012 | `aws dynamodb list-tables --endpoint-url http://localhost:8012` |

`/api/salud` abre una conexión real contra RabbitMQ y devuelve su versión; si RabbitMQ está apagado, falla. Las credenciales se toman de `RABBITMQ_USER` y `RABBITMQ_PASSWORD` (por defecto `admin` / `changeme`, igual que el compose).

El contrato del producer genera interfaces Java al compilar (`./mvnw clean compile`); quedan en `target/generated-sources/openapi`.

## Flujo (según el diagrama del ingeniero)

1. El **producer** lee los pagos de la **réplica de solo lectura**, crea el control de cada entrega en DynamoDB (`pagos-control`) y publica un mensaje por destino en la **cola**.
2. El **consumer** toma cada pago de la cola y lo entrega a **dos destinos**: el **banco** y el **ERP municipal**.
3. Cada intento (exitoso o fallido) se registra en `pagos-logs`; una función aparte (Lambda en AWS, proceso programado en local) marca el pago como `ENVIADO` para que no se vuelva a enviar.

Diseño completo en [`docs/diseno-control-pagos.md`](docs/diseno-control-pagos.md). Por confirmar con el docente: dónde vive la bandera (la propuesta la deja en DynamoDB porque la réplica es de solo lectura).

## Destinos externos (specs del docente)

Los specs `bank-payments-api.yaml` y `erp-payments-api.yaml` están en `pagos-consumer/src/main/resources/openapi/`. **No se modifican**: el docente los entrega como contrato, y con ellos se genera un cliente Feign con `openapi-generator-maven-plugin` (`library = spring-cloud`).

El banco y el ERP no piden el mismo formato a propósito:

| | Banco | ERP |
|---|---|---|
| Nombres | inglés, camelCase | español, snake_case |
| Unicidad del pago | header `Idempotency-Key` | `documento_origen.numero` en el body |
| Monto | entero en centavos (`15075`) | texto con 2 decimales (`"150.75"`) |
| Período | no lo pide | objeto `{ anio, mes }` |
| Detalle | no | líneas por rubro; la suma debe cuadrar con `total` |

La llave de unicidad debe ser **estable**, derivada del pago del monolito (por ejemplo `EQA-000123`): todos los reintentos del mismo pago usan la misma llave.

### Qué hacer según la respuesta

| Respuesta | Acción |
|---|---|
| 201 | Entregado. Guardar el comprobante. |
| 409 `DUPLICATE_PAYMENT` / `DOCUMENTO_DUPLICADO` | Ya estaba entregado: tratar como éxito. |
| 409 `IDEMPOTENCY_KEY_REUSED` / `DOCUMENTO_REUTILIZADO` | Error del productor: dead-letter, no reintentar. |
| 429, 500, 502, 503, 504, timeout | Reintentar con backoff (respetar `Retry-After`). |
| 400, 403, 422 | No reintentar: dead-letter. |

Timeouts recomendados por el docente: connect 2 s, read 3 s. Las API keys y las URLs van en variables de entorno (ver `.env.example`), nunca en el repo.

## Endpoints del contrato

| Método | Ruta | Respuesta |
|---|---|---|
| POST | `/api/v1/payments/dispatch` | 202 / 400 |
| GET | `/api/v1/payments/transmissions?status=&destination=` | 200 / 400 |
| GET | `/api/v1/payments/transmissions/{paymentId}` | 200 / 404 |
| POST | `/api/v1/payments/transmissions/{paymentId}/retry` | 202 / 404 / 409 |

## Regla principal: primero el contrato

El YAML de OpenAPI se escribe y se revisa antes que el código. El código Java se genera/implementa a partir del contrato con `openapi-generator-maven-plugin`, nunca al revés.

El spec del banco y del ERP lo entrega el ingeniero: no se diseña aquí, se copia tal cual y se usa para generar el cliente Feign (ver *Destinos externos*).

## Cómo levantar RabbitMQ

Requiere Docker Desktop.

```bash
cp .env.example .env
# cambia RABBITMQ_PASSWORD en .env
docker compose up -d
docker compose ps
```

Interfaz de administración: http://localhost:15672

Para probar que un mensaje viaja: crea una cola `cola-prueba` en la pestaña **Queues and Streams**, publica un mensaje desde **Publish message** y léelo con **Get messages**.

## Flujo de trabajo

- Una rama por tarjeta: `feature/AQ-XX-descripcion`.
- Commits con la clave al inicio: `AQ-XX: mensaje`.
- Nada directo a `main`: todo por pull request, con la clave en el título.
