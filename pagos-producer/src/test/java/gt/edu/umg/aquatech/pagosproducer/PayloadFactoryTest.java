package gt.edu.umg.aquatech.pagosproducer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PayloadFactoryTest {

    private final PayloadFactory fabrica = new PayloadFactory("EQA");

    private PagoReplica pago(String monto, String montoRecibo, String metodo) {
        return new PagoReplica(123, new BigDecimal(monto), LocalDateTime.of(2026, 10, 10, 8, 30, 0),
                "REF-1", metodo, "REC-2026-4821-000001", new BigDecimal(montoRecibo),
                "2026-09-01", "4821", 7, "Ana");
    }

    @Test
    void llaveEsEstableYConSeisDigitos() {
        assertEquals("EQA-000123", fabrica.llave(123));
        assertEquals("EQA-000123", fabrica.llave(123));
    }

    @Test
    void bancoEnviaCentavosEnteros() {
        Map<String, Object> banco = fabrica.banco(pago("150.75", "140.00", "Efectivo"));
        assertEquals(Map.of("valueInCents", 15075L), banco.get("amount"));
        assertEquals("CASH", banco.get("channel"));
        assertEquals("2026-10-10T08:30:00-06:00", banco.get("paymentDate"));
        assertEquals("CLI-0007", banco.get("customerCode"));
    }

    @Test
    void erpSeparaMoraYElDetalleSumaElTotal() {
        Map<String, Object> erp = fabrica.erp(pago("150.75", "140.00", "EFECTIVO"));
        assertEquals("150.75", erp.get("total"));
        List<?> detalle = (List<?>) erp.get("detalle");
        assertEquals(2, detalle.size());
        assertEquals("10.75", ((Map<?, ?>) detalle.get(1)).get("monto"));
    }

    @Test
    void pagoMenorQueElReciboNoGeneraMora() {
        Map<String, Object> erp = fabrica.erp(pago("100.00", "140.00", "TARJETA"));
        List<?> detalle = (List<?>) erp.get("detalle");
        assertEquals(1, detalle.size());
        assertEquals("100.00", ((Map<?, ?>) detalle.get(0)).get("monto"));
    }

    @Test
    void noSeEnviaElDpi() {
        assertFalse(fabrica.banco(pago("10.00", "10.00", "EFECTIVO")).containsKey("taxId"));
        assertFalse(fabrica.erp(pago("10.00", "10.00", "EFECTIVO")).containsKey("nit"));
    }
}
