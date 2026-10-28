package gt.edu.umg.aquatech.pagosproducer;

import gt.edu.umg.aquatech.pagosproducer.api.PaymentsApi;
import gt.edu.umg.aquatech.pagosproducer.api.model.Destination;
import gt.edu.umg.aquatech.pagosproducer.api.model.DispatchRequest;
import gt.edu.umg.aquatech.pagosproducer.api.model.DispatchResult;
import gt.edu.umg.aquatech.pagosproducer.api.model.RetryRequest;
import gt.edu.umg.aquatech.pagosproducer.api.model.Transmission;
import gt.edu.umg.aquatech.pagosproducer.api.model.TransmissionStatus;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implementa la interfaz generada desde payments-api.yaml: estado de un pago (GET) y reintento (POST /retry).
 * Las operaciones list y dispatch del contrato responden 501.
 */
@RestController
public class PagosApiController implements PaymentsApi {

    private final TransmisionesService transmisiones;

    public PagosApiController(TransmisionesService transmisiones) {
        this.transmisiones = transmisiones;
    }

    @Override
    public ResponseEntity<Transmission> getTransmission(Long paymentId) {
        return ResponseEntity.ok(transmisiones.consultar(paymentId));
    }

    @Override
    public ResponseEntity<Transmission> retryTransmission(Long paymentId, @Nullable RetryRequest retryRequest) {
        RetryRequest.DestinationEnum destino =
                (retryRequest == null || retryRequest.getDestination() == null)
                        ? RetryRequest.DestinationEnum.BOTH
                        : retryRequest.getDestination();
        return ResponseEntity.accepted().body(transmisiones.reintentar(paymentId, destino));
    }

    @Override
    public ResponseEntity<List<Transmission>> listTransmissions(@Nullable TransmissionStatus status,
                                                                @Nullable Destination destination) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @Override
    public ResponseEntity<DispatchResult> dispatchPendingPayments(@Nullable DispatchRequest dispatchRequest) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
