package gt.edu.umg.aquatech.pagosconsumer;

/** Puerto hacia un destino externo: envuelve el cliente Feign generado y no lanza excepciones de Feign. */
public interface DestinoClient {

    /** "BANCO" o "ERP". */
    String destino();

    /** Envía el payload tal cual (mismo cuerpo y misma llave en cada intento); no reintenta. */
    RespuestaDestino enviar(String payloadJson, String idempotencyKey);
}
