package gt.edu.umg.aquatech.pagosproducer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Arma el cuerpo (payload) de cada destino a partir de un pago (sección 8 del diseño).
 * Sin dependencias de Spring: se registra como bean en {@link ProducerConfig}.
 *
 * Los campos marcados VERIFICAR son nombres que el diseño no fija: compararlos con
 * bank-payments-api.yaml / erp-payments-api.yaml y agregar los obligatorios que falten.
 * No se envía el DPI (taxId / nit), decisión 6 del diseño.
 */
public class PayloadFactory {

    private static final ZoneId GUATEMALA = ZoneId.of("America/Guatemala");

    private static final Map<String, String> CANAL_BANCO = Map.of(
            "EFECTIVO", "CASH",
            "TARJETA", "CARD",
            "TRANSFERENCIA", "BANK_TRANSFER",
            "CHEQUE", "CHECK");

    // CHEQUE queda sin tipo hasta resolver la decisión 7 del diseño.
    private static final Map<String, String> TIPO_DOCUMENTO_ERP = Map.of(
            "EFECTIVO", "RECIBO_CAJA",
            "TRANSFERENCIA", "BOLETA_DEPOSITO",
            "TARJETA", "VOUCHER_TARJETA");

    private final String equipo;

    public PayloadFactory(String equipo) {
        this.equipo = equipo;
    }

    /** Llave estable: &lt;EQUIPO&gt;-&lt;pago_id con 6 dígitos&gt;, p. ej. EQA-000123. Cumple ^[A-Za-z0-9_-]+$ */
    public String llave(long pagoId) {
        return "%s-%06d".formatted(equipo, pagoId);
    }

    /** Decisión 5 del diseño (por confirmar con Marvin): CLI- más el id con 4 dígitos. */
    public String codigoCliente(long clienteId) {
        return "CLI-%04d".formatted(clienteId);
    }

    public Map<String, Object> payload(Destino destino, PagoReplica p) {
        return switch (destino) {
            case BANCO -> banco(p);
            case ERP -> erp(p);
        };
    }

    public Map<String, Object> banco(PagoReplica p) {
        String metodo = metodo(p);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("customerCode", codigoCliente(p.clienteId()));
        m.put("amount", Map.of("valueInCents", centavos(p.monto())));
        m.put("channel", CANAL_BANCO.getOrDefault(metodo, metodo));   // VERIFICAR nombre del campo
        m.put("paymentDate", fecha(p));                               // VERIFICAR nombre del campo
        return m;
    }

    public Map<String, Object> erp(PagoReplica p) {
        String metodo = metodo(p);
        YearMonth periodo = periodo(p);

        // El detalle siempre debe sumar el total: si el pago es menor que el recibo,
        // todo va a AGUA_POTABLE; la mora solo existe cuando el pago excede el recibo.
        BigDecimal total = dos(p.monto());
        BigDecimal agua = dos(p.montoRecibo()).min(total);
        BigDecimal mora = total.subtract(agua);

        List<Map<String, Object>> detalle = new ArrayList<>();
        detalle.add(linea("AGUA_POTABLE", agua));
        if (mora.signum() > 0) {
            detalle.add(linea("MORA", mora));
        }

        Map<String, Object> documento = new LinkedHashMap<>();
        String tipo = TIPO_DOCUMENTO_ERP.get(metodo);
        if (tipo != null) {
            documento.put("tipo", tipo);
        }
        documento.put("numero", llave(p.id()));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("codigo_cliente", codigoCliente(p.clienteId()));
        m.put("documento_origen", documento);
        m.put("contribuyente", Map.of("codigo_contador", p.numeroRegistro()));
        m.put("periodo_fiscal", Map.of("anio", periodo.getYear(), "mes", periodo.getMonthValue()));
        m.put("fecha_pago", fecha(p));                                // VERIFICAR nombre del campo
        m.put("forma_pago", metodo);                                  // VERIFICAR nombre y valores
        m.put("total", total.toPlainString());
        m.put("detalle", detalle);
        return m;
    }

    private static Map<String, Object> linea(String rubro, BigDecimal monto) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("rubro", rubro);                                        // VERIFICAR nombre del campo
        l.put("monto", monto.toPlainString());
        return l;
    }

    /** Con BigDecimal, nunca double. 150.75 -> 15075 */
    private static long centavos(BigDecimal monto) {
        return dos(monto).movePointRight(2).longValueExact();
    }

    private static BigDecimal dos(BigDecimal monto) {
        return monto.setScale(2, RoundingMode.HALF_UP);
    }

    private static String metodo(PagoReplica p) {
        return p.metodoPago() == null ? "" : p.metodoPago().trim().toUpperCase(Locale.ROOT);
    }

    /** lecturas.periodo puede ser '2026-09' o '2026-09-01': se toma año y mes. */
    private static YearMonth periodo(PagoReplica p) {
        return YearMonth.parse(p.periodo().substring(0, 7));
    }

    /** Fecha del pago (nunca la hora actual) con zona horaria de Guatemala (-06:00). */
    private static String fecha(PagoReplica p) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                p.fechaPago().truncatedTo(ChronoUnit.SECONDS).atZone(GUATEMALA).toOffsetDateTime());
    }
}
