package gt.edu.umg.aquatech.pagosconsumer;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

@Configuration
public class ConsumerConfig {

    public static final String COLA_BANCO = "pagos.banco";
    public static final String COLA_ERP = "pagos.erp";
    public static final String COLA_DLQ = "pagos.dlq";

    /** Cola principal del destino ("BANCO" o "ERP"). */
    public static String colaPrincipal(String destino) {
        return "BANCO".equals(destino) ? COLA_BANCO : COLA_ERP;
    }

    /** Cola de espera de un escalón, p. ej. pagos.banco.retry.4s */
    public static String colaEspera(String colaPrincipal, int segundos) {
        return "%s.retry.%ds".formatted(colaPrincipal, segundos);
    }

    /**
     * Las colas principales se declaran IGUAL que en el producer (durables y sin argumentos extra): si los
     * argumentos difieren, RabbitMQ responde PRECONDITION_FAILED. Por eso la espera y el dead-letter viven en
     * colas aparte, y las principales no se tocan.
     */
    @Bean
    Queue colaBanco() {
        return QueueBuilder.durable(COLA_BANCO).build();
    }

    @Bean
    Queue colaErp() {
        return QueueBuilder.durable(COLA_ERP).build();
    }

    /**
     * Una cola de espera por destino y por escalón. No tienen consumidor: el mensaje espera su TTL y
     * RabbitMQ lo devuelve solo a la cola principal (dead-letter hacia el exchange por defecto, con la cola
     * principal como routing key). Más pagos.dlq, donde terminan los pagos que se rinden.
     */
    @Bean
    Declarables colasDeEsperaYDeadLetter() {
        List<Declarable> colas = new ArrayList<>();
        for (String principal : List.of(COLA_BANCO, COLA_ERP)) {
            for (int segundos : PoliticaReintentos.ESCALONES_SEGUNDOS) {
                colas.add(QueueBuilder.durable(colaEspera(principal, segundos))
                        .ttl(segundos * 1000)
                        .deadLetterExchange("")
                        .deadLetterRoutingKey(principal)
                        .build());
            }
        }
        colas.add(QueueBuilder.durable(COLA_DLQ).build());
        return new Declarables(colas);
    }

    /** Local: DYNAMODB_ENDPOINT=http://localhost:8012. En AWS se omite el endpoint. */
    @Bean
    DynamoDbClient dynamoDbClient(
            @Value("${DYNAMODB_ENDPOINT:}") String endpoint,
            @Value("${AWS_REGION:us-east-1}") String region) {
        DynamoDbClientBuilder builder = DynamoDbClient.builder().region(Region.of(region));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }
}
