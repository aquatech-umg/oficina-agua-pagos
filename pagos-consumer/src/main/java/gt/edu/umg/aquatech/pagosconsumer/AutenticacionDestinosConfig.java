package gt.edu.umg.aquatech.pagosconsumer;

import feign.RequestInterceptor;
import feign.auth.BasicAuthRequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class AutenticacionDestinosConfig {

    @Bean
    RequestInterceptor autenticacionBasica(@Value("${SIMULADOR_USUARIO:}") String usuario,
                                           @Value("${SIMULADOR_PASSWORD:}") String password) {
        if (usuario.isBlank()) {
            return plantilla -> {
            
            };
        }
        return new BasicAuthRequestInterceptor(usuario, password);
    }
}
