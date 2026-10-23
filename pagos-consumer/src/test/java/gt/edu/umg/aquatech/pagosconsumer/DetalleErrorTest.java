package gt.edu.umg.aquatech.pagosconsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DetalleErrorTest {

    @Test
    void conCuerpoGuardaCodigoYDetalle() {
        RespuestaDestino r = new RespuestaDestino(400, null, null,
                "{\"code\":\"VALIDATION_ERROR\",\n  \"message\":\"payer.taxId must not be null\"}");
        assertEquals("HTTP_400: {\"code\":\"VALIDATION_ERROR\", \"message\":\"payer.taxId must not be null\"}",
                DetalleError.ultimoError(r));
    }

    @Test
    void siHayCodigoConocidoSeUsaEseCodigo() {
        RespuestaDestino r = new RespuestaDestino(422, "UNSUPPORTED_CHANNEL", null, "{\"code\":\"UNSUPPORTED_CHANNEL\"}");
        assertEquals("UNSUPPORTED_CHANNEL: {\"code\":\"UNSUPPORTED_CHANNEL\"}", DetalleError.ultimoError(r));
    }

    @Test
    void sinCuerpoQuedaSoloElCodigoHttp() {
        assertEquals("HTTP_503", DetalleError.ultimoError(new RespuestaDestino(503, null, null, null)));
        assertEquals("HTTP_503", DetalleError.ultimoError(new RespuestaDestino(503, null, null, "   ")));
    }

    @Test
    void unTimeoutIncluyeElMensajeDeLaExcepcion() {
        RespuestaDestino r = new RespuestaDestino(0, null, null, "Read timed out executing POST https://x/pagos");
        assertEquals("TIMEOUT_O_CONEXION: Read timed out executing POST https://x/pagos", DetalleError.ultimoError(r));
    }

    @Test
    void unCuerpoLargoSeCortaConPuntosSuspensivos() {
        String largo = "x".repeat(2000);
        String texto = DetalleError.resumir(largo, 500);
        assertEquals(500, texto.length());
        assertEquals("...", texto.substring(497));
        assertEquals(null, DetalleError.resumir(null, 500));
    }
}
