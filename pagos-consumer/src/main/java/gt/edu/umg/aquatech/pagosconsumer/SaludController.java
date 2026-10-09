package gt.edu.umg.aquatech.pagosconsumer;

import java.util.Map;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SaludController {

    private final RabbitTemplate rabbit;

    public SaludController(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    @GetMapping("/api/salud")
    public Map<String, Object> salud() {
        // Abre un canal real contra RabbitMQ: si no hay conexion, esto falla y se nota.
        String version = rabbit.execute(channel ->
                String.valueOf(channel.getConnection().getServerProperties().get("version")));

        return Map.of(
                "servicio", "pagos-consumer",
                "rabbitmq", version);
    }
}
