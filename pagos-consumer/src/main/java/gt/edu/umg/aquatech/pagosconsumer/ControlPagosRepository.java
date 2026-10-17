package gt.edu.umg.aquatech.pagosconsumer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/** Acceso del consumer a pagos-control (leer, actualizar intentos) y pagos-logs (solo agregar). */
@Component
public class ControlPagosRepository {

    private static final Logger log = LoggerFactory.getLogger(ControlPagosRepository.class);

    /** Lo que el consumer necesita saber de un ítem de pagos-control. */
    public record ItemControl(String pagoDestino, String idempotencyKey, String payload,
                              String estado, int intentos) {
    }

    private final DynamoDbClient dynamo;
    private final String tablaControl;
    private final String tablaLogs;

    public ControlPagosRepository(DynamoDbClient dynamo,
                                  @Value("${DYNAMODB_TABLE_CONTROL:pagos-control}") String tablaControl,
                                  @Value("${DYNAMODB_TABLE_LOGS:pagos-logs}") String tablaLogs) {
        this.dynamo = dynamo;
        this.tablaControl = tablaControl;
        this.tablaLogs = tablaLogs;
    }

    /** Lectura consistente del ítem, o null si no existe. */
    public ItemControl leer(String pagoDestino) {
        GetItemResponse r = dynamo.getItem(GetItemRequest.builder()
                .tableName(tablaControl)
                .consistentRead(true)
                .key(Map.of("pagoDestino", AttributeValue.fromS(pagoDestino)))
                .build());
        if (!r.hasItem() || r.item().isEmpty()) {
            return null;
        }
        Map<String, AttributeValue> i = r.item();
        return new ItemControl(
                pagoDestino,
                i.get("idempotencyKey").s(),
                i.get("payload").s(),
                i.get("estado").s(),
                i.containsKey("intentos") ? Integer.parseInt(i.get("intentos").n()) : 0);
    }

    /**
     * ¿Ya hay un intento EXITOSO en pagos-logs? Hace falta porque quien marca ENVIADO es un proceso
     * aparte (sprint 9): mientras tanto, el log es la prueba de que el pago ya se entregó.
     */
    public boolean existeExitoso(String pagoDestino) {
        return !dynamo.query(QueryRequest.builder()
                        .tableName(tablaLogs)
                        .consistentRead(true)
                        .keyConditionExpression("pagoDestino = :pd")
                        .filterExpression("resultado = :ok")
                        .expressionAttributeValues(Map.of(
                                ":pd", AttributeValue.fromS(pagoDestino),
                                ":ok", AttributeValue.fromS("EXITOSO")))
                        .build())
                .items().isEmpty();
    }

    /** Un registro por intento, solo se agrega. Condicional: reprocesar el mismo intento no duplica. */
    public void registrarIntento(String pagoDestino, int intento, Resultado resultado,
                                 RespuestaDestino respuesta, long duracionMs) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pagoDestino", AttributeValue.fromS(pagoDestino));
        item.put("intento", AttributeValue.fromN(Integer.toString(intento)));
        item.put("resultado", AttributeValue.fromS(resultado.esExito() ? "EXITOSO" : "FALLIDO"));
        item.put("httpStatus", AttributeValue.fromN(Integer.toString(respuesta.httpStatus())));
        item.put("reintentable", AttributeValue.fromBool(resultado == Resultado.FALLIDO_REINTENTABLE));
        item.put("duracionMs", AttributeValue.fromN(Long.toString(duracionMs)));
        item.put("registradoEn", AttributeValue.fromS(ahora()));
        if (respuesta.codigoError() != null) {
            item.put("codigoError", AttributeValue.fromS(respuesta.codigoError()));
        }
        if (respuesta.referenciaExterna() != null) {
            item.put("referenciaExterna", AttributeValue.fromS(respuesta.referenciaExterna()));
        }
        try {
            dynamo.putItem(PutItemRequest.builder()
                    .tableName(tablaLogs)
                    .item(item)
                    .conditionExpression("attribute_not_exists(pagoDestino)")
                    .build());
        } catch (ConditionalCheckFailedException e) {
            log.warn("El intento {} de {} ya estaba registrado", intento, pagoDestino);
        }
    }

    /**
     * Actualiza intentos / ultimoError del ítem. Solo si sigue EN_COLA. Si el resultado es un fallo
     * definitivo, también lo pasa a FALLIDO (el consumer es el único que escribe ese estado).
     * El paso a ENVIADO NO lo hace el consumer.
     */
    public void actualizarTrasIntento(String pagoDestino, int intento, Resultado resultado, String ultimoError) {
        Map<String, AttributeValue> valores = new HashMap<>();
        valores.put(":i", AttributeValue.fromN(Integer.toString(intento)));
        valores.put(":t", AttributeValue.fromS(ahora()));
        valores.put(":enCola", AttributeValue.fromS("EN_COLA"));

        String expresion;
        if (resultado.esExito()) {
            expresion = "SET intentos = :i, actualizadoEn = :t REMOVE ultimoError";
        } else if (resultado == Resultado.FALLIDO_DEFINITIVO) {
            expresion = "SET intentos = :i, actualizadoEn = :t, ultimoError = :e, #e = :fallido";
            valores.put(":e", AttributeValue.fromS(ultimoError));
            valores.put(":fallido", AttributeValue.fromS("FALLIDO"));
        } else {
            expresion = "SET intentos = :i, actualizadoEn = :t, ultimoError = :e";
            valores.put(":e", AttributeValue.fromS(ultimoError));
        }

        try {
            dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(tablaControl)
                    .key(Map.of("pagoDestino", AttributeValue.fromS(pagoDestino)))
                    .updateExpression(expresion)
                    .conditionExpression("#e = :enCola")
                    .expressionAttributeNames(Map.of("#e", "estado"))
                    .expressionAttributeValues(valores)
                    .build());
        } catch (ConditionalCheckFailedException e) {
            log.warn("{} ya no estaba EN_COLA; el intento {} quedó solo en pagos-logs", pagoDestino, intento);
        }
    }

    private static String ahora() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }
}
