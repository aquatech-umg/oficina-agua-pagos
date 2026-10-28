package gt.edu.umg.aquatech.pagosconsumer;

import java.util.List;
import java.util.Set;

/** Traduce (estado HTTP + cuerpo) a un {@link Resultado}. */
public final class ClasificadorRespuesta {

    private static final List<String> CODIGOS_CONOCIDOS = List.of(
            "DUPLICATE_PAYMENT", "DOCUMENTO_DUPLICADO",
            "IDEMPOTENCY_KEY_REUSED", "DOCUMENTO_REUTILIZADO",
            "UNSUPPORTED_CHANNEL", "PERIODO_CERRADO");

    /** 0 = timeout o falla de conexión. */
    private static final Set<Integer> REINTENTABLES = Set.of(0, 429, 500, 502, 503, 504);

    private ClasificadorRespuesta() {
    }

    public static Resultado clasificar(int status, String cuerpo) {
        if (status == 201) {
            return Resultado.EXITOSO;
        }
        if (status == 409) {
            String codigo = extraerCodigo(cuerpo);
            if ("DUPLICATE_PAYMENT".equals(codigo) || "DOCUMENTO_DUPLICADO".equals(codigo)) {
                return Resultado.EXITOSO_DUPLICADO;
            }
            // IDEMPOTENCY_KEY_REUSED, DOCUMENTO_REUTILIZADO o un 409 sin código reconocible:
            // nunca se da por entregado sin evidencia.
            return Resultado.FALLIDO_DEFINITIVO;
        }
        if (REINTENTABLES.contains(status)) {
            return Resultado.FALLIDO_REINTENTABLE;
        }
        // 400, 403, 422 y cualquier otro estado inesperado.
        return Resultado.FALLIDO_DEFINITIVO;
    }

    /** Distingue los dos 409 mirando el código de error del cuerpo, no solo el estado HTTP. */
    public static String extraerCodigo(String cuerpo) {
        if (cuerpo == null) {
            return null;
        }
        for (String codigo : CODIGOS_CONOCIDOS) {
            if (cuerpo.contains(codigo)) {
                return codigo;
            }
        }
        return null;
    }
}
