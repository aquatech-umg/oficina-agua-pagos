package gt.edu.umg.aquatech.pagosproducer;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tools.jackson.databind.json.JsonMapper;


@Service
public class ProductorPagosService {

    private static final Logger log = LoggerFactory.getLogger(ProductorPagosService.class);

    /** La consulta retrocede unos ids bajo el cursor por pagos que se confirman tarde en la réplica. */
    private static final int RETROCESO_IDS = 100;

    private final PagoReplicaRepository replica;
    private final ControlPagosRepository control;
    private final PayloadFactory payloads;
    private final PublicadorPagos publicador;
    private final JsonMapper mapper;
    private final LocalDate desde;
    private final int lote;

    public ProductorPagosService(PagoReplicaRepository replica,
                                 ControlPagosRepository control,
                                 PayloadFactory payloads,
                                 PublicadorPagos publicador,
                                 JsonMapper mapper,
                                 // El ERP rechaza períodos de más de 12 meses; por defecto no se filtra nada.
                                 @Value("${PAGOS_DESDE:1970-01-01}") String desde,
                                 // Debe ser bastante mayor que RETROCESO_IDS para que el cursor siempre avance.
                                 @Value("${pagos.producer.lote:500}") int lote) {
        this.replica = replica;
        this.control = control;
        this.payloads = payloads;
        this.publicador = publicador;
        this.mapper = mapper;
        // Variable definida pero vacía (PAGOS_DESDE=) = sin filtro, igual que si no existiera.
        this.desde = desde.isBlank() ? LocalDate.of(1970, 1, 1) : LocalDate.parse(desde.trim());
        this.lote = lote;
    }

    @Scheduled(fixedDelayString = "${pagos.producer.intervalo-ms:10000}")
    public void ciclo() {
        try {
            int creados = detectarNuevos();
            int publicados = publicarPendientes();
            if (creados > 0 || publicados > 0) {
                log.info("Ciclo del producer: {} ítems creados, {} mensajes publicados", creados, publicados);
            }
        } catch (RuntimeException e) {
            log.error("El ciclo del producer falló; se reintenta en el siguiente", e);
        }
    }

    /** Devuelve cuántos ítems de control creó (dos por cada pago nuevo). */
    public int detectarNuevos() {
        long cursor = control.leerCursor();
        List<PagoReplica> pagos = replica.nuevos(Math.max(0, cursor - RETROCESO_IDS), desde, lote);

        long maxProcesado = cursor;
        long primerFallo = Long.MAX_VALUE;
        int creados = 0;

        for (PagoReplica pago : pagos) {
            try {
                String llave = payloads.llave(pago.id());
                for (Destino destino : Destino.values()) {
                    String json = mapper.writeValueAsString(payloads.payload(destino, pago));
                    if (control.crearSiNoExiste(destino, pago.id(), llave, json)) {
                        creados++;
                    }
                }
                maxProcesado = Math.max(maxProcesado, pago.id());
            } catch (RuntimeException e) {
                // Un pago con datos raros no bloquea a los demás, pero el cursor no lo deja atrás:
                // se vuelve a intentar en cada ciclo hasta que se corrija.
                primerFallo = Math.min(primerFallo, pago.id());
                log.error("No se pudo crear el control del pago {}; se reintentará", pago.id(), e);
            }
        }

        long nuevoCursor = Math.min(maxProcesado, primerFallo - 1);
        if (nuevoCursor > cursor) {
            control.avanzarCursor(nuevoCursor);
        }
        return creados;
    }

    /** Devuelve cuántos mensajes publicó y confirmó RabbitMQ. */
    public int publicarPendientes() {
        int publicados = 0;
        for (Map<String, AttributeValue> item : control.pendientes(lote)) {
            if (reclamarYPublicar(item.get("pagoDestino").s())) {
                publicados++;
            }
        }
        return publicados;
    }

    /**
     * Reclama un ítem PENDIENTE (-> EN_COLA, condicional) y publica su mensaje con confirmación. Lo usan el
     * ciclo normal y POST /retry. Devuelve false si otra corrida ya lo reclamó o si no se pudo publicar
     * (en ese caso el ítem vuelve a PENDIENTE).
     */
    public boolean reclamarYPublicar(String pagoDestino) {
        // Todo sale de la clave (EQA-000123#BANCO), que siempre viene en el índice, sin depender
        // de qué atributos proyecte el índice secundario.
        int separador = pagoDestino.indexOf('#');
        String llave = pagoDestino.substring(0, separador);
        Destino destino = Destino.valueOf(pagoDestino.substring(separador + 1));
        long pagoId = Long.parseLong(llave.substring(llave.lastIndexOf('-') + 1));

        if (!control.reclamar(pagoDestino)) {
            return false;   // otra corrida ya lo reclamó: no se publica de nuevo
        }
        try {
            publicador.publicar(destino, pagoId, llave);
            return true;
        } catch (RuntimeException e) {
            log.warn("No se pudo publicar {}; vuelve a PENDIENTE", pagoDestino, e);
            control.devolverAPendiente(pagoDestino);
            return false;
        }
    }
}
