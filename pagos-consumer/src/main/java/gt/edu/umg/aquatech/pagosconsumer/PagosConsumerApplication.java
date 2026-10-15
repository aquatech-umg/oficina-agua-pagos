package gt.edu.umg.aquatech.pagosconsumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class PagosConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PagosConsumerApplication.class, args);
    }

}
