package gt.edu.umg.aquatech.pagosproducer;

import java.nio.charset.StandardCharsets;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publica un mensaje por destino con persistencia y confirmación del broker;
 * si RabbitMQ no confirma lanza excepción.
 */
@Component
public class PublicadorPagos {

    private static final long TIMEOUT_CONFIRMACION_MS = 5_000;

    private final RabbitTemplate rabbit;

    public PublicadorPagos(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    public void publicar(Destino destino, long pagoId, String idempotencyKey) {
        // El cuerpo solo lleva la referencia: el payload completo vive en pagos-control.
        String json = "{\"pagoId\":%d,\"destino\":\"%s\",\"idempotencyKey\":\"%s\"}"
                .formatted(pagoId, destino.name(), idempotencyKey);
        Message mensaje = MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();

        rabbit.invoke(operaciones -> {
            operaciones.send("", destino.cola(), mensaje);
            operaciones.waitForConfirmsOrDie(TIMEOUT_CONFIRMACION_MS);
            return null;
        });
    }
}
