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
 * Arma el cuerpo de cada destino según los contratos del docente (BankPaymentRequest y ErpRegistroPago).
 * No envía DPI ni NIT: son opcionales en ambos contratos.
 */
public class PayloadFactory {

    private static final ZoneId GUATEMALA = ZoneId.of("America/Guatemala");

    // El banco rechaza CHECK con 422.
    private static final Map<String, String> CANAL_BANCO = Map.of(
            "EFECTIVO", "CASH",
            "TARJETA", "CARD",
            "TRANSFERENCIA", "BANK_TRANSFER",
            "CHEQUE", "CHECK");

    // documento_origen.tipo es obligatorio y el contrato no tiene tipo para cheque: se usa RECIBO_CAJA.
    private static final String TIPO_DOCUMENTO_POR_DEFECTO = "RECIBO_CAJA";
    private static final Map<String, String> TIPO_DOCUMENTO_ERP = Map.of(
            "EFECTIVO", "RECIBO_CAJA",
            "TRANSFERENCIA", "BOLETA_DEPOSITO",
            "TARJETA", "VOUCHER_TARJETA",
            "CHEQUE", TIPO_DOCUMENTO_POR_DEFECTO);

    private final String equipo;
    private final String teamCode;

    /**
     * {@code equipo} es el prefijo de la llave (EQA); {@code teamCode} es el código de los contratos
     * (EQUIPO_A) y debe coincidir con la API key.
     */
    public PayloadFactory(String equipo, String teamCode) {
        this.equipo = equipo;
        this.teamCode = teamCode;
    }

    /** Llave estable por pago, p. ej. EQA-000123 (prefijo e id con 6 dígitos). */
    public String llave(long pagoId) {
        return "%s-%06d".formatted(equipo, pagoId);
    }

    /** Código de cliente para los destinos: CLI- más el id con 4 dígitos. */
    public String codigoCliente(long clienteId) {
        return "CLI-%04d".formatted(clienteId);
    }

    public Map<String, Object> payload(Destino destino, PagoReplica p) {
        return switch (destino) {
            case BANCO -> banco(p);
            case ERP -> erp(p);
        };
    }

    /** BankPaymentRequest. La llave va en el header Idempotency-Key, no en el cuerpo. */
    public Map<String, Object> banco(PagoReplica p) {
        String metodo = metodo(p);

        Map<String, Object> pagador = new LinkedHashMap<>();
        pagador.put("customerCode", codigoCliente(p.clienteId()));
        String nombre = recortar(p.cliente(), 150);
        if (nombre != null) {
            pagador.put("fullName", nombre);
        }

        Map<String, Object> monto = new LinkedHashMap<>();
        monto.put("valueInCents", centavos(p.monto()));
        monto.put("currency", "GTQ");

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("teamCode", teamCode);
        m.put("payer", pagador);
        m.put("amount", monto);
        m.put("transactionDate", fecha(p));
        m.put("channel", CANAL_BANCO.getOrDefault(metodo, metodo));
        m.put("description", "Servicio de agua " + periodo(p));
        return m;
    }

    /** ErpRegistroPago. La unicidad va en el cuerpo: documento_origen.numero (la misma llave). */
    public Map<String, Object> erp(PagoReplica p) {
        String metodo = metodo(p);
        YearMonth periodo = periodo(p);

        // El detalle siempre debe sumar el total (si no, el ERP responde 422 TOTAL_NO_CUADRA):
        // si el pago es menor que el recibo todo va a AGUA_POTABLE; la mora solo existe si el pago excede el recibo.
        BigDecimal total = dos(p.monto());
        BigDecimal agua = dos(p.montoRecibo()).min(total);
        BigDecimal mora = total.subtract(agua);

        List<Map<String, Object>> detalle = new ArrayList<>();
        detalle.add(linea("AGUA_POTABLE", agua));
        if (mora.signum() > 0) {
            detalle.add(linea("MORA", mora));
        }

        Map<String, Object> documento = new LinkedHashMap<>();
        documento.put("tipo", TIPO_DOCUMENTO_ERP.getOrDefault(metodo, TIPO_DOCUMENTO_POR_DEFECTO));
        documento.put("numero", llave(p.id()));
        documento.put("fecha_emision", p.fechaPago().toLocalDate().toString());

        Map<String, Object> contribuyente = new LinkedHashMap<>();
        contribuyente.put("codigo_cliente", codigoCliente(p.clienteId()));
        String contador = recortar(p.numeroRegistro(), 50);
        if (contador != null) {
            contribuyente.put("codigo_contador", contador);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("equipo", teamCode);
        m.put("documento_origen", documento);
        m.put("contribuyente", contribuyente);
        m.put("periodo_fiscal", Map.of("anio", periodo.getYear(), "mes", periodo.getMonthValue()));
        m.put("forma_pago", metodo);
        m.put("fecha_pago", fecha(p));
        m.put("total", total.toPlainString());
        m.put("detalle", detalle);
        return m;
    }

    private static Map<String, Object> linea(String rubro, BigDecimal monto) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("rubro", rubro);
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

    private static String recortar(String texto, int maximo) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        String limpio = texto.trim();
        return limpio.length() <= maximo ? limpio : limpio.substring(0, maximo);
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
