package gt.edu.umg.aquatech.pagosconsumer;

import java.util.List;

/**
 * Qué hacer con un pago cuyo envío falló (sección 6 del diseño). Lógica pura, sin Spring.
 *
 * <ul>
 *   <li>Error definitivo (400, 403, 422, 409 de llave reutilizada): dead-letter y FALLIDO, sin reintentar.</li>
 *   <li>Error transitorio (429, 500, 502, 503, 504, timeout): 5 intentos en total por tanda, con esperas de
 *       2, 4, 8 y 16 segundos entre ellos. Si el destino manda Retry-After se espera al menos eso.</li>
 *   <li>Al agotar los intentos: dead-letter y FALLIDO.</li>
 * </ul>
 *
 * La espera se hace con una cola por escalón (cada una con su TTL fijo): así un mensaje de 2 s nunca queda
 * detrás de uno de 16 s, cosa que sí pasa con un TTL distinto por mensaje dentro de la misma cola.
 */
public final class PoliticaReintentos {

    /** Intentos totales por tanda (la tanda se reinicia con POST /retry). */
    public static final int MAX_INTENTOS = 5;

    /** Espera tras el intento 1, 2, 3 y 4. */
    private static final List<Integer> ESPERA_POR_INTENTO = List.of(2, 4, 8, 16);

    /** Escalones que existen como cola de espera. El último cubre Retry-After largos. */
    public static final List<Integer> ESCALONES_SEGUNDOS = List.of(2, 4, 8, 16, 32);

    public enum Accion {
        /** Mandar el mensaje a la cola de espera y reintentar luego. */
        ESPERAR,
        /** Mandar el mensaje a pagos.dlq y marcar el ítem FALLIDO. */
        DEAD_LETTER
    }

    /**
     * @param accion          qué hacer
     * @param esperaSegundos  escalón de la cola de espera (0 si la acción es DEAD_LETTER)
     * @param motivo          para pagos.dlq: por qué se rindió (null si se espera)
     */
    public record Decision(Accion accion, int esperaSegundos, String motivo) {
    }

    private PoliticaReintentos() {
    }

    /**
     * @param resultado        clasificación de la respuesta (debe ser un fallo)
     * @param intentoEnTanda   número de intento dentro de la tanda actual, empezando en 1
     * @param retryAfter       segundos que pidió el destino, o null
     */
    public static Decision decidirTrasFallo(Resultado resultado, int intentoEnTanda, Integer retryAfter) {
        if (resultado.esExito()) {
            throw new IllegalArgumentException("Un éxito no necesita política de reintentos");
        }
        if (resultado == Resultado.FALLIDO_DEFINITIVO) {
            return new Decision(Accion.DEAD_LETTER, 0, "ERROR_DEFINITIVO");
        }
        if (intentoEnTanda >= MAX_INTENTOS) {
            return new Decision(Accion.DEAD_LETTER, 0, "REINTENTOS_AGOTADOS");
        }
        int base = ESPERA_POR_INTENTO.get(Math.max(1, intentoEnTanda) - 1);
        int pedida = retryAfter == null ? 0 : Math.max(0, retryAfter);
        return new Decision(Accion.ESPERAR, escalon(Math.max(base, pedida)), null);
    }

    /** Menor escalón que cubre la espera pedida; si es más larga que el último, el último. */
    static int escalon(int segundos) {
        for (int e : ESCALONES_SEGUNDOS) {
            if (segundos <= e) {
                return e;
            }
        }
        return ESCALONES_SEGUNDOS.get(ESCALONES_SEGUNDOS.size() - 1);
    }
}
