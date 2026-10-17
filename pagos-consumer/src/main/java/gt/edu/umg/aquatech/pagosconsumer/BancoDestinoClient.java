package gt.edu.umg.aquatech.pagosconsumer;

import feign.FeignException;
import gt.edu.umg.aquatech.pagosconsumer.cliente.banco.api.BankPaymentsApiClient;
import gt.edu.umg.aquatech.pagosconsumer.cliente.banco.model.BankPaymentReceipt;
import gt.edu.umg.aquatech.pagosconsumer.cliente.banco.model.BankPaymentRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Envía el pago al banco con el cliente Feign generado desde bank-payments-api.yaml.
 * La llave de idempotencia va en el header Idempotency-Key (POST /bank/v1/payments).
 */
@Component
public class BancoDestinoClient implements DestinoClient {

    private final BankPaymentsApiClient api;
    private final JsonMapper mapper;
    private final String escenario;

    public BancoDestinoClient(BankPaymentsApiClient api,
                              JsonMapper mapper,
                              // Solo para pruebas: fuerza SUCCESS, TIMEOUT, LOST_ACK, ERROR_503, RATE_LIMIT...
                              // Vacío = comportamiento normal del simulador (AUTO).
                              @Value("${BANK_SIMULATION_SCENARIO:}") String escenario) {
        this.api = api;
        this.mapper = mapper;
        this.escenario = escenario.isBlank() ? null : escenario;
    }

    @Override
    public String destino() {
        return "BANCO";
    }

    @Override
    public RespuestaDestino enviar(String payloadJson, String idempotencyKey) {
        try {
            BankPaymentRequest solicitud = mapper.readValue(payloadJson, BankPaymentRequest.class);
            // X-Correlation-Id = la misma llave: se puede rastrear el pago en el banco de punta a punta.
            ResponseEntity<BankPaymentReceipt> respuesta =
                    api.sendBankPayment(idempotencyKey, solicitud, idempotencyKey, escenario);
            BankPaymentReceipt comprobante = respuesta.getBody();
            return RespuestasFeign.exitosa(
                    respuesta.getStatusCode().value(),
                    comprobante == null ? null : comprobante.getReceiptId());
        } catch (FeignException e) {
            return RespuestasFeign.deExcepcion(e);
        } catch (ConstraintViolationException e) {
            return RespuestasFeign.payloadInvalido(e);
        } catch (RuntimeException e) {
            // Jackson 3 lanza excepciones sin chequear: aquí solo cae un payload que no entra al modelo.
            if (e.getClass().getName().startsWith("tools.jackson.")) {
                return RespuestasFeign.payloadInvalido(e);
            }
            throw e;
        }
    }
}
