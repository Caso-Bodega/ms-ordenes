package com.bodeganube.ordenes.service;

import com.bodeganube.ordenes.client.InventarioClient;
import com.bodeganube.ordenes.dto.ActualizarOrdenRequest;
import com.bodeganube.ordenes.dto.CrearOrdenRequest;
import com.bodeganube.ordenes.dto.ItemRequest;
import com.bodeganube.ordenes.dto.OrdenRegistrada;
import com.bodeganube.ordenes.dto.OrdenResponse;
import com.bodeganube.ordenes.exception.RecursoNoEncontradoException;
import com.bodeganube.ordenes.exception.ReglaNegocioException;
import com.bodeganube.ordenes.model.EstadoOrden;
import com.bodeganube.ordenes.model.Orden;
import com.bodeganube.ordenes.model.OrdenItem;
import com.bodeganube.ordenes.repository.OrdenRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Logica del ciclo de vida del pedido. El controller solo traduce HTTP y delega aqui.
 * Todas las respuestas salen como DTO, armadas dentro de la transaccion.
 */
@Service
@Transactional(readOnly = true)
public class OrdenService {

    private static final Logger log = LoggerFactory.getLogger(OrdenService.class);

    private final OrdenRepository ordenRepository;
    private final InventarioClient inventarioClient;

    public OrdenService(OrdenRepository ordenRepository, InventarioClient inventarioClient) {
        this.ordenRepository = ordenRepository;
        this.inventarioClient = inventarioClient;
    }

    /**
     * RF-02 y RNF-02 (Idempotent Receiver): si el externalOrderId ya se proceso, devuelve la orden
     * existente sin crear otra. Asi un reintento del webhook o de SQS no duplica la venta.
     */
    @Transactional
    public OrdenRegistrada recibirAvisoDeVenta(CrearOrdenRequest request) {
        Optional<Orden> existente = ordenRepository.findByExternalOrderId(request.externalOrderId());
        if (existente.isPresent()) {
            return new OrdenRegistrada(OrdenResponse.de(existente.get()), false);
        }

        Orden orden = new Orden();
        orden.setExternalOrderId(request.externalOrderId());
        orden.setComercioId(request.comercioId());
        orden.setEstado(EstadoOrden.PENDIENTE_STOCK);
        orden.setFechaCreacion(LocalDateTime.now());
        request.items().forEach(itemReq -> orden.agregarItem(aItem(itemReq)));

        // Regla del caso: si no hay stock, la orden NO queda disponible para picking.
        orden.setEstado(reservarStock(orden));
        return new OrdenRegistrada(OrdenResponse.de(ordenRepository.save(orden)), true);
    }

    /**
     * Para el operario: reintenta la reserva de una orden que quedo PENDIENTE_STOCK porque ms-inventario
     * no respondio cuando llego el aviso.
     */
    @Transactional
    public OrdenResponse reintentarReserva(Long id) {
        Orden orden = buscar(id);
        if (orden.getEstado() != EstadoOrden.PENDIENTE_STOCK) {
            throw new ReglaNegocioException("Solo se reintenta la reserva de una orden PENDIENTE_STOCK; estado actual: "
                    + orden.getEstado());
        }
        orden.setEstado(reservarStock(orden));
        return OrdenResponse.de(ordenRepository.save(orden));
    }

    /**
     * Sin comercioId lista todas (vista del operario). RF-06: con comercioId, cada comercio ve solo
     * sus propias ordenes (Tenant Isolation; el gateway lo forzara con el claim comercioId del JWT).
     */
    public List<OrdenResponse> listar(String comercioId) {
        List<Orden> ordenes = (comercioId == null || comercioId.isBlank())
                ? ordenRepository.findAll()
                : ordenRepository.findByComercioId(comercioId);
        return ordenes.stream()
                .map(OrdenResponse::de)
                .toList();
    }

    public OrdenResponse obtener(Long id) {
        return obtener(id, null);
    }

    /**
     * RF-06: si comercioDelUsuario viene informado (usuario COMERCIO), la orden tiene que ser suya. Si es de
     * otro comercio se responde 404, igual que si no existiera, para no revelar que ese id existe.
     */
    public OrdenResponse obtener(Long id, String comercioDelUsuario) {
        Orden orden = buscar(id);
        if (comercioDelUsuario != null && !comercioDelUsuario.equals(orden.getComercioId())) {
            throw new RecursoNoEncontradoException("No existe la orden " + id);
        }
        return OrdenResponse.de(orden);
    }

    /** RF-03: ordenes con stock ya reservado, listas para que bodega las prepare. */
    public List<OrdenResponse> disponiblesParaPicking() {
        return ordenRepository.findByEstado(EstadoOrden.LISTA_PARA_PICKING).stream()
                .map(OrdenResponse::de)
                .toList();
    }

    /** Los items solo se pueden cambiar mientras la orden espera stock; despues ya hay reservas hechas. */
    @Transactional
    public OrdenResponse actualizarItems(Long id, ActualizarOrdenRequest request) {
        Orden orden = buscar(id);
        if (orden.getEstado() != EstadoOrden.PENDIENTE_STOCK) {
            throw new ReglaNegocioException("Solo se pueden modificar los items de una orden PENDIENTE_STOCK; estado actual: "
                    + orden.getEstado());
        }
        orden.reemplazarItems(request.items().stream().map(this::aItem).toList());
        return OrdenResponse.de(ordenRepository.save(orden));
    }

    /** Aplica las transiciones definidas en EstadoOrden.puedeCambiarA. */
    @Transactional
    public OrdenResponse cambiarEstado(Long id, EstadoOrden nuevoEstado) {
        Orden orden = buscar(id);
        if (!orden.getEstado().puedeCambiarA(nuevoEstado)) {
            throw new ReglaNegocioException("No se puede pasar una orden de " + orden.getEstado() + " a " + nuevoEstado);
        }
        orden.setEstado(nuevoEstado);
        return OrdenResponse.de(ordenRepository.save(orden));
    }

    /** Una orden que ya entro a bodega (EN_PICKING o DESPACHADA) no se puede eliminar. */
    @Transactional
    public void eliminar(Long id) {
        Orden orden = buscar(id);
        if (orden.getEstado() == EstadoOrden.EN_PICKING || orden.getEstado() == EstadoOrden.DESPACHADA) {
            throw new ReglaNegocioException("No se puede eliminar la orden " + id + " porque ya esta en bodega (estado "
                    + orden.getEstado() + ")");
        }
        ordenRepository.delete(orden);
    }

    /**
     * Reserva en ms-inventario cada item de la orden y devuelve el estado que corresponde:
     * - Todos reservados: LISTA_PARA_PICKING.
     * - Un producto no existe o no tiene stock (404/409): RECHAZADA_SIN_STOCK, y se libera lo ya reservado.
     * - Inventario no responde o el circuito esta abierto: PENDIENTE_STOCK, para reintentar despues.
     */
    private EstadoOrden reservarStock(Orden orden) {
        List<OrdenItem> reservados = new ArrayList<>();
        try {
            for (OrdenItem item : orden.getItems()) {
                inventarioClient.reservar(item.getProductoId(), item.getCantidad());
                reservados.add(item);
            }
            log.info("Orden {}: stock reservado para {} item(s), queda LISTA_PARA_PICKING",
                    orden.getExternalOrderId(), reservados.size());
            return EstadoOrden.LISTA_PARA_PICKING;
        } catch (HttpClientErrorException e) {
            log.info("Orden {}: sin stock ({}), queda RECHAZADA_SIN_STOCK", orden.getExternalOrderId(), e.getStatusCode());
            liberar(reservados);
            return EstadoOrden.RECHAZADA_SIN_STOCK;
        } catch (RuntimeException e) {
            log.warn("Orden {}: ms-inventario no disponible ({}), queda PENDIENTE_STOCK",
                    orden.getExternalOrderId(), e.getMessage());
            liberar(reservados);
            return EstadoOrden.PENDIENTE_STOCK;
        }
    }

    private void liberar(List<OrdenItem> reservados) {
        for (OrdenItem item : reservados) {
            try {
                inventarioClient.liberar(item.getProductoId(), item.getCantidad());
            } catch (RuntimeException e) {
                log.error("No se pudo liberar la reserva de {} x{}: {}", item.getProductoId(), item.getCantidad(),
                        e.getMessage());
            }
        }
    }

    private Orden buscar(Long id) {
        return ordenRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe la orden " + id));
    }

    private OrdenItem aItem(ItemRequest itemReq) {
        OrdenItem item = new OrdenItem();
        item.setProductoId(itemReq.productoId());
        item.setCantidad(itemReq.cantidad());
        return item;
    }
}
