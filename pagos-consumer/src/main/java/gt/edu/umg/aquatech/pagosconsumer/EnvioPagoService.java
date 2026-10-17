package gt.edu.umg.aquatech.pagosconsumer;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * AQ-121. Procesa un mensaje: lee el ítem, evita reenviar lo ya entregado, envía el payload guardado
 * con la misma llave, y registra el resultado de cada intento antes de que el listener haga ack.
 */
@Service
public class EnvioPagoService {

    /** Qué debe hacer el listener con el mensaje, una vez procesado. */
    public enum Decision {
        /** Ack: ya se registró el resultado, o no hay nada más que hacer con este mensaje. */
        ACK,
        /** Rechazo sin reencolar: el mensaje no se puede procesar nunca (ítem inexistente o destino desconocido). */
        RECHAZAR
    }

    private static final Logger log = LoggerFactory.getLogger(EnvioPagoService.class);

    private final ControlPagosRepository control;
    private final Map<String, DestinoClient> clientes;

    public EnvioPagoService(ControlPagosRepository control, List<DestinoClient> clientes) {
        this.control = control;
        this.clientes = clientes.stream().collect(Collectors.toMap(DestinoClient::destino, Function.identity()));
    }

    public Decision procesar(MensajePago mensaje) {
        String pagoDestino = mensaje.pagoDestino();

        ControlPagosRepository.ItemControl item = control.leer(pagoDestino);
        if (item == null) {
            log.error("No existe el ítem {} en pagos-control; se rechaza el mensaje", pagoDestino);
            return Decision.RECHAZAR;
        }
        DestinoClient cliente = clientes.get(mensaje.destino());
        if (cliente == null) {
            log.error("Destino desconocido '{}' en el mensaje de {}", mensaje.destino(), pagoDestino);
            return Decision.RECHAZAR;
        }

        // Mensaje repetido de algo ya resuelto: descartar con ack, sin llamar al destino.
        // (RabbitMQ entrega "al menos una vez", así que esto es normal.)
        if ("ENVIADO".equals(item.estado()) || "FALLIDO".equals(item.estado())) {
            log.info("{} ya está {}; se descarta el mensaje", pagoDestino, item.estado());
            return Decision.ACK;
        }
        if (control.existeExitoso(pagoDestino)) {
            log.info("{} ya tiene un intento exitoso en pagos-logs; se descarta el mensaje", pagoDestino);
            return Decision.ACK;
        }

        int intento = item.intentos() + 1;
        long inicio = System.nanoTime();
        RespuestaDestino respuesta = cliente.enviar(item.payload(), item.idempotencyKey());
        long duracionMs = (System.nanoTime() - inicio) / 1_000_000;

        Resultado resultado = ClasificadorRespuesta.clasificar(respuesta.httpStatus(), respuesta.cuerpo());
        String codigoError = respuesta.codigoError() != null
                ? respuesta.codigoError()
                : ClasificadorRespuesta.extraerCodigo(respuesta.cuerpo());
        RespuestaDestino registrada = new RespuestaDestino(
                respuesta.httpStatus(), codigoError, respuesta.referenciaExterna(), respuesta.cuerpo());

        control.registrarIntento(pagoDestino, intento, resultado, registrada, duracionMs);
        control.actualizarTrasIntento(pagoDestino, intento, resultado, ultimoError(registrada));

        log.info("{} intento {} -> {} (HTTP {})", pagoDestino, intento, resultado, respuesta.httpStatus());
        if (resultado == Resultado.FALLIDO_REINTENTABLE) {
            // Sprint 9: aquí irá a la cola de espera con retardo creciente. Por ahora el ítem queda
            // EN_COLA con intentos y ultimoError al día, y el mensaje se da por atendido.
            log.warn("{} falló con un error reintentable; queda EN_COLA hasta que exista la cola de espera", pagoDestino);
        }
        return Decision.ACK;
    }

    private static String ultimoError(RespuestaDestino r) {
        if (r.codigoError() != null) {
            return r.codigoError();
        }
        return r.httpStatus() == 0 ? "TIMEOUT_O_CONEXION" : "HTTP_" + r.httpStatus();
    }
}
