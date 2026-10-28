package gt.edu.umg.aquatech.pagosconsumer;

/**
 * Texto de error que se guarda para no adivinar qué falló: código (o HTTP_xxx) más el cuerpo del destino,
 * en una sola línea y con tope de largo.
 */
public final class DetalleError {

    /** Largo máximo del detalle en pagos-control.ultimoError. */
    static final int MAX_ULTIMO_ERROR = 500;

    /** Largo máximo del cuerpo guardado en cada fila de pagos-logs. */
    static final int MAX_DETALLE_LOG = 1000;

    private DetalleError() {
    }

    /** CODIGO o HTTP_xxx (TIMEOUT_O_CONEXION si no hubo respuesta), seguido de ": " y el cuerpo si lo hay. */
    public static String ultimoError(RespuestaDestino r) {
        String base;
        if (r.codigoError() != null) {
            base = r.codigoError();
        } else {
            base = r.httpStatus() == 0 ? "TIMEOUT_O_CONEXION" : "HTTP_" + r.httpStatus();
        }
        String detalle = resumir(r.cuerpo(), MAX_ULTIMO_ERROR);
        return detalle == null ? base : base + ": " + detalle;
    }

    /** Una sola línea, sin espacios de sobra y cortada con "..." si pasa del máximo. Null si no hay texto. */
    public static String resumir(String texto, int maximo) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.replaceAll("\\s+", " ").trim();
        if (limpio.isEmpty()) {
            return null;
        }
        return limpio.length() <= maximo ? limpio : limpio.substring(0, maximo - 3) + "...";
    }
}
