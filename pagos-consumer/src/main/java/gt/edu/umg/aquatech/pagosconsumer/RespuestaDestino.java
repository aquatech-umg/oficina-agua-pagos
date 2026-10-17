package gt.edu.umg.aquatech.pagosconsumer;

/**
 * Lo que devuelve un cliente de destino, ya sin tipos de Feign.
 *
 * @param httpStatus        código HTTP; 0 si hubo timeout o falla de conexión
 * @param codigoError       código de error del cuerpo (p. ej. DUPLICATE_PAYMENT), o null
 * @param referenciaExterna comprobante del destino cuando respondió 201, o null
 * @param cuerpo            cuerpo de la respuesta de error, para clasificar (puede ser null)
 */
public record RespuestaDestino(int httpStatus, String codigoError, String referenciaExterna, String cuerpo) {
}
