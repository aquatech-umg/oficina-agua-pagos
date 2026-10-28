package gt.edu.umg.aquatech.pagosconsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RespuestasFeignTest {

    private static Request peticion() {
        return Request.create(Request.HttpMethod.POST, "http://destino/pagos", Map.of(), null,
                StandardCharsets.UTF_8, null);
    }

    private static FeignException error(int estado, Map<String, java.util.Collection<String>> cabeceras, String cuerpo) {
        Response respuesta = Response.builder().status(estado).reason("error").request(peticion())
                .headers(cabeceras).body(cuerpo, StandardCharsets.UTF_8).build();
        return FeignException.errorStatus("metodo", respuesta);
    }

    @Test
    void unDuplicadoDelBancoGuardaElComprobanteOriginal() {
        String cuerpo = "{\"code\":\"DUPLICATE_PAYMENT\",\"originalReceipt\":{\"receiptId\":\"BNK-20261007-8F3K2Q\"}}";
        RespuestaDestino r = RespuestasFeign.deExcepcion(error(409, Map.of(), cuerpo));
        assertEquals(409, r.httpStatus());
        assertEquals("DUPLICATE_PAYMENT", r.codigoError());
        assertEquals("BNK-20261007-8F3K2Q", r.referenciaExterna());
    }

    @Test
    void unDuplicadoDelErpGuardaElComprobanteOriginal() {
        String cuerpo = "{\"codigo_error\":\"DOCUMENTO_DUPLICADO\","
                + "\"comprobante_original\":{\"numero_comprobante\":\"ERP-20261007-Q7W2ZX\"}}";
        RespuestaDestino r = RespuestasFeign.deExcepcion(error(409, Map.of(), cuerpo));
        assertEquals("ERP-20261007-Q7W2ZX", r.referenciaExterna());
    }

    @Test
    void unaLlaveReutilizadaNoGuardaReferencia() {
        String cuerpo = "{\"code\":\"IDEMPOTENCY_KEY_REUSED\",\"originalReceipt\":{\"receiptId\":\"BNK-1\"}}";
        assertNull(RespuestasFeign.deExcepcion(error(409, Map.of(), cuerpo)).referenciaExterna());
    }

    @Test
    void leeRetryAfterEnSegundosSinImportarMayusculas() {
        RespuestaDestino r = RespuestasFeign.deExcepcion(
                error(503, Map.of("retry-after", List.of("7")), "{\"code\":\"SERVICE_UNAVAILABLE\"}"));
        assertEquals(503, r.httpStatus());
        assertEquals(7, r.retryAfterSegundos());
    }

    @Test
    void ignoraRetryAfterEnFormatoDeFecha() {
        RespuestaDestino r = RespuestasFeign.deExcepcion(
                error(503, Map.of("Retry-After", List.of("Wed, 21 Oct 2026 07:28:00 GMT")), ""));
        assertNull(r.retryAfterSegundos());
    }

    @Test
    void unTimeoutEsEstadoCero() {
        RetryableException timeout = new RetryableException(-1, "Read timed out", Request.HttpMethod.POST,
                new SocketTimeoutException("x"), (Long) null, peticion());
        assertEquals(0, RespuestasFeign.deExcepcion(timeout).httpStatus());
    }
}
