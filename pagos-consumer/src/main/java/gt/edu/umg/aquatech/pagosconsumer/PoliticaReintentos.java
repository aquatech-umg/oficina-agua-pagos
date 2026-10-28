package gt.edu.umg.aquatech.pagosconsumer;

import java.util.List;

/**
 * Qué hacer con un pago cuyo envío falló. Un error definitivo va al dead-letter; uno transitorio se reintenta
 * hasta 5 veces con esperas de 2, 4, 8 y 16 s (o el Retry-After del destino); al agotarse va al dead-letter.
 * La espera usa una cola por escalón, cada una con su TTL fijo: con TTL distinto por mensaje en una sola cola,
 * uno de 16 s bloquearía a uno de 2 s que esté detrás.
 */
public final class PoliticaReintentos {

    /** Intentos totales por tanda (la tanda se reinicia con POST /retry). */
    public static final int MAX_INTENTOS = 5;

    /** Espera tras el intento 1, 2, 3 y 4. */
    private static final List<Integer> ESPERA_POR_INTENTO = List.of(2, 4, 8, 16);

    /** Escalones que existen como cola de espera. El último cubre Retry-After largos. */
    public static final List<Integer> ESCALONES_SEGUNDOS = List.of(2, 4, 8, 16, 32);

    public enum Accion {
        ESPERAR,
        DEAD_LETTER
    }

    /**
     * @param esperaSegundos escalón de la cola de espera (0 si va al dead-letter)
     * @param motivo         razón del dead-letter (null si se espera)
     */
    public record Decision(Accion accion, int esperaSegundos, String motivo) {
    }

    private PoliticaReintentos() {
    }

    /** {@code intentoEnTanda} empieza en 1; {@code retryAfter} son los segundos que pidió el destino, o null. */
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
