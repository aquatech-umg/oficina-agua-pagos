package gt.edu.umg.aquatech.pagosconsumer;

import feign.FeignException;
import feign.RetryableException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Convierte lo que lanza o devuelve un cliente Feign en un {@link RespuestaDestino} sin tipos de Feign. */
final class RespuestasFeign {

    private static final Pattern REFERENCIA =
            Pattern.compile("\"(?:receiptId|numero_comprobante)\"\\s*:\\s*\"([^\"]+)\"");

    private RespuestasFeign() {
    }

    static RespuestaDestino exitosa(int httpStatus, String referenciaExterna) {
        return new RespuestaDestino(httpStatus, null, referenciaExterna, null);
    }

    /**
     * Un timeout o falla de conexión es estado 0 (reintentable). Del resto se conserva el estado y el cuerpo,
     * donde viene el código que distingue los dos 409.
     */
    static RespuestaDestino deExcepcion(FeignException e) {
        if (e instanceof RetryableException || e.status() < 0) {
            return new RespuestaDestino(0, null, null, e.getMessage());
        }
        String cuerpo = e.contentUTF8();
        String codigo = ClasificadorRespuesta.extraerCodigo(cuerpo);
        return new RespuestaDestino(e.status(), codigo, referenciaDelDuplicado(codigo, cuerpo), cuerpo, retryAfter(e));
    }

    /** En un 409 de duplicado el destino devuelve el comprobante original (receiptId o numero_comprobante). */
    static String referenciaDelDuplicado(String codigo, String cuerpo) {
        if (cuerpo == null || !("DUPLICATE_PAYMENT".equals(codigo) || "DOCUMENTO_DUPLICADO".equals(codigo))) {
            return null;
        }
        Matcher m = REFERENCIA.matcher(cuerpo);
        return m.find() ? m.group(1) : null;
    }

    /** Retry-After en segundos; si viene como fecha HTTP se ignora. */
    static Integer retryAfter(FeignException e) {
        if (e.responseHeaders() == null) {
            return null;
        }
        for (var cabecera : e.responseHeaders().entrySet()) {
            if ("Retry-After".equalsIgnoreCase(cabecera.getKey())) {
                for (String valor : cabecera.getValue()) {
                    try {
                        return Integer.valueOf(valor.trim());
                    } catch (NumberFormatException ignorado) {
                        // formato de fecha: se usa la espera propia
                    }
                }
            }
        }
        return null;
    }

    /**
     * El payload guardado no entra al modelo del contrato: error del productor que ningún reintento arregla
     * (400 propio con código PAYLOAD_INVALIDO).
     */
    static RespuestaDestino payloadInvalido(RuntimeException e) {
        return new RespuestaDestino(400, "PAYLOAD_INVALIDO", null, e.getMessage());
    }
}
