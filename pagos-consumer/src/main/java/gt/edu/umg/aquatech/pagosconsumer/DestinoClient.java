package gt.edu.umg.aquatech.pagosconsumer;

/**
 * Puerto hacia un destino externo. Cada implementación envuelve el cliente Feign generado desde el
 * spec del docente (tarea de clientes del banco y el ERP) y devuelve un {@link RespuestaDestino} sin lanzar
 * excepciones de Feign ni de HTTP.
 */
public interface DestinoClient {

    /** "BANCO" o "ERP". */
    String destino();

    /**
     * Envía el payload tal cual (mismo cuerpo y misma llave en cada intento). No debe reintentar:
     * los reintentos los controla RabbitMQ para que cada intento quede en pagos-logs.
     */
    RespuestaDestino enviar(String payloadJson, String idempotencyKey);
}
