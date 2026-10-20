package gt.edu.umg.aquatech.pagosconsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gt.edu.umg.aquatech.pagosconsumer.PoliticaReintentos.Accion;
import gt.edu.umg.aquatech.pagosconsumer.PoliticaReintentos.Decision;
import org.junit.jupiter.api.Test;

class PoliticaReintentosTest {

    private static Decision transitorio(int intento, Integer retryAfter) {
        return PoliticaReintentos.decidirTrasFallo(Resultado.FALLIDO_REINTENTABLE, intento, retryAfter);
    }

    @Test
    void laEsperaCreceEntreIntentos() {
        assertEquals(2, transitorio(1, null).esperaSegundos());
        assertEquals(4, transitorio(2, null).esperaSegundos());
        assertEquals(8, transitorio(3, null).esperaSegundos());
        assertEquals(16, transitorio(4, null).esperaSegundos());
        assertEquals(Accion.ESPERAR, transitorio(4, null).accion());
    }

    @Test
    void alQuintoIntentoSeRindeAlDeadLetter() {
        Decision d = transitorio(5, null);
        assertEquals(Accion.DEAD_LETTER, d.accion());
        assertEquals("REINTENTOS_AGOTADOS", d.motivo());
    }

    @Test
    void unErrorDefinitivoVaAlDeadLetterDeInmediato() {
        Decision d = PoliticaReintentos.decidirTrasFallo(Resultado.FALLIDO_DEFINITIVO, 1, null);
        assertEquals(Accion.DEAD_LETTER, d.accion());
        assertEquals("ERROR_DEFINITIVO", d.motivo());
    }

    @Test
    void seRespetaRetryAfterSubiendoAlEscalonQueLoCubre() {
        assertEquals(8, transitorio(1, 7).esperaSegundos());     // pidió 7 s: no basta 2 ni 4
        assertEquals(2, transitorio(1, 1).esperaSegundos());     // pidió menos que la espera propia
        assertEquals(16, transitorio(2, 16).esperaSegundos());
        assertEquals(32, transitorio(4, 20).esperaSegundos());   // max(16, 20) = 20 -> escalón de 32
        assertEquals(32, transitorio(1, 100).esperaSegundos());  // tope: el último escalón
    }

    @Test
    void unExitoNoTienePoliticaDeReintentos() {
        assertThrows(IllegalArgumentException.class,
                () -> PoliticaReintentos.decidirTrasFallo(Resultado.EXITOSO, 1, null));
    }
}
