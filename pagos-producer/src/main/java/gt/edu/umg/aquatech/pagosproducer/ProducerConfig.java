package gt.edu.umg.aquatech.pagosproducer;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import javax.sql.DataSource;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

@Configuration
@EnableScheduling
public class ProducerConfig {

    /**
     * Colas durables; el consumer las declara igual (si los argumentos difieren,
     * RabbitMQ responde PRECONDITION_FAILED).
     */
    @Bean
    Queue colaBanco() {
        return QueueBuilder.durable(Destino.BANCO.cola()).build();
    }

    @Bean
    Queue colaErp() {
        return QueueBuilder.durable(Destino.ERP.cola()).build();
    }

    /**
     * El endpoint solo se define en local (DynamoDB Local). En ese caso las llaves se pasan
     * explícitamente: el SDK de AWS no lee las propiedades de Spring (como las del .env), solo
     * variables del sistema o ~/.aws/credentials. DynamoDB Local acepta cualquier llave.
     * Sin endpoint (AWS real) se usa la cadena de credenciales por defecto del SDK.
     */
    @Bean
    DynamoDbClient dynamoDbClient(
            @Value("${DYNAMODB_ENDPOINT:}") String endpoint,
            @Value("${AWS_REGION:us-east-1}") String region,
            @Value("${AWS_ACCESS_KEY_ID:local}") String accessKey,
            @Value("${AWS_SECRET_ACCESS_KEY:local}") String secretKey) {
        DynamoDbClientBuilder builder = DynamoDbClient.builder().region(Region.of(region));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)));
        }
        return builder.build();
    }

    @Bean
    PayloadFactory payloadFactory(@Value("${PAGOS_EQUIPO:EQA}") String equipo,
                                  @Value("${TEAM_CODE:EQUIPO_A}") String teamCode) {
        return new PayloadFactory(equipo, teamCode);
    }

    /** Réplica de solo lectura: usuario 'lectura', conexión marcada como read-only. */
    @Bean
    DataSource replicaDataSource(
            @Value("${DB_REPLICA_HOST:localhost}") String host,
            @Value("${DB_REPLICA_PORT:3307}") int port,
            @Value("${DB_REPLICA_NAME:oficina_agua}") String base,
            @Value("${DB_LECTURA_USER:lectura}") String usuario,
            @Value("${DB_LECTURA_PASSWORD:}") String clave) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:mysql://%s:%d/%s?allowPublicKeyRetrieval=true&useSSL=false".formatted(host, port, base));
        ds.setUsername(usuario);
        ds.setPassword(clave);
        ds.setReadOnly(true);
        ds.setMaximumPoolSize(2);
        ds.setPoolName("replica-lectura");
        return ds;
    }

    @Bean
    JdbcClient replicaJdbc(DataSource replicaDataSource) {
        return JdbcClient.create(replicaDataSource);
    }
}