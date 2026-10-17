package gt.edu.umg.aquatech.pagosproducer;

/** Destinos externos de un pago. Cada destino tiene su propia cola y su propio ítem de control. */
public enum Destino {
    BANCO("pagos.banco"),
    ERP("pagos.erp");

    private final String cola;

    Destino(String cola) {
        this.cola = cola;
    }

    public String cola() {
        return cola;
    }
}
