package gt.edu.umg.aquatech.pagosconsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;

class AutenticacionDestinosConfigTest {

    private final AutenticacionDestinosConfig config = new AutenticacionDestinosConfig();

    @Test
    void sinUsuarioNoAgregaElHeaderAuthorization() {
        RequestInterceptor interceptor = config.autenticacionBasica("", "");
        RequestTemplate plantilla = new RequestTemplate();
        interceptor.apply(plantilla);
        assertFalse(plantilla.headers().containsKey("Authorization"));
    }

    @Test
    void conUsuarioAgregaAuthorizationBasic() {
        RequestInterceptor interceptor = config.autenticacionBasica("usuario", "clave");
        RequestTemplate plantilla = new RequestTemplate();
        interceptor.apply(plantilla);
        // base64("usuario:clave")
        assertEquals(List.of("Basic dXN1YXJpbzpjbGF2ZQ=="), List.copyOf(plantilla.headers().get("Authorization")));
    }
}
