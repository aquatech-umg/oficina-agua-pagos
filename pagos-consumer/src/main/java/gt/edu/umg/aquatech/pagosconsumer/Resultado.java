package gt.edu.umg.aquatech.pagosconsumer;

/** Cómo se interpreta la respuesta de un destino (sección 6 del diseño). */
public enum Resultado {
    /** 201: entregado. */
    EXITOSO,
    /** 409 de duplicado: el destino ya lo tenía, se trata como éxito. */
    EXITOSO_DUPLICADO,
    /** 400, 403, 422 o 409 de llave reutilizada: no se reintenta, el ítem pasa a FALLIDO. */
    FALLIDO_DEFINITIVO,
    /** 429, 500, 502, 503, 504 o timeout: se podría reintentar (las colas de espera son del sprint 9). */
    FALLIDO_REINTENTABLE;

    public boolean esExito() {
        return this == EXITOSO || this == EXITOSO_DUPLICADO;
    }
}
