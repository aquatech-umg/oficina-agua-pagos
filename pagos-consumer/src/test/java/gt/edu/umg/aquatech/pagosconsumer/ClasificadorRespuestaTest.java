package gt.edu.umg.aquatech.pagosconsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ClasificadorRespuestaTest {

    @Test
    void creado201EsExito() {
        assertEquals(Resultado.EXITOSO, ClasificadorRespuesta.clasificar(201, null));
    }

    @Test
    void duplicadoEs409ConCodigoDeDuplicado() {
        assertEquals(Resultado.EXITOSO_DUPLICADO,
                ClasificadorRespuesta.clasificar(409, "{\"code\":\"DUPLICATE_PAYMENT\"}"));
        assertEquals(Resultado.EXITOSO_DUPLICADO,
                ClasificadorRespuesta.clasificar(409, "{\"codigo\":\"DOCUMENTO_DUPLICADO\"}"));
    }

    @Test
    void llaveReutilizadaEsFallidoDefinitivo() {
        assertEquals(Resultado.FALLIDO_DEFINITIVO,
                ClasificadorRespuesta.clasificar(409, "{\"code\":\"IDEMPOTENCY_KEY_REUSED\"}"));
        assertEquals(Resultado.FALLIDO_DEFINITIVO,
                ClasificadorRespuesta.clasificar(409, "{\"codigo\":\"DOCUMENTO_REUTILIZADO\"}"));
        assertEquals(Resultado.FALLIDO_DEFINITIVO, ClasificadorRespuesta.clasificar(409, null));
    }

    @Test
    void erroresDelClienteSonDefinitivos() {
        for (int status : new int[] {400, 403, 422}) {
            assertEquals(Resultado.FALLIDO_DEFINITIVO, ClasificadorRespuesta.clasificar(status, null));
        }
    }

    @Test
    void errorTransitorioEsReintentable() {
        for (int status : new int[] {0, 429, 500, 502, 503, 504}) {
            assertEquals(Resultado.FALLIDO_REINTENTABLE, ClasificadorRespuesta.clasificar(status, null));
        }
    }
}
