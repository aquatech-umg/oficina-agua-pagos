package gt.edu.umg.aquatech.pagosproducer;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PagoReplica(
        long id,
        BigDecimal monto,
        LocalDateTime fechaPago,
        String referencia,
        String metodoPago,
        String numeroRecibo,
        BigDecimal montoRecibo,
        String periodo,
        String numeroRegistro,
        long clienteId,
        String cliente) {
}
