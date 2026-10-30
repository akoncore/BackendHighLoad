package com.akon.catalog;

import com.akon.catalog.proto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.profiles.active=demo", "grpc.port=0"})
class CatalogIntegrationTest {
    @LocalServerPort int port;
    @Autowired Server grpcServer;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate database;
    HttpClient http;
    ManagedChannel channel;
    ProductServiceGrpc.ProductServiceBlockingStub grpc;

    @BeforeEach
    void connect() {
        http = HttpClient.newHttpClient();
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", grpcServer.getPort()).usePlaintext().disableRetry().build();
        grpc = ProductServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void close() throws Exception {
        http.close();
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    String body(String title) {
        return "{\"title\":\"" + title + "\",\"price\":\"750000.00\",\"stock\":10,\"currency\":\"KZT\",\"category_id\":1}";
    }

    ProductInput input(String title) {
        return ProductInput.newBuilder().setTitle(title).setPrice("750000.00")
                .setStock(10).setCurrency("KZT").setCategoryId(1).build();
    }

    HttpResponse<String> rest(String method, String suffix, String body, String... headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/products" + suffix))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json");
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    ProductResponse createGrpc(String key, String title) {
        return grpc.withDeadlineAfter(5, TimeUnit.SECONDS).createProduct(CreateProductRequest.newBuilder()
                .setIdempotencyKey(key).setProduct(input(title)).build());
    }

    @Test
    void restAndGrpcShareDataAndVersion() throws Exception {
        var created = rest("POST", "", body("Ноутбук"), "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(201, created.statusCode());
        JsonNode product = json.readTree(created.body());
        String id = product.get("id").asText();
        var fetched = grpc.getProduct(GetProductRequest.newBuilder().setId(id).build());
        assertEquals("Ноутбук", fetched.getTitle());
        assertEquals(product.get("price").asText(), fetched.getPrice());
        assertEquals(product.get("updated_at").asText(), fetched.getUpdatedAt());
        assertEquals(1, fetched.getVersion());
        assertEquals("KZT", fetched.getCurrency());
        assertEquals(10, fetched.getStock());

        var updated = grpc.updateProduct(UpdateProductRequest.newBuilder().setId(id)
                .setVersion(1).setProduct(input("Новый ноутбук")).build());
        assertEquals(2, updated.getVersion());
        var readRest = rest("GET", "/" + id, null);
        assertEquals("\"2\"", readRest.headers().firstValue("ETag").orElseThrow());
        assertEquals("Новый ноутбук", json.readTree(readRest.body()).get("title").asText());
        assertEquals(412, rest("PUT", "/" + id, body("Устаревшая запись"), "If-Match", "\"1\"").statusCode());
        assertEquals(428, rest("PUT", "/" + id, body("Без версии")).statusCode());
        assertEquals(Status.Code.ABORTED, assertThrows(StatusRuntimeException.class, () ->
                grpc.updateProduct(UpdateProductRequest.newBuilder().setId(id).setVersion(1)
                        .setProduct(input("Устаревшая запись")).build())).getStatus().getCode());

        assertEquals(204, rest("DELETE", "/" + id, null).statusCode());
        assertEquals(404, rest("GET", "/" + id, null).statusCode());
        assertEquals(Status.Code.NOT_FOUND, assertThrows(StatusRuntimeException.class, () ->
                grpc.getProduct(GetProductRequest.newBuilder().setId(id).build())).getStatus().getCode());
    }

    @Test
    void replayReturnsOriginalEvenAfterUpdateAndDelete() throws Exception {
        String key = UUID.randomUUID().toString();
        var first = rest("POST", "", body("Телефон"), "Idempotency-Key", key);
        String id = json.readTree(first.body()).get("id").asText();
        assertEquals(200, rest("PUT", "/" + id, body("Изменён"), "If-Match", "\"1\"").statusCode());
        assertEquals(first.body(), rest("POST", "", body("Телефон"), "Idempotency-Key", key).body());
        assertEquals(422, rest("POST", "", body("Другие данные"), "Idempotency-Key", key).statusCode());
        var replayGrpc = createGrpc(key, "Телефон");
        assertEquals(id, replayGrpc.getId());
        assertEquals(1, replayGrpc.getVersion());
        grpc.deleteProduct(DeleteProductRequest.newBuilder().setId(id).build());
        assertEquals(first.body(), rest("POST", "", body("Телефон"), "Idempotency-Key", key).body());
        assertEquals(404, rest("GET", "/" + id, null).statusCode());
    }

    @Test
    void concurrentRestAndGrpcCreateOnlyOneProduct() throws Exception {
        String key = UUID.randomUUID().toString();
        String title = "Один товар " + key;
        try (var pool = Executors.newFixedThreadPool(10)) {
            CountDownLatch ready = new CountDownLatch(10);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                final boolean useRest = i % 2 == 0;
                results.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    if (useRest) {
                        var result = rest("POST", "", body(title), "Idempotency-Key", key);
                        assertEquals(201, result.statusCode());
                        return json.readTree(result.body()).get("id").asText();
                    }
                    return createGrpc(key, title).getId();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            Set<String> ids = new HashSet<>();
            for (var result : results) ids.add(result.get(10, TimeUnit.SECONDS));
            assertEquals(1, ids.size());
            // Считаем реальные записи, а не только одинаковые ответы.
            long matches = database.queryForObject(
                    "SELECT count(*) FROM products WHERE title=?", Long.class, title);
            assertEquals(1, matches);
        }
    }

    @Test
    void paginationAndInvalidRequests() throws Exception {
        createGrpc(UUID.randomUUID().toString(), "Первый");
        createGrpc(UUID.randomUUID().toString(), "Второй");
        var page = rest("GET", "?limit=1", null);
        var first = json.readTree(page.body());
        assertEquals(1, first.get("items").size());
        String cursor = first.get("next_cursor").asText();
        var next = grpc.listProducts(ListProductsRequest.newBuilder().setLimit(1).setCursor(cursor).build());
        assertNotEquals(first.get("items").get(0).get("id").asText(), next.getItems(0).getId());
        assertEquals(400, rest("GET", "?limit=0", null).statusCode());
        assertEquals(400, rest("GET", "?cursor=broken", null).statusCode());
        assertEquals(400, rest("GET", "/not-a-uuid", null).statusCode());
        assertEquals(400, rest("POST", "", body("Без ключа")).statusCode());
        assertEquals(400, rest("POST", "", body("Цена").replace("750000.00", "-1"),
                "Idempotency-Key", UUID.randomUUID().toString()).statusCode());
        assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class, () ->
                createGrpc(UUID.randomUUID().toString(), "")).getStatus().getCode());
        assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class, () ->
                grpc.listProducts(ListProductsRequest.newBuilder().setLimit(0).build())).getStatus().getCode());
    }

    @Test
    void concurrentUpdatesHaveExactlyOneWinner() throws Exception {
        var product = createGrpc(UUID.randomUUID().toString(), "Версия");
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Status.Code> operation = () -> {
                start.await();
                try {
                    grpc.updateProduct(UpdateProductRequest.newBuilder().setId(product.getId())
                            .setVersion(1).setProduct(input("Обновлён")).build());
                    return Status.Code.OK;
                } catch (StatusRuntimeException e) { return e.getStatus().getCode(); }
            };
            var a = pool.submit(operation);
            var b = pool.submit(operation);
            start.countDown();
            assertEquals(Set.of(Status.Code.OK, Status.Code.ABORTED),
                    new HashSet<>(List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS))));
        }
    }
}
