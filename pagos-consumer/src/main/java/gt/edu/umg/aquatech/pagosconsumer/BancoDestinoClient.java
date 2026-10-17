package gt.edu.umg.aquatech.pagosconsumer;

import org.springframework.stereotype.Component;

/**
 * TODO: conectar con el cliente Feign generado desde bank-payments-api.yaml
 * (paquete gt.edu.umg.aquatech.pagosconsumer.cliente.banco.api). Esqueleto:
 *
 * <pre>
 *   var solicitud = mapper.readValue(payloadJson, &lt;ModeloGeneradoDelBanco&gt;.class);
 *   try {
 *       var respuesta = api.&lt;operacion&gt;(idempotencyKey, solicitud);      // header Idempotency-Key
 *       return new RespuestaDestino(respuesta.getStatusCode().value(), null, &lt;comprobante&gt;, null);
 *   } catch (FeignException e) {                                         // 4xx / 5xx
 *       String cuerpo = e.contentUTF8();
 *       return new RespuestaDestino(e.status(), ClasificadorRespuesta.extraerCodigo(cuerpo), null, cuerpo);
 *   } catch (RetryableException e) {                                     // timeout / conexión
 *       return new RespuestaDestino(0, null, null, e.getMessage());
 *   }
 * </pre>
 */
@Component
public class BancoDestinoClient implements DestinoClient {

    @Override
    public String destino() {
        return "BANCO";
    }

    @Override
    public RespuestaDestino enviar(String payloadJson, String idempotencyKey) {
        throw new UnsupportedOperationException("Falta conectar el cliente Feign del banco (tarea de clientes Feign)");
    }
}
