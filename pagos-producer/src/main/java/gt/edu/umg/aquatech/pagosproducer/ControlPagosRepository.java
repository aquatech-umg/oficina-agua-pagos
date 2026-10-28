package gt.edu.umg.aquatech.pagosproducer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
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

/**
 * Tabla pagos-control. Las transiciones del producer son escrituras condicionales: crear PENDIENTE
 * y reclamar PENDIENTE -> EN_COLA.
 */
@Component
public class ControlPagosRepository {

    private static final Logger log = LoggerFactory.getLogger(ControlPagosRepository.class);

    private static final String INDICE_ESTADO = "estado-actualizadoEn-index";
    private static final String CURSOR = "_CURSOR";

    private final DynamoDbClient dynamo;
    private final String tabla;

    public ControlPagosRepository(DynamoDbClient dynamo,
                                  @Value("${DYNAMODB_TABLE_CONTROL:pagos-control}") String tabla) {
        this.dynamo = dynamo;
        this.tabla = tabla;
    }


    /** Último pago del monolito ya procesado; 0 si todavía no existe el ítem _CURSOR. */
    public long leerCursor() {
        GetItemResponse r = dynamo.getItem(GetItemRequest.builder()
                .tableName(tabla)
                .consistentRead(true)
                .key(clave(CURSOR))
                .build());
        if (!r.hasItem() || r.item().isEmpty() || !r.item().containsKey("ultimoPagoId")) {
            return 0L;
        }
        return Long.parseLong(r.item().get("ultimoPagoId").n());
    }

    /** Solo avanza: si otra corrida ya dejó un cursor mayor, no hace nada. */
    public void avanzarCursor(long ultimoPagoId) {
        try {
            dynamo.putItem(PutItemRequest.builder()
                    .tableName(tabla)
                    .item(Map.of(
                            "pagoDestino", AttributeValue.fromS(CURSOR),
                            "ultimoPagoId", AttributeValue.fromN(Long.toString(ultimoPagoId)),
                            "actualizadoEn", AttributeValue.fromS(ahora())))
                    .conditionExpression("attribute_not_exists(pagoDestino) OR ultimoPagoId < :nuevo")
                    .expressionAttributeValues(Map.of(":nuevo", AttributeValue.fromN(Long.toString(ultimoPagoId))))
                    .build());
        } catch (ConditionalCheckFailedException e) {
            log.debug("El cursor ya estaba en un valor mayor que {}", ultimoPagoId);
        }
    }


    /**
     * PutItem condicional: si ya existe el ítem (pago, destino) no lo toca, así el payload queda
     * congelado con el primer valor. Devuelve true si lo creó.
     */
    public boolean crearSiNoExiste(Destino destino, long pagoId, String idempotencyKey, String payloadJson) {
        String ahora = ahora();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pagoDestino", AttributeValue.fromS(pagoDestino(idempotencyKey, destino)));
        item.put("pagoId", AttributeValue.fromN(Long.toString(pagoId)));
        item.put("destino", AttributeValue.fromS(destino.name()));
        item.put("idempotencyKey", AttributeValue.fromS(idempotencyKey));
        item.put("payload", AttributeValue.fromS(payloadJson));
        item.put("estado", AttributeValue.fromS("PENDIENTE"));
        item.put("intentos", AttributeValue.fromN("0"));
        item.put("creadoEn", AttributeValue.fromS(ahora));
        item.put("actualizadoEn", AttributeValue.fromS(ahora));
        try {
            dynamo.putItem(PutItemRequest.builder()
                    .tableName(tabla)
                    .item(item)
                    .conditionExpression("attribute_not_exists(pagoDestino)")
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }


    /** Ítems PENDIENTE, vía el índice estado-actualizadoEn-index (no recorre la tabla). */
    public List<Map<String, AttributeValue>> pendientes(int limite) {
        return dynamo.query(QueryRequest.builder()
                        .tableName(tabla)
                        .indexName(INDICE_ESTADO)
                        .keyConditionExpression("#e = :pendiente")
                        .expressionAttributeNames(Map.of("#e", "estado"))
                        .expressionAttributeValues(Map.of(":pendiente", AttributeValue.fromS("PENDIENTE")))
                        .limit(limite)
                        .build())
                .items();
    }

    /** PENDIENTE -> EN_COLA con condición: si dos corridas coinciden, solo una gana cada ítem. */
    public boolean reclamar(String pagoDestino) {
        String ahora = ahora();
        try {
            dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(tabla)
                    .key(clave(pagoDestino))
                    .updateExpression("SET #e = :enCola, encoladoEn = :t, actualizadoEn = :t")
                    .conditionExpression("#e = :pendiente")
                    .expressionAttributeNames(Map.of("#e", "estado"))
                    .expressionAttributeValues(Map.of(
                            ":enCola", AttributeValue.fromS("EN_COLA"),
                            ":pendiente", AttributeValue.fromS("PENDIENTE"),
                            ":t", AttributeValue.fromS(ahora)))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    /**
     * Si la publicación falla después de reclamar, el ítem vuelve a PENDIENTE para no quedar
     * EN_COLA sin mensaje. Si el mensaje sí había salido, el duplicado es inofensivo: el consumer
     * descarta lo ya entregado y los destinos son idempotentes.
     */
    public void devolverAPendiente(String pagoDestino) {
        try {
            dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(tabla)
                    .key(clave(pagoDestino))
                    .updateExpression("SET #e = :pendiente, actualizadoEn = :t REMOVE encoladoEn")
                    .conditionExpression("#e = :enCola")
                    .expressionAttributeNames(Map.of("#e", "estado"))
                    .expressionAttributeValues(Map.of(
                            ":enCola", AttributeValue.fromS("EN_COLA"),
                            ":pendiente", AttributeValue.fromS("PENDIENTE"),
                            ":t", AttributeValue.fromS(ahora())))
                    .build());
        } catch (ConditionalCheckFailedException e) {
            log.debug("{} ya no estaba EN_COLA; no se devuelve a PENDIENTE", pagoDestino);
        }
    }


    /** Lectura consistente de un ítem (pago, destino), o null si no existe. */
    public Map<String, AttributeValue> leer(String pagoDestino) {
        GetItemResponse r = dynamo.getItem(GetItemRequest.builder()
                .tableName(tabla)
                .consistentRead(true)
                .key(clave(pagoDestino))
                .build());
        return (r.hasItem() && !r.item().isEmpty()) ? r.item() : null;
    }

    /**
     * POST /retry: FALLIDO -> PENDIENTE (la única salida de FALLIDO). Con condición: si el ítem ya no está
     * FALLIDO no hace nada y devuelve false. Deja intentosBase = intentos para que el consumer cuente 5 intentos
     * nuevos, sin reiniciar la numeración de pagos-logs (que es la clave de cada intento).
     */
    public boolean reintentar(String pagoDestino) {
        try {
            dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(tabla)
                    .key(clave(pagoDestino))
                    .updateExpression("SET #e = :pendiente, intentosBase = intentos + :cero, actualizadoEn = :t "
                            + "REMOVE ultimoError, encoladoEn")
                    .conditionExpression("#e = :fallido")
                    .expressionAttributeNames(Map.of("#e", "estado"))
                    .expressionAttributeValues(Map.of(
                            ":pendiente", AttributeValue.fromS("PENDIENTE"),
                            ":fallido", AttributeValue.fromS("FALLIDO"),
                            ":cero", AttributeValue.fromN("0"),
                            ":t", AttributeValue.fromS(ahora())))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }


    private static String pagoDestino(String idempotencyKey, Destino destino) {
        return idempotencyKey + "#" + destino.name();
    }

    private static Map<String, AttributeValue> clave(String pagoDestino) {
        return Map.of("pagoDestino", AttributeValue.fromS(pagoDestino));
    }

    private static String ahora() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }
}
