package com.akon.catalog.benchmark;

import com.akon.catalog.proto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.io.PrintWriter;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class BenchmarkRunner {
    private static final ObjectMapper JSON = new ObjectMapper();

    // Аргументы: число запросов, число клиентов, повторения, папка, REST URL, gRPC host, gRPC port.
    public static void main(String[] args) throws Exception {
        int requests = args.length > 0 ? Integer.parseInt(args[0]) : 10000;
        int clients = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        int rounds = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        Path directory = Path.of(args.length > 3 ? args[3] : "benchmark/results/run-" + System.currentTimeMillis());
        String restUrl = args.length > 4 ? args[4] : "http://127.0.0.1:8080";
        String grpcHost = args.length > 5 ? args[5] : "127.0.0.1";
        int grpcPort = args.length > 6 ? Integer.parseInt(args[6]) : 9090;
        if (requests < 1 || clients < 1 || clients > requests || rounds < 1)
            throw new IllegalArgumentException("Нужны requests >= clients >= 1 и rounds >= 1");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("environment.txt"), """
                timestamp=%s
                java=%s
                os=%s %s
                available_processors=%s
                requests=%s
                clients=%s
                rounds=%s
                warmup_per_protocol_per_round=1000
                rest=%s
                grpc=%s:%s
                database=record server profile separately
                retries=disabled
                timeout=2 seconds per request
                """.formatted(Instant.now(), System.getProperty("java.version"), System.getProperty("os.name"),
                System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors(),
                requests, clients, rounds, restUrl, grpcHost, grpcPort));

        ManagedChannel channel = ManagedChannelBuilder.forAddress(grpcHost, grpcPort).usePlaintext().disableRetry().build();
        try (HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).build();
             PrintWriter raw = new PrintWriter(Files.newBufferedWriter(directory.resolve("raw.csv")));
             PrintWriter summary = new PrintWriter(Files.newBufferedWriter(directory.resolve("summary.csv")))) {
            String id = createProduct(http, restUrl);
            var stub = ProductServiceGrpc.newBlockingStub(channel);
            var grpcRequest = GetProductRequest.newBuilder().setId(id).build();
            var restRequest = HttpRequest.newBuilder(URI.create(restUrl + "/api/v1/products/" + id))
                    .timeout(Duration.ofSeconds(2)).GET().build();

            // Обе операции включают получение ответа, десериализацию и проверку id.
            Operation rest = () -> {
                var response = http.send(restRequest, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) throw new IllegalStateException("HTTP_" + response.statusCode());
                if (!JSON.readTree(response.body()).get("id").asText().equals(id))
                    throw new IllegalStateException("wrong_id");
                return response.body().length;
            };
            Operation grpc = () -> {
                var response = stub.withDeadlineAfter(2, TimeUnit.SECONDS).getProduct(grpcRequest);
                if (!response.getId().equals(id)) throw new IllegalStateException("wrong_id");
                return response.getSerializedSize();
            };
            raw.println("protocol,round,request,latency_ms,success,response_payload_bytes,error");
            summary.println("protocol,round,requests,clients,successes,errors,duration_s,success_rps,p50_ms,p95_ms,p99_ms,mean_payload_bytes");
            try {
                for (int round = 1; round <= rounds; round++) {
                    // Чередуем порядок, чтобы один протокол не был всегда первым.
                    String[] order = round % 2 == 1 ? new String[]{"REST", "gRPC"} : new String[]{"gRPC", "REST"};
                    for (String protocol : order) {
                        Operation operation = protocol.equals("REST") ? rest : grpc;
                        for (int i = 0; i < 1000; i++) operation.call();
                        measure(protocol, round, requests, clients, operation, raw, summary);
                    }
                }
            } finally {
                // Benchmark создаёт и удаляет только собственный тестовый товар.
                var deleted = http.send(HttpRequest.newBuilder(URI.create(restUrl + "/api/v1/products/" + id))
                        .timeout(Duration.ofSeconds(2)).DELETE().build(), HttpResponse.BodyHandlers.discarding());
                if (deleted.statusCode() != 204) System.err.println("Не удалось удалить тестовый товар: " + id);
            }
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        System.out.println("CSV сохранены: " + directory.toAbsolutePath());
    }

    private static String createProduct(HttpClient http, String url) throws Exception {
        String body = """
                {"title":"Benchmark laptop","price":"750000.00","stock":10,"currency":"KZT","category_id":1}
                """;
        var response = http.send(HttpRequest.newBuilder(URI.create(url + "/api/v1/products"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201) throw new IllegalStateException("Создание товара: HTTP " + response.statusCode());
        return JSON.readTree(response.body()).get("id").asText();
    }

    private static void measure(String protocol, int round, int requests, int clients, Operation operation,
                                PrintWriter raw, PrintWriter summary) throws Exception {
        Sample[] samples = new Sample[requests];
        AtomicInteger next = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(clients);
        CountDownLatch start = new CountDownLatch(1);
        long elapsed;
        try (var pool = Executors.newFixedThreadPool(clients)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int client = 0; client < clients; client++) {
                tasks.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int index; (index = next.getAndIncrement()) < requests;) {
                        long began = System.nanoTime();
                        try {
                            int bytes = operation.call();
                            samples[index] = new Sample(System.nanoTime() - began, bytes, "");
                        } catch (Exception error) {
                            samples[index] = new Sample(System.nanoTime() - began, 0, error.getClass().getSimpleName());
                        }
                    }
                    return null;
                }));
            }
            ready.await();
            long began = System.nanoTime();
            start.countDown();
            for (var task : tasks) task.get();
            elapsed = System.nanoTime() - began;
        }
        for (int i = 0; i < samples.length; i++) {
            var sample = samples[i];
            raw.printf(Locale.ROOT, "%s,%d,%d,%.6f,%s,%d,%s%n", protocol, round, i + 1,
                    sample.nanos / 1e6, sample.error.isEmpty(), sample.bytes, sample.error);
        }
        long[] successful = Arrays.stream(samples).filter(s -> s.error.isEmpty()).mapToLong(Sample::nanos).sorted().toArray();
        double bytes = Arrays.stream(samples).filter(s -> s.error.isEmpty()).mapToInt(Sample::bytes).average().orElse(0);
        double seconds = elapsed / 1e9;
        summary.printf(Locale.ROOT, "%s,%d,%d,%d,%d,%d,%.6f,%.3f,%.6f,%.6f,%.6f,%.1f%n",
                protocol, round, requests, clients, successful.length, requests - successful.length,
                seconds, successful.length / seconds, percentile(successful, .50), percentile(successful, .95),
                percentile(successful, .99), bytes);
        raw.flush();
        summary.flush();
        System.out.printf(Locale.ROOT, "%s, повтор %d: %.0f успешных запросов/с; ошибок: %d%n",
                protocol, round, successful.length / seconds, requests - successful.length);
    }

    private static double percentile(long[] values, double percentile) {
        return values.length == 0 ? Double.NaN : values[(int) Math.ceil(values.length * percentile) - 1] / 1e6;
    }

    @FunctionalInterface
    private interface Operation { int call() throws Exception; }
    private record Sample(long nanos, int bytes, String error) {}
}
