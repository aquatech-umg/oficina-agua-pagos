package gt.edu.umg.aquatech.pagosconsumer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;


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
    private final EncoladorPagos encolador;
    private final Map<String, DestinoClient> clientes;

    public EnvioPagoService(ControlPagosRepository control, EncoladorPagos encolador, List<DestinoClient> clientes) {
        this.control = control;
        this.encolador = encolador;
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

        // RabbitMQ entrega al menos una vez: un mensaje repetido de algo ya resuelto
        // se descarta sin llamar al destino.
        if ("ENVIADO".equals(item.estado()) || "FALLIDO".equals(item.estado())) {
            log.info("{} ya está {}; se descarta el mensaje", pagoDestino, item.estado());
            return Decision.ACK;
        }
        Optional<ControlPagosRepository.IntentoExitoso> yaEntregado = control.buscarExitoso(pagoDestino);
        if (yaEntregado.isPresent()) {
            log.info("{} ya tiene un intento exitoso en pagos-logs; se descarta el mensaje y se marca ENVIADO", pagoDestino);
            control.marcarEnviado(pagoDestino, item.intentos(), yaEntregado.get().referenciaExterna());
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
        RespuestaDestino registrada = new RespuestaDestino(respuesta.httpStatus(), codigoError,
                respuesta.referenciaExterna(), respuesta.cuerpo(), respuesta.retryAfterSegundos());

        control.registrarIntento(pagoDestino, intento, resultado, registrada, duracionMs);
        log.info("{} intento {} -> {} (HTTP {})", pagoDestino, intento, resultado, respuesta.httpStatus());

        if (resultado.esExito()) {
            control.marcarEnviado(pagoDestino, intento, respuesta.referenciaExterna());
            return Decision.ACK;
        }

        String ultimoError = DetalleError.ultimoError(registrada);
        PoliticaReintentos.Decision decision = PoliticaReintentos.decidirTrasFallo(
                resultado, item.intentoEnTanda(), respuesta.retryAfterSegundos());

        if (decision.accion() == PoliticaReintentos.Accion.ESPERAR) {
            encolador.aEspera(mensaje, decision.esperaSegundos());
            control.actualizarIntento(pagoDestino, intento, ultimoError);
            log.warn("{} falló ({}); reintento {} de {} en {} s", pagoDestino, ultimoError,
                    item.intentoEnTanda() + 1, PoliticaReintentos.MAX_INTENTOS, decision.esperaSegundos());
        } else {
            encolador.aDeadLetter(mensaje, decision.motivo(), intento, respuesta.httpStatus(), codigoError);
            control.marcarFallido(pagoDestino, intento, ultimoError);
            log.error("{} -> pagos.dlq y FALLIDO ({}: {})", pagoDestino, decision.motivo(), ultimoError);
        }
        return Decision.ACK;
    }
}
