package gt.edu.umg.aquatech.pagosproducer;

import gt.edu.umg.aquatech.pagosproducer.api.model.Error;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Errores con el formato Error del contrato payments-api.yaml. */
@RestControllerAdvice
public class ManejadorErrores {

    @ExceptionHandler(TransmisionesService.NoEncontrada.class)
    ResponseEntity<Error> noEncontrada(TransmisionesService.NoEncontrada e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Error(404, e.getMessage()));
    }

    @ExceptionHandler(TransmisionesService.SinFallidos.class)
    ResponseEntity<Error> sinFallidos(TransmisionesService.SinFallidos e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Error(409, e.getMessage()));
    }
}
