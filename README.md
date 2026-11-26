# ms-ordenes

Microservicio de **gestión del ciclo de vida del pedido** del caso BodegaNube (JVY0101). Recibe los avisos
de venta, evita duplicados por reintentos y controla los estados de cada orden hasta que se despacha.

| | |
|---|---|
| Puerto | `8082` |
| Base de datos | PostgreSQL `bodeganube_ordenes` (una base por microservicio) |
| Stack | Java 17, Spring Boot 3.2, Spring Data JPA, Bean Validation, Maven Wrapper |

## Requerimientos que cubre
- **RF-02**: recepción de avisos de venta externos (webhook que llega vía Lambda y SQS).
- **RF-03**: consulta de las órdenes disponibles para picking.
- **RF-06**: cada comercio consulta solo sus propias órdenes (Tenant Isolation).
- **RNF-02**: idempotencia. Un reintento del mismo aviso no crea una segunda orden (*Idempotent Receiver*).
- **RF-04**: al recibir la orden reserva el stock en ms-inventario. *Si no existe stock disponible, la orden
  no queda disponible para picking.*

## Ciclo de vida de una orden
```
PENDIENTE_STOCK ──► LISTA_PARA_PICKING ──► EN_PICKING ──► DESPACHADA
       └──────────► RECHAZADA_SIN_STOCK
```
Las transiciones permitidas están en `EstadoOrden.puedeCambiarA`. Cualquier otra responde 409.
Los ítems solo se pueden editar en `PENDIENTE_STOCK`, y una orden `EN_PICKING` o `DESPACHADA` no se elimina.

## Reserva de stock con ms-inventario
Cada aviso nuevo reserva sus ítems llamando a `POST /api/v1/productos/{sku}/reservar` de ms-inventario
(`InventarioClient`, con `RestClient` y timeouts de 2 s para conectar y 3 s para responder):

| Resultado de inventario | Estado de la orden |
|---|---|
| Todos los ítems reservados | `LISTA_PARA_PICKING` |
| Un producto no existe (404) o no tiene stock (409) | `RECHAZADA_SIN_STOCK`, y se **libera** lo que ya se había reservado de los otros ítems (compensación con `POST /liberar`) |
| Inventario no responde o el circuito está abierto | `PENDIENTE_STOCK`; el operario reintenta con `POST /api/v1/ordenes/{id}/reservar-stock` |

La llamada está protegida con **Resilience4j** (configuración en `application.yml`):
- **Retry**: hasta 3 intentos cada 500 ms, solo si inventario no respondió (caído, timeout o 5xx). Un 404 o 409
  es una respuesta de negocio y no se reintenta.
- **Circuit Breaker**: si al menos la mitad de las últimas 10 llamadas fallan, deja de llamar a inventario por
  20 s para no saturarlo mientras se recupera. La liberación de reservas no pasa por el circuito, para que
  nunca queden unidades reservadas de más.

## Aislamiento por comercio (Tenant Isolation)
Cuando quien consulta es un usuario `COMERCIO`, el gateway agrega la cabecera `X-Comercio-Id` con el comercio
de su JWT (y borra cualquier cabecera con ese nombre que mande el cliente). ms-ordenes la usa así:
- `GET /api/v1/ordenes` filtra por ese comercio, aunque la URL pida otro.
- `GET /api/v1/ordenes/{id}` responde 404 si la orden es de otro comercio (no revela que existe).

## Arquitectura en capas
```
src/main/java/com/bodeganube/ordenes
├── client/       InventarioClient            llamada HTTP a ms-inventario con Retry y Circuit Breaker
├── controller/   OrdenController             recibe HTTP, valida con @Valid y delega al service
├── service/      OrdenService                idempotencia, reglas de estado y transacciones
├── repository/   OrdenRepository             Spring Data JPA, con @EntityGraph para evitar N+1
├── model/        Orden, OrdenItem, EstadoOrden  entidades JPA y estados
├── dto/          *Request / *Response        contrato JSON de la API, separado de las entidades
└── exception/    GlobalExceptionHandler      todos los errores con el mismo formato JSON
```
La API responde siempre con DTOs: devolver la entidad directamente hacía que Jackson entrara en un ciclo
infinito (`Orden → items → orden → ...`).

## Modelo de datos
Relación **uno a muchos** entre `ordenes` y `orden_items`. Hibernate crea las tablas al iniciar.

| Tabla | Columna | Restricciones |
|---|---|---|
| ordenes | id | PK, autoincremental |
| ordenes | external_order_id | NOT NULL, UNIQUE: clave de idempotencia |
| ordenes | comercio_id | NOT NULL |
| ordenes | estado | NOT NULL (enum como texto) |
| ordenes | tracking_number, fecha_creacion | fecha NOT NULL |
| orden_items | id | PK, autoincremental |
| orden_items | orden_id | NOT NULL, **FK → ordenes.id** |
| orden_items | producto_id, cantidad | NOT NULL. producto_id es el SKU de ms-inventario |

En código: `Orden` tiene `@OneToMany(mappedBy = "orden", cascade = ALL, orphanRemoval = true)` y
`OrdenItem` tiene `@ManyToOne(fetch = LAZY, optional = false)` con `@JoinColumn(name = "orden_id")`.
Los ítems se guardan y se borran junto con su orden.

## Endpoints
Base: `http://localhost:8082`. A través de ms-gateway (`http://localhost:8080`) todas las rutas piden JWT.

| Método | Ruta | Qué hace | Respuestas |
|---|---|---|---|
| POST | `/api/v1/ordenes` | Recibe un aviso de venta y reserva el stock | 201 con `Location`; 200 si es un reintento; 400 |
| GET | `/api/v1/ordenes` | Lista todas; `?comercioId=X` filtra por comercio | 200 |
| GET | `/api/v1/ordenes/{id}` | Obtiene una orden con sus ítems | 200, 404 |
| GET | `/api/v1/ordenes/disponibles-picking` | Órdenes en `LISTA_PARA_PICKING` | 200 |
| PUT | `/api/v1/ordenes/{id}` | Reemplaza los ítems: `{"items": [...]}` | 200, 400, 404, 409 |
| POST | `/api/v1/ordenes/{id}/reservar-stock` | Reintenta la reserva de una orden `PENDIENTE_STOCK` | 200, 404, 409 |
| PATCH | `/api/v1/ordenes/{id}/estado` | Cambia el estado: `{"estado": "LISTA_PARA_PICKING"}` | 200, 400, 404, 409 |
| DELETE | `/api/v1/ordenes/{id}` | Elimina la orden | 204, 404, 409 si ya está en bodega |

Ejemplo de aviso de venta:
```json
{
  "externalOrderId": "shopify-evt-001",
  "comercioId": "comercio-123",
  "items": [{ "productoId": "SKU-1", "cantidad": 2 }]
}
```

Todas las respuestas de error tienen el mismo formato:
```json
{
  "timestamp": "2026-10-08T18:30:12.448",
  "status": 409,
  "error": "Conflict",
  "mensaje": "No se puede pasar una orden de EN_PICKING a PENDIENTE_STOCK",
  "ruta": "/api/v1/ordenes/1/estado"
}
```
En los errores de validación (400) se agrega `detalles`, con el mensaje de cada campo inválido.

## Levantar el servicio desde cero

### 1. Requisitos
- JDK 17 o superior (`java -version`).
- PostgreSQL 14 o superior en `localhost:5432` (usuario y contraseña `postgres` por defecto).
- Git. **No hace falta instalar Maven**: el repositorio trae el Maven Wrapper (`mvnw` y `mvnw.cmd`).

### 2. Clonar el repositorio
```bash
git clone https://github.com/Caso-Bodega/ms-ordenes.git
cd ms-ordenes
```

### 3. Crear la base de datos
Con psql:
```bash
psql -U postgres -c "CREATE DATABASE bodeganube_ordenes;"
```
O en pgAdmin: clic derecho en *Databases* → *Create* → *Database...* → `bodeganube_ordenes`.
Las tablas las crea la aplicación al iniciar.

Si no tienes PostgreSQL instalado, puedes usar Docker:
```bash
docker run -d --name bodeganube-db -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:16
docker exec bodeganube-db psql -U postgres -c "CREATE DATABASE bodeganube_ordenes;"
```

### 4. Compilar, probar y empaquetar
```bash
.\mvnw.cmd clean package    # Windows (PowerShell)
./mvnw clean package        # Linux, macOS o Git Bash
```
Descarga las dependencias, compila, corre las pruebas automatizadas y genera `target/ms-ordenes.jar`.

### 5. Ejecutar
```bash
java -jar target/ms-ordenes.jar
```
También se puede levantar sin empaquetar con `.\mvnw.cmd spring-boot:run`. Queda escuchando en
`http://localhost:8082`.

### 6. Probar con Postman
Con ms-ordenes **y ms-inventario** corriendo (por ejemplo con `docker compose up` en bodeganube-infra),
importa `postman/ms-ordenes.postman_collection.json` (*Import* → archivo) y usa *Run collection*. Ejecuta en
orden 19 peticiones: prepara un producto en inventario, recibe un aviso con stock (`LISTA_PARA_PICKING`),
prueba la idempotencia, un aviso sin stock (`RECHAZADA_SIN_STOCK` con la reserva liberada), el aislamiento por
comercio, los cambios de estado y la eliminación, más los casos 400, 404 y 409. Se puede repetir.

Para ver los datos en la base:
```sql
SELECT o.id, o.external_order_id, o.estado, i.producto_id, i.cantidad
FROM ordenes o JOIN orden_items i ON i.orden_id = o.id;
```

## Configuración
Todo tiene un valor por defecto para desarrollo local y se puede cambiar con variables de entorno:

| Variable | Valor por defecto |
|---|---|
| `PORT` | `8082` |
| `DB_URL` | `jdbc:postgresql://localhost:5432/bodeganube_ordenes` |
| `DB_USER` | `postgres` |
| `DB_PASSWORD` | `postgres` |
| `INVENTARIO_URL` | `http://localhost:8083` |

## Pruebas automatizadas
`.\mvnw.cmd test` corre 21 pruebas que no necesitan base de datos ni a ms-inventario:
- `OrdenServiceTest` (JUnit 5 + Mockito): idempotencia, reserva de stock (con stock, sin stock con
  compensación, inventario caído), reintento de la reserva, transiciones de estado, aislamiento por comercio y
  reglas de edición y borrado.
- `OrdenControllerTest` (MockMvc): 201 frente a 200 en reintentos, validaciones, filtro por `X-Comercio-Id`,
  404, 409 y formato de error.

## Docker
La imagen se construye con el `Dockerfile` de la raíz, en dos etapas:

| Etapa | Imagen base | Qué hace |
|---|---|---|
| build | `eclipse-temurin:17-jdk-alpine` | Baja las dependencias (capa que se reutiliza mientras no cambie `pom.xml`), compila y separa el `.jar` en capas |
| final | `eclipse-temurin:17-jre-alpine` | Solo el JRE y la aplicación en 4 capas, ejecutada por el usuario sin privilegios `bodega` |

Buenas prácticas aplicadas: imagen final de unos 110 MB sin JDK ni código fuente, `.dockerignore` para que el
contexto de build sea chico, usuario no root, `HEALTHCHECK` contra `/actuator/health/liveness`, apagado
ordenado (`server.shutdown: graceful`) y la JVM ajustada al límite de memoria del contenedor
(`-XX:MaxRAMPercentage`).

```bash
docker build -t ms-ordenes .
docker run -p 8082:8082 -e DB_URL=jdbc:postgresql://host.docker.internal:5432/bodeganube_ordenes ms-ordenes
```
Para levantar el sistema completo (5 microservicios + PostgreSQL) se usa `docker compose up -d --build` en
[bodeganube-infra](https://github.com/Caso-Bodega/bodeganube-infra). En Docker Swarm las contraseñas llegan como
secretos en `/run/secrets` y Spring las lee con `spring.config.import=optional:configtree:/run/secrets/`.

## CI/CD (GitHub Actions)
Pipeline en `.github/workflows/ci-cd.yml`:

| Evento | Etapas |
|---|---|
| Pull request a `develop` o `main` | **1. Build y test**: `./mvnw verify` con JDK 17; el reporte de pruebas queda como artefacto |
| Push a `develop` | 1 + **2. Imagen**: publica `ghcr.io/caso-bodega/ms-ordenes:develop` en GitHub Container Registry |
| Push a `main` (merge del PR) | 1 + 2 (etiquetas `latest` y `sha-<commit>`) + **3. Despliegue**: `docker service update` en el clúster Swarm de AWS (rolling update con rollback automático) y verificación de `http://<manager>:8080/estado/ms-ordenes` |

La etiqueta `sha-<commit>` dice exactamente qué commit corre en el clúster (trazabilidad). La etapa 3 usa los
secretos de la organización `SWARM_HOST` y `SWARM_SSH_KEY`; si no existen, se omite con una advertencia.

## Ramas
`main` tiene la versión entregada, `develop` integra el trabajo en curso y cada cambio entra desde una
rama `feature/...` con un Pull Request hacia `develop`.

## Próximos pasos
- Reintentar automáticamente las órdenes `PENDIENTE_STOCK` (hoy lo hace el operario con `/reservar-stock`).
- Publicar un evento en una cola (SQS) cuando la orden queda `LISTA_PARA_PICKING`, en vez de llamar
  directo a ms-picking-despacho (feedback de la Evaluación 1).
- Si no hay stock, notificar el rechazo al comercio con un webhook de retorno.
