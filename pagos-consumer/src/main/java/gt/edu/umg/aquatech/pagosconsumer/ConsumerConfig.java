package gt.edu.umg.aquatech.pagosconsumer;

import java.net.URI;
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

    /** Mismas colas y mismos argumentos que declara el producer (si no, PRECONDITION_FAILED). */
    @Bean
    Queue colaBanco() {
        return QueueBuilder.durable(COLA_BANCO).build();
    }

    @Bean
    Queue colaErp() {
        return QueueBuilder.durable(COLA_ERP).build();
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
