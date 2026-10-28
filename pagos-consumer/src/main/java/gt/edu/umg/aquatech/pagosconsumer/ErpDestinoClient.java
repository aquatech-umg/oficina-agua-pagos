package gt.edu.umg.aquatech.pagosconsumer;

import feign.FeignException;
import gt.edu.umg.aquatech.pagosconsumer.cliente.erp.api.ErpPagosApiClient;
import gt.edu.umg.aquatech.pagosconsumer.cliente.erp.model.ErpComprobante;
import gt.edu.umg.aquatech.pagosconsumer.cliente.erp.model.ErpRegistroPago;
import jakarta.validation.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Registra el pago en el ERP con el cliente Feign generado; la unicidad va en documento_origen.numero. */
@Component
public class ErpDestinoClient implements DestinoClient {

    private final ErpPagosApiClient api;
    private final JsonMapper mapper;
    private final String escenario;

    public ErpDestinoClient(ErpPagosApiClient api,
                            JsonMapper mapper,
                            // Solo pruebas: fuerza un escenario del simulador; vacío = comportamiento normal.
                            @Value("${ERP_SIMULATION_SCENARIO:}") String escenario) {
        this.api = api;
        this.mapper = mapper;
        this.escenario = escenario.isBlank() ? null : escenario;
    }

    @Override
    public String destino() {
        return "ERP";
    }

    @Override
    public RespuestaDestino enviar(String payloadJson, String idempotencyKey) {
        try {
            ErpRegistroPago solicitud = mapper.readValue(payloadJson, ErpRegistroPago.class);
            ResponseEntity<ErpComprobante> respuesta =
                    api.registrarPagoErp(solicitud, idempotencyKey, escenario);
            ErpComprobante comprobante = respuesta.getBody();
            return RespuestasFeign.exitosa(
                    respuesta.getStatusCode().value(),
                    comprobante == null ? null : comprobante.getNumeroComprobante());
        } catch (FeignException e) {
            return RespuestasFeign.deExcepcion(e);
        } catch (ConstraintViolationException e) {
            return RespuestasFeign.payloadInvalido(e);
        } catch (RuntimeException e) {
            if (e.getClass().getName().startsWith("tools.jackson.")) {
                return RespuestasFeign.payloadInvalido(e);
            }
            throw e;
        }
    }
}
