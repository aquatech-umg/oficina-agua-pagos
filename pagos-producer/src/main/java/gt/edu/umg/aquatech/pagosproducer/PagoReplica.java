package gt.edu.umg.aquatech.pagosproducer;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Un pago leído de la réplica de solo lectura (resultado de la consulta de la sección 7 del diseño). */
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
