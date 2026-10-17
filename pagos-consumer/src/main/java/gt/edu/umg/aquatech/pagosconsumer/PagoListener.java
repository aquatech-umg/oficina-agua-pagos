package gt.edu.umg.aquatech.pagosconsumer;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Toma los mensajes de pagos.banco y pagos.erp con ack manual: ack solo tras registrar el resultado. */
@Component
public class PagoListener {

    private static final Logger log = LoggerFactory.getLogger(PagoListener.class);

    private final EnvioPagoService servicio;
    private final JsonMapper mapper;

    public PagoListener(EnvioPagoService servicio, JsonMapper mapper) {
        this.servicio = servicio;
        this.mapper = mapper;
    }

    @RabbitListener(queues = {ConsumerConfig.COLA_BANCO, ConsumerConfig.COLA_ERP}, ackMode = "MANUAL")
    public void alRecibir(Message mensaje, Channel canal) throws IOException {
        long etiqueta = mensaje.getMessageProperties().getDeliveryTag();

        MensajePago pago;
        try {
            pago = mapper.readValue(mensaje.getBody(), MensajePago.class);
        } catch (RuntimeException e) {
            log.error("Mensaje ilegible; se rechaza sin reencolar", e);
            canal.basicReject(etiqueta, false);
            return;
        }

        try {
            EnvioPagoService.Decision decision = servicio.procesar(pago);
            if (decision == EnvioPagoService.Decision.ACK) {
                canal.basicAck(etiqueta, false);
            } else {
                canal.basicReject(etiqueta, false);
            }
        } catch (RuntimeException e) {
            // Falla inesperada (DynamoDB caído, cliente sin conectar...): no se pierde el mensaje.
            // Cuidado: reencolar puede girar en bucle mientras dure la falla (el sprint 9 lo reemplaza
            // por colas de espera + dead-letter).
            log.error("Falla procesando {}; el mensaje vuelve a la cola", pago.pagoDestino(), e);
            canal.basicNack(etiqueta, false, true);
        }
    }
}
