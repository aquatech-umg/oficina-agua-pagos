package gt.edu.umg.aquatech.pagosconsumer;

/** Mensaje que publica el producer: solo la referencia, el payload está en pagos-control. */
public record MensajePago(long pagoId, String destino, String idempotencyKey) {

    /** Clave del ítem en pagos-control y de los intentos en pagos-logs, p. ej. EQA-000123#BANCO. */
    public String pagoDestino() {
        return idempotencyKey + "#" + destino;
    }
}
