# Consumir el banco y el ERP con Feign

Regla del equipo y del docente: las llamadas a los sistemas externos se hacen con **clientes Feign de Spring Boot generados desde los specs del docente** (`library = spring-cloud`), no escritos a mano. Solo aplica al `pagos-consumer`.

> **Estado:** esta guía no está probada. El `pom.xml` actual del consumer compila y levanta sin Feign; no se modificó para no romperlo. Al seguir estos pasos, valida con `mvnw.cmd clean compile`.

## Lo que falta confirmar antes de empezar

**Qué versión de Spring Cloud va con Spring Boot 4.1.1.** No pude confirmarlo: la documentación que encontré no lo dice. Se revisa en la tabla "Release Train / Spring Boot Generation" de https://spring.io/projects/spring-cloud, o se le pregunta a Marvin, porque el repo `oficina-agua-rag` también usará Feign para llamar a Ollama. Si no existe un tren compatible, hablarlo con el docente antes de cambiar de enfoque.

## Pasos

### 1. Dependencias en `pagos-consumer/pom.xml`

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version><!-- versión confirmada arriba --></version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<!-- dentro de <dependencies> -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>
```

### 2. Generar un cliente por cada spec

Los specs ya están en `src/main/resources/openapi/` y **no se modifican**. Se agregan dos ejecuciones al `openapi-generator-maven-plugin` (el mismo plugin que usa el producer), cada una con su `<id>` y sus propios paquetes para que no choquen los nombres de clases de ambos specs:

| Ejecución | `inputSpec` | `apiPackage` / `modelPackage` |
|---|---|---|
| `banco` | `.../openapi/bank-payments-api.yaml` | `gt.edu.umg.aquatech.pagosconsumer.cliente.banco` (`.api` / `.model`) |
| `erp` | `.../openapi/erp-payments-api.yaml` | `gt.edu.umg.aquatech.pagosconsumer.cliente.erp` (`.api` / `.model`) |

En ambas: `generatorName = spring` y `library = spring-cloud`. El resultado son interfaces anotadas con `@FeignClient` en `target/generated-sources/openapi`.

**Revisa la clase generada:** el nombre exacto de las propiedades de configuración (las que fijan la URL de cada cliente) sale de su anotación `@FeignClient`. Úsalas tal cual en el `application.yaml`, apuntando a `BANK_BASE_URL` y `ERP_BASE_URL`.

### 3. Activar los clientes

En `PagosConsumerApplication` agrega `@EnableFeignClients(basePackages = "gt.edu.umg.aquatech.pagosconsumer.cliente")`, para que encuentre las interfaces generadas.

### 4. Configuración que debe cumplir el cliente

| Qué | Cómo |
|---|---|
| Timeouts del docente | conexión 2 s, lectura 3 s: `spring.cloud.openfeign.client.config.default.connect-timeout: 2000` y `read-timeout: 3000` |
| API key | un bean `feign.RequestInterceptor` que agregue el header de llave con `BANK_API_KEY` o `ERP_API_KEY` según el cliente; nunca en el código ni en el repo |
| Idempotencia del banco | el header `Idempotency-Key` es un parámetro del método generado: pasar `idempotencyKey` del ítem de `pagos-control` |
| Idempotencia del ERP | va en el cuerpo (`documento_origen.numero`), con el mismo valor |
| Reintentos | **no activar el `Retryer` de Feign.** Los reintentos los controla RabbitMQ (colas de espera y dead-letter), así cada intento queda en `pagos-logs` |

### 5. Traducir las respuestas a la política de reintentos

Feign lanza `FeignException` (con el código en `status()`) cuando el destino responde con error, y `RetryableException` ante timeouts o fallos de conexión. El consumer debe convertirlos así (la tabla completa está en [`diseno-control-pagos.md`](diseno-control-pagos.md), sección 6):

| Situación | Acción |
|---|---|
| 201 | Registrar el éxito en `pagos-logs` |
| 409 de duplicado | Tratar como éxito |
| 409 de llave reutilizada, 400, 403, 422 | Registrar el fallo y mandar a `pagos.dlq`, sin reintento |
| 429, 500, 502, 503, 504, timeout | Registrar el fallo y mandar a la cola de espera |

Para distinguir los dos `409` hay que leer el código de error del cuerpo de la respuesta, no solo el estado HTTP.
