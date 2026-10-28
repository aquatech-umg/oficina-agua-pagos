package gt.edu.umg.aquatech.pagosproducer;

import gt.edu.umg.aquatech.pagosproducer.api.model.DestinationStatus;
import gt.edu.umg.aquatech.pagosproducer.api.model.RetryRequest;
import gt.edu.umg.aquatech.pagosproducer.api.model.Transmission;
import gt.edu.umg.aquatech.pagosproducer.api.model.TransmissionStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Consulta del estado de un pago en ambos destinos y reintento manual (contrato payments-api.yaml). */
@Service
public class TransmisionesService {

    /** El pago no tiene ítems de control: responde 404. */
    public static class NoEncontrada extends RuntimeException {
        public NoEncontrada(long pagoId) {
            super("Payment " + pagoId + " not found");
        }
    }

    /** Ningún destino pedido está FAILED: responde 409. */
    public static class SinFallidos extends RuntimeException {
        public SinFallidos(long pagoId) {
            super("Payment " + pagoId + " has no destination in FAILED state to retry");
        }
    }

    private final ControlPagosRepository control;
    private final PayloadFactory payloads;
    private final ProductorPagosService productor;

    public TransmisionesService(ControlPagosRepository control, PayloadFactory payloads,
                                ProductorPagosService productor) {
        this.control = control;
        this.payloads = payloads;
        this.productor = productor;
    }

    public Transmission consultar(long pagoId) {
        String llave = payloads.llave(pagoId);
        Map<String, AttributeValue> banco = control.leer(llave + "#" + Destino.BANCO.name());
        Map<String, AttributeValue> erp = control.leer(llave + "#" + Destino.ERP.name());
        if (banco == null && erp == null) {
            throw new NoEncontrada(pagoId);
        }
        return new Transmission(pagoId, llave, estado(banco), estado(erp));
    }

    /**
     * Reencola los destinos pedidos que estén FAILED, con la misma llave y el mismo payload
     * (los destinos son idempotentes).
     */
    public Transmission reintentar(long pagoId, RetryRequest.DestinationEnum destino) {
        consultar(pagoId);
        String llave = payloads.llave(pagoId);

        List<Destino> elegidos = switch (destino) {
            case BANK -> List.of(Destino.BANCO);
            case ERP -> List.of(Destino.ERP);
            case BOTH -> List.of(Destino.BANCO, Destino.ERP);
        };

        int reencolados = 0;
        for (Destino d : elegidos) {
            String pagoDestino = llave + "#" + d.name();
            if (control.reintentar(pagoDestino)) {
                reencolados++;
                productor.reclamarYPublicar(pagoDestino);
            }
        }
        if (reencolados == 0) {
            throw new SinFallidos(pagoId);
        }
        return consultar(pagoId);
    }

    private static DestinationStatus estado(Map<String, AttributeValue> item) {
        if (item == null) {
            return new DestinationStatus(TransmissionStatus.PENDING, 0);
        }
        DestinationStatus d = new DestinationStatus(traducir(item.get("estado").s()), entero(item, "intentos"));
        if (item.containsKey("ultimoError")) {
            d.setLastError(item.get("ultimoError").s());
        }
        if (item.containsKey("ultimoIntentoEn")) {
            d.setLastAttemptAt(OffsetDateTime.parse(item.get("ultimoIntentoEn").s()));
        }
        if (item.containsKey("referenciaExterna")) {
            d.setExternalReference(item.get("referenciaExterna").s());
        }
        return d;
    }

    static TransmissionStatus traducir(String estado) {
        return switch (estado) {
            case "EN_COLA" -> TransmissionStatus.QUEUED;
            case "ENVIADO" -> TransmissionStatus.SENT;
            case "FALLIDO" -> TransmissionStatus.FAILED;
            default -> TransmissionStatus.PENDING;
        };
    }

    private static int entero(Map<String, AttributeValue> item, String atributo) {
        return item.containsKey(atributo) ? Integer.parseInt(item.get(atributo).n()) : 0;
    }
}
