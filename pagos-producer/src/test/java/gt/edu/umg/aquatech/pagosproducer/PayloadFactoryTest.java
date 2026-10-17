package gt.edu.umg.aquatech.pagosproducer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PayloadFactoryTest {

    private final PayloadFactory fabrica = new PayloadFactory("EQA", "EQUIPO_A");

    private PagoReplica pago(String monto, String montoRecibo, String metodo) {
        return new PagoReplica(123, new BigDecimal(monto), LocalDateTime.of(2026, 10, 10, 8, 30, 0),
                "REF-1", metodo, "REC-2026-4821-000001", new BigDecimal(montoRecibo),
                "2026-09-01", "4821", 7, "Ana López");
    }

    @Test
    void llaveEsEstableYConSeisDigitos() {
        assertEquals("EQA-000123", fabrica.llave(123));
        assertEquals("EQA-000123", fabrica.llave(123));
    }

    @Test
    @SuppressWarnings("unchecked")
    void bancoSigueElContratoBankPaymentRequest() {
        Map<String, Object> banco = fabrica.banco(pago("150.75", "140.00", "Efectivo"));
        assertEquals("EQUIPO_A", banco.get("teamCode"));
        assertEquals(Map.of("customerCode", "CLI-0007", "fullName", "Ana López"), banco.get("payer"));
        assertEquals(Map.of("valueInCents", 15075L, "currency", "GTQ"), banco.get("amount"));
        assertEquals("2026-10-10T08:30:00-06:00", banco.get("transactionDate"));
        assertEquals("CASH", banco.get("channel"));
        assertEquals("Servicio de agua 2026-09", banco.get("description"));
        assertFalse(((Map<String, Object>) banco.get("payer")).containsKey("taxId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void erpSigueElContratoErpRegistroPago() {
        Map<String, Object> erp = fabrica.erp(pago("150.75", "140.00", "EFECTIVO"));
        assertEquals("EQUIPO_A", erp.get("equipo"));
        assertEquals(Map.of("tipo", "RECIBO_CAJA", "numero", "EQA-000123", "fecha_emision", "2026-10-10"),
                erp.get("documento_origen"));
        assertEquals(Map.of("codigo_cliente", "CLI-0007", "codigo_contador", "4821"), erp.get("contribuyente"));
        assertEquals(Map.of("anio", 2026, "mes", 9), erp.get("periodo_fiscal"));
        assertEquals("EFECTIVO", erp.get("forma_pago"));
        assertEquals("150.75", erp.get("total"));
        assertFalse(((Map<String, Object>) erp.get("contribuyente")).containsKey("nit"));
    }

    @Test
    void erpSeparaMoraYElDetalleSumaElTotal() {
        Map<String, Object> erp = fabrica.erp(pago("150.75", "140.00", "EFECTIVO"));
        List<?> detalle = (List<?>) erp.get("detalle");
        assertEquals(2, detalle.size());
        assertEquals("140.00", ((Map<?, ?>) detalle.get(0)).get("monto"));
        assertEquals("MORA", ((Map<?, ?>) detalle.get(1)).get("rubro"));
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
    void chequeVaAlBancoComoCheckYAlErpComoReciboDeCaja() {
        PagoReplica cheque = pago("100.00", "100.00", "CHEQUE");
        assertEquals("CHECK", fabrica.banco(cheque).get("channel"));
        Map<?, ?> documento = (Map<?, ?>) fabrica.erp(cheque).get("documento_origen");
        assertEquals("RECIBO_CAJA", documento.get("tipo"));
        assertEquals("CHEQUE", fabrica.erp(cheque).get("forma_pago"));
    }
}
