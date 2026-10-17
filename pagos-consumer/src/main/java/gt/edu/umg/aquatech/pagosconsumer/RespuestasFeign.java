package gt.edu.umg.aquatech.pagosconsumer;

import feign.FeignException;
import feign.RetryableException;

/** Convierte lo que lanza o devuelve un cliente Feign en un {@link RespuestaDestino} sin tipos de Feign. */
final class RespuestasFeign {

    private RespuestasFeign() {
    }

    static RespuestaDestino exitosa(int httpStatus, String referenciaExterna) {
        return new RespuestaDestino(httpStatus, null, referenciaExterna, null);
    }

    /**
     * RetryableException es timeout o falla de conexión (estado 0, reintentable). El resto de las
     * FeignException trae el estado HTTP y el cuerpo: el cuerpo se guarda porque ahí viene el código
     * de error que distingue los dos 409 (duplicado = entregado, llave reutilizada = fallo).
     */
    static RespuestaDestino deExcepcion(FeignException e) {
        if (e instanceof RetryableException || e.status() < 0) {
            return new RespuestaDestino(0, null, null, e.getMessage());
        }
        String cuerpo = e.contentUTF8();
        return new RespuestaDestino(e.status(), ClasificadorRespuesta.extraerCodigo(cuerpo), null, cuerpo);
    }

    /**
     * El payload guardado no se puede convertir al modelo del contrato, o no pasa su validación.
     * Es un error del productor que ningún reintento arregla: se registra como 400 propio (no lo dijo
     * el destino; lo marca el código PAYLOAD_INVALIDO) y el ítem queda FALLIDO.
     */
    static RespuestaDestino payloadInvalido(RuntimeException e) {
        return new RespuestaDestino(400, "PAYLOAD_INVALIDO", null, e.getMessage());
    }
}
