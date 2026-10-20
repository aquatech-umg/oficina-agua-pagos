package gt.edu.umg.aquatech.pagosconsumer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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

/**
 * Acceso del consumer a pagos-control y pagos-logs. Todas las transiciones de estado que hace el consumer
 * son condicionales sobre EN_COLA: EN_COLA -> ENVIADO y EN_COLA -> FALLIDO (sección 4 del diseño).
 */
@Component
public class ControlPagosRepository {

    private static final Logger log = LoggerFactory.getLogger(ControlPagosRepository.class);

    /**
     * Lo que el consumer necesita saber de un ítem de pagos-control.
     *
     * @param intentos      intentos hechos en total (nunca se reinicia: es la numeración de pagos-logs)
     * @param intentosBase  intentos que había cuando empezó la tanda actual; POST /retry lo iguala a
     *                      {@code intentos}, así cada reintento manual tiene sus 5 intentos nuevos
     */
    public record ItemControl(String pagoDestino, String idempotencyKey, String payload,
                              String estado, int intentos, int intentosBase) {

        public int intentoEnTanda() {
            return intentos + 1 - intentosBase;
        }
    }

    /** Un intento exitoso ya registrado en pagos-logs. */
    public record IntentoExitoso(int intento, String referenciaExterna) {
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
                .key(clave(pagoDestino))
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
                entero(i, "intentos"),
                entero(i, "intentosBase"));
    }

    /**
     * ¿Ya hay un intento EXITOSO en pagos-logs? Protege contra reenviar algo ya entregado cuando el mensaje
     * se repite (RabbitMQ entrega al menos una vez) y el ítem todavía no llegó a ENVIADO.
     */
    public Optional<IntentoExitoso> buscarExitoso(String pagoDestino) {
        return dynamo.query(QueryRequest.builder()
                        .tableName(tablaLogs)
                        .consistentRead(true)
                        .keyConditionExpression("pagoDestino = :pd")
                        .filterExpression("resultado = :ok")
                        .expressionAttributeValues(Map.of(
                                ":pd", AttributeValue.fromS(pagoDestino),
                                ":ok", AttributeValue.fromS("EXITOSO")))
                        .build())
                .items().stream().findFirst()
                .map(i -> new IntentoExitoso(
                        entero(i, "intento"),
                        i.containsKey("referenciaExterna") ? i.get("referenciaExterna").s() : null));
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

    /** Fallo reintentable: el ítem sigue EN_COLA (el mensaje espera en su cola de espera). */
    public void actualizarIntento(String pagoDestino, int intento, String ultimoError) {
        actualizar(pagoDestino, intento,
                "SET intentos = :i, ultimoIntentoEn = :t, actualizadoEn = :t, ultimoError = :e",
                Map.of(":e", AttributeValue.fromS(ultimoError)));
    }

    /** EN_COLA -> ENVIADO: el destino respondió 201 o 409 de duplicado. Guarda el comprobante. */
    public void marcarEnviado(String pagoDestino, int intento, String referenciaExterna) {
        Map<String, AttributeValue> valores = new HashMap<>();
        valores.put(":enviado", AttributeValue.fromS("ENVIADO"));
        String expresion = "SET #e = :enviado, intentos = :i, ultimoIntentoEn = :t, actualizadoEn = :t, enviadoEn = :t";
        if (referenciaExterna != null) {
            expresion += ", referenciaExterna = :r";
            valores.put(":r", AttributeValue.fromS(referenciaExterna));
        }
        actualizar(pagoDestino, intento, expresion + " REMOVE ultimoError", valores);
    }

    /** EN_COLA -> FALLIDO: rechazo definitivo o reintentos agotados. Solo POST /retry lo saca de ahí. */
    public void marcarFallido(String pagoDestino, int intento, String ultimoError) {
        actualizar(pagoDestino, intento,
                "SET #e = :fallido, intentos = :i, ultimoIntentoEn = :t, actualizadoEn = :t, ultimoError = :e",
                Map.of(":fallido", AttributeValue.fromS("FALLIDO"), ":e", AttributeValue.fromS(ultimoError)));
    }

    private void actualizar(String pagoDestino, int intento, String expresion,
                            Map<String, AttributeValue> valoresExtra) {
        Map<String, AttributeValue> valores = new HashMap<>(valoresExtra);
        valores.put(":i", AttributeValue.fromN(Integer.toString(intento)));
        valores.put(":t", AttributeValue.fromS(ahora()));
        valores.put(":enCola", AttributeValue.fromS("EN_COLA"));
        try {
            dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(tablaControl)
                    .key(clave(pagoDestino))
                    .updateExpression(expresion)
                    .conditionExpression("#e = :enCola")
                    .expressionAttributeNames(Map.of("#e", "estado"))
                    .expressionAttributeValues(valores)
                    .build());
        } catch (ConditionalCheckFailedException e) {
            log.warn("{} ya no estaba EN_COLA; el intento {} quedó solo en pagos-logs", pagoDestino, intento);
        }
    }

    private static int entero(Map<String, AttributeValue> item, String atributo) {
        return item.containsKey(atributo) ? Integer.parseInt(item.get(atributo).n()) : 0;
    }

    private static Map<String, AttributeValue> clave(String pagoDestino) {
        return Map.of("pagoDestino", AttributeValue.fromS(pagoDestino));
    }

    private static String ahora() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }
}
