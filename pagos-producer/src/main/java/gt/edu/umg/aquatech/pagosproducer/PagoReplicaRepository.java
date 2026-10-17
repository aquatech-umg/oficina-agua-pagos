package gt.edu.umg.aquatech.pagosproducer;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Solo lectura sobre la réplica. Nunca escribe en MySQL. */
@Component
public class PagoReplicaRepository {

    // Consulta de la sección 7 del diseño.
    private static final String SQL = """
            SELECT p.id, p.monto, p.fecha_pago, p.referencia,
                   m.nombre AS metodo_pago,
                   r.numero_recibo, r.monto AS monto_recibo,
                   l.periodo,
                   c.numero_registro,
                   cl.id AS cliente_id, cl.nombre AS cliente
            FROM pagos p
            JOIN recibos r       ON r.id = p.recibo_id
            JOIN lecturas l      ON l.id = r.lectura_id
            JOIN contadores c    ON c.id = l.contador_id
            JOIN clientes cl     ON cl.id = c.cliente_id
            JOIN metodos_pago m  ON m.id = p.metodo_pago_id
            WHERE p.id > :ultimoPagoId AND p.fecha_pago >= :desde
            ORDER BY p.id
            LIMIT :limite
            """;

    private final JdbcClient jdbc;

    public PagoReplicaRepository(@Qualifier("replicaJdbc") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<PagoReplica> nuevos(long despuesDeId, LocalDate desde, int limite) {
        return jdbc.sql(SQL)
                .param("ultimoPagoId", despuesDeId)
                .param("desde", desde)
                .param("limite", limite)
                .query((rs, fila) -> new PagoReplica(
                        rs.getLong("id"),
                        rs.getBigDecimal("monto"),
                        rs.getObject("fecha_pago", LocalDateTime.class),
                        rs.getString("referencia"),
                        rs.getString("metodo_pago"),
                        rs.getString("numero_recibo"),
                        rs.getBigDecimal("monto_recibo"),
                        rs.getString("periodo"),
                        rs.getString("numero_registro"),
                        rs.getLong("cliente_id"),
                        rs.getString("cliente")))
                .list();
    }
}
