# Consumir el banco y el ERP con Feign

Regla del equipo y del docente: las llamadas a los sistemas externos se hacen con **clientes Feign de Spring Boot**. Para el banco y el ERP, el docente pide generarlos desde sus specs (`library = spring-cloud`) en vez de escribirlos a mano. Solo aplica al `pagos-consumer`.

## Versión confirmada (AQ-111)

| Spring Boot | Spring Cloud | OpenFeign |
|---|---|---|
| 4.1.1 | **2025.1.3** (Oakwood) | 5.0.3 |

- El anuncio de Spring Cloud 2025.1.3 dice que el tren está basado en Boot 4.0.x y es compatible con la línea 4.1.x: https://spring.io/blog/2026/08/20/spring-cloud-2025-1-3-has-been-released/
- Según Marvin, hace falta la **2025.1.2 o una más nueva**: la 2025.1.1 falla con Boot 4.1. Ese dato sale de su prueba, no del anuncio.
- Marvin lo probó en `rag-service`, que compila y llama a Ollama. Su documentación está en `oficina-agua-rag`, archivo `rag-service/docs/feign-ollama.md` (PR #13).

## Qué ya está aplicado en `pagos-consumer`

Es el mismo patrón que compiló Marvin:

| Archivo | Cambio |
|---|---|
| `pom.xml` | Propiedad `spring-cloud.version` = `2025.1.3`; BOM `spring-cloud-dependencies` en `dependencyManagement`; dependencia `spring-cloud-starter-openfeign` |
| `PagosConsumerApplication.java` | Anotación `@EnableFeignClients` (escanea el paquete base y sus subpaquetes) |
| `application.yaml` | Timeouts del docente para todos los clientes: conexión 2 s y lectura 3 s (`spring.cloud.openfeign.client.config.default`) |

Para comprobarlo, desde `pagos-consumer`: `.\mvnw.cmd clean compile` y luego `.\mvnw.cmd spring-boot:run`.

## Pendiente: generar los clientes desde los specs (sin probar)

Marvin escribió su cliente de Ollama a mano. Para el banco y el ERP el docente pide generarlos, y esa configuración **no la probó nadie todavía**. Valida cada paso con `mvnw.cmd clean compile`.

### 1. Una ejecución del generador por cada spec

Los specs ya están en `src/main/resources/openapi/` y **no se modifican**. Se agregan dos ejecuciones al `openapi-generator-maven-plugin` (el mismo plugin y versión 7.21.0 que usa el producer), cada una con su `<id>` y sus propios paquetes, para que no choquen los nombres de clases de ambos specs:

| Ejecución | `inputSpec` | `apiPackage` / `modelPackage` |
|---|---|---|
| `banco` | `.../openapi/bank-payments-api.yaml` | `gt.edu.umg.aquatech.pagosconsumer.cliente.banco` (`.api` / `.model`) |
| `erp` | `.../openapi/erp-payments-api.yaml` | `gt.edu.umg.aquatech.pagosconsumer.cliente.erp` (`.api` / `.model`) |

En ambas: `generatorName = spring` y `library = spring-cloud`. El resultado son interfaces anotadas con `@FeignClient` en `target/generated-sources/openapi`.

**Revisa la clase generada:** de su anotación `@FeignClient` salen el nombre del cliente y la propiedad que fija su URL. Usa esa propiedad tal cual en el `application.yaml`, apuntando a `BANK_BASE_URL` y `ERP_BASE_URL`. Si el generador no es compatible con las opciones `useSpringBoot4` y `useJackson3` del producer con esta biblioteca, probar sin ellas y revisar los imports generados.

### 2. Configuración que debe cumplir el cliente

| Qué | Cómo |
|---|---|
| API key | un bean `feign.RequestInterceptor` que agregue el header de llave con `BANK_API_KEY` o `ERP_API_KEY` según el cliente; nunca en el código ni en el repo |
| Idempotencia del banco | el header `Idempotency-Key` es un parámetro del método generado: pasar `idempotencyKey` del ítem de `pagos-control` |
| Idempotencia del ERP | va en el cuerpo (`documento_origen.numero`), con el mismo valor |
| Reintentos | **no activar el `Retryer` de Feign.** Los reintentos los controla RabbitMQ (colas de espera y dead-letter), así cada intento queda en `pagos-logs` |

### 3. Traducir las respuestas a la política de reintentos

Feign lanza `FeignException` (con el código en `status()`) cuando el destino responde con error, y `RetryableException` ante timeouts o fallos de conexión. El consumer debe convertirlos así (la tabla completa está en [`diseno-control-pagos.md`](diseno-control-pagos.md), sección 6):

| Situación | Acción |
|---|---|
| 201 | Registrar el éxito en `pagos-logs` |
| 409 de duplicado | Tratar como éxito |
| 409 de llave reutilizada, 400, 403, 422 | Registrar el fallo y mandar a `pagos.dlq`, sin reintento |
| 429, 500, 502, 503, 504, timeout | Registrar el fallo y mandar a la cola de espera |

Para distinguir los dos `409` hay que leer el código de error del cuerpo de la respuesta, no solo el estado HTTP.
