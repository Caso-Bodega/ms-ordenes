package com.bodeganube.ordenes.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Cliente HTTP hacia ms-inventario: unica dependencia sincrona entre microservicios de negocio
 * (seccion 4.2 del informe de arquitectura).
 *
 * - Timeouts cortos: si inventario no responde, ms-ordenes no queda colgado.
 * - @Retry: reintenta hasta 3 veces solo cuando inventario no respondio (caido, timeout o error 5xx).
 * - @CircuitBreaker: si la mitad de las ultimas llamadas fallan, deja de llamar a inventario por 20 s
 *   (circuito abierto) para no saturarlo mientras se recupera. Configuracion en application.yml.
 *
 * Un 404 (producto inexistente) o 409 (stock insuficiente) NO es una falla: es la respuesta de negocio y
 * llega como HttpClientErrorException.
 */
@Component
public class InventarioClient {

    private final RestClient restClient;

    public InventarioClient(RestClient.Builder builder, @Value("${app.inventario.url}") String inventarioUrl) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(2000);
        fabrica.setReadTimeout(3000);
        this.restClient = builder.baseUrl(inventarioUrl).requestFactory(fabrica).build();
    }

    /** RF-04: mueve unidades de "disponible" a "reservado" en ms-inventario. */
    @Retry(name = "inventario")
    @CircuitBreaker(name = "inventario")
    public void reservar(String sku, int cantidad) {
        restClient.post()
                .uri("/api/v1/productos/{sku}/reservar", sku)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("cantidad", cantidad))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Compensacion: devuelve a "disponible" lo que se reservo si otro item de la misma orden no tenia stock.
     * Solo lleva @Retry y no @CircuitBreaker: deshacer una reserva no se debe bloquear porque el circuito se
     * haya abierto mientras tanto, o esas unidades quedarian reservadas para siempre.
     */
    @Retry(name = "inventario")
    public void liberar(String sku, int cantidad) {
        restClient.post()
                .uri("/api/v1/productos/{sku}/liberar", sku)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("cantidad", cantidad))
                .retrieve()
                .toBodilessEntity();
    }
}
