package gt.edu.umg.aquatech.pagosconsumer;

import java.nio.charset.StandardCharsets;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageBuilderSupport;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publica en las colas de espera y en pagos.dlq con mensajes persistentes y confirmación del broker.
 * Si RabbitMQ no confirma lanza excepción y el listener no hace ack (no se pierde el pago).
 */
@Component
public class EncoladorPagos {

    private static final long TIMEOUT_CONFIRMACION_MS = 5_000;

    private final RabbitTemplate rabbit;

    public EncoladorPagos(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    /** Reintento con espera: el mensaje vuelve solo a la cola principal cuando vence el TTL del escalón. */
    public void aEspera(MensajePago mensaje, int esperaSegundos) {
        String principal = ConsumerConfig.colaPrincipal(mensaje.destino());
        publicar(ConsumerConfig.colaEspera(principal, esperaSegundos), construir(mensaje).build());
    }

    /** El pago se rinde: va a pagos.dlq con el motivo en los headers (el ítem queda FALLIDO). */
    public void aDeadLetter(MensajePago mensaje, String motivo, int intentos, int httpStatus, String codigoError) {
        MessageBuilderSupport<Message> constructor = construir(mensaje)
                .setHeader("x-motivo", motivo)
                .setHeader("x-intentos", intentos)
                .setHeader("x-http-status", httpStatus)
                .setHeader("x-cola-origen", ConsumerConfig.colaPrincipal(mensaje.destino()));
        if (codigoError != null) {
            constructor.setHeader("x-codigo-error", codigoError);
        }
        publicar(ConsumerConfig.COLA_DLQ, constructor.build());
    }

    private static MessageBuilderSupport<Message> construir(MensajePago mensaje) {
        return MessageBuilder.withBody(mensaje.aJson().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    }

    private void publicar(String cola, Message mensaje) {
        rabbit.invoke(operaciones -> {
            operaciones.send("", cola, mensaje);
            operaciones.waitForConfirmsOrDie(TIMEOUT_CONFIRMACION_MS);
            return null;
        });
    }
}
