package gt.edu.umg.aquatech.pagosconsumer;

/**
 * Respuesta de un destino sin tipos de Feign. {@code httpStatus} es 0 si hubo timeout o falla de conexión;
 * {@code referenciaExterna} es el comprobante (201 o 409 de duplicado).
 */
public record RespuestaDestino(int httpStatus, String codigoError, String referenciaExterna, String cuerpo,
                               Integer retryAfterSegundos) {

    public RespuestaDestino(int httpStatus, String codigoError, String referenciaExterna, String cuerpo) {
        this(httpStatus, codigoError, referenciaExterna, cuerpo, null);
    }
}
