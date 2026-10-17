package gt.edu.umg.aquatech.pagosconsumer;

import org.springframework.stereotype.Component;

/**
 * TODO: conectar con el cliente Feign generado desde erp-payments-api.yaml
 * (paquete gt.edu.umg.aquatech.pagosconsumer.cliente.erp.api). Mismo esqueleto que el banco,
 * pero la unicidad va en el cuerpo (documento_origen.numero), no en un header: ya viene dentro
 * del payload guardado, con el mismo valor que idempotencyKey.
 */
@Component
public class ErpDestinoClient implements DestinoClient {

    @Override
    public String destino() {
        return "ERP";
    }

    @Override
    public RespuestaDestino enviar(String payloadJson, String idempotencyKey) {
        throw new UnsupportedOperationException("Falta conectar el cliente Feign del ERP (tarea de clientes Feign)");
    }
}
