package com.akon.catalog;

import com.akon.catalog.client.*;
import com.akon.catalog.proto.*;
import com.sun.net.httpserver.HttpServer;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.net.*;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReliabilityTest {
    @Test
    void grpcRetriesUnavailableAndThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var service = new ProductServiceGrpc.ProductServiceImplBase() {
            @Override
            public void getProduct(GetProductRequest request, StreamObserver<ProductResponse> observer) {
                if (calls.incrementAndGet() < 3) observer.onError(Status.UNAVAILABLE.asRuntimeException());
                else {
                    observer.onNext(ProductResponse.newBuilder().setTitle("Готово").build());
                    observer.onCompleted();
                }
            }
        };
        runGrpc(service, stub -> {
            assertEquals("Готово", stub.withDeadlineAfter(3, TimeUnit.SECONDS)
                    .getProduct(GetProductRequest.getDefaultInstance()).getTitle());
            assertEquals(3, calls.get());
        });
    }

    @Test
    void grpcNeverRetriesValidationOrNotFound() throws Exception {
        for (Status failure : new Status[]{Status.NOT_FOUND, Status.INVALID_ARGUMENT, Status.ABORTED}) {
            AtomicInteger calls = new AtomicInteger();
            runGrpc(new ProductServiceGrpc.ProductServiceImplBase() {
                @Override
                public void getProduct(GetProductRequest request, StreamObserver<ProductResponse> observer) {
                    calls.incrementAndGet();
                    observer.onError(failure.asRuntimeException());
                }
            }, stub -> {
                assertEquals(failure.getCode(), assertThrows(StatusRuntimeException.class, () ->
                        stub.withDeadlineAfter(2, TimeUnit.SECONDS)
                                .getProduct(GetProductRequest.getDefaultInstance())).getStatus().getCode());
                assertEquals(1, calls.get());
            });
        }
    }

    @Test
    void grpcDeadlineAndRetryLimit() throws Exception {
        runGrpc(new ProductServiceGrpc.ProductServiceImplBase() {
            @Override
            public void getProduct(GetProductRequest request, StreamObserver<ProductResponse> observer) {
                // Намеренно не отвечаем: проверяем настоящий клиентский deadline.
            }
        }, stub -> assertEquals(Status.Code.DEADLINE_EXCEEDED, assertThrows(StatusRuntimeException.class, () ->
                stub.withDeadlineAfter(150, TimeUnit.MILLISECONDS)
                        .getProduct(GetProductRequest.getDefaultInstance())).getStatus().getCode()));

        AtomicInteger calls = new AtomicInteger();
        runGrpc(new ProductServiceGrpc.ProductServiceImplBase() {
            @Override
            public void getProduct(GetProductRequest request, StreamObserver<ProductResponse> observer) {
                calls.incrementAndGet();
                observer.onError(Status.UNAVAILABLE.asRuntimeException());
            }
        }, stub -> {
            assertEquals(Status.Code.UNAVAILABLE, assertThrows(StatusRuntimeException.class, () ->
                    stub.withDeadlineAfter(3, TimeUnit.SECONDS)
                            .getProduct(GetProductRequest.getDefaultInstance())).getStatus().getCode());
            assertEquals(3, calls.get());
        });
    }

    @Test
    void restRetries503ButNot404Or400() throws Exception {
        for (int status : new int[]{503, 404, 400}) {
            AtomicInteger calls = new AtomicInteger();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                int number = calls.incrementAndGet();
                exchange.sendResponseHeaders(status == 503 && number == 3 ? 200 : status, -1);
                exchange.close();
            });
            server.start();
            try (var client = new RestClient()) {
                var result = client.get(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
                assertEquals(status == 503 ? 200 : status, result.statusCode());
                assertEquals(status == 503 ? 3 : 1, calls.get());
            } finally { server.stop(0); }
        }
    }

    @Test
    void restHasAnOverallTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var workers = Executors.newCachedThreadPool();
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try (var client = new RestClient()) {
            assertThrows(HttpTimeoutException.class, () ->
                    client.get(URI.create("http://127.0.0.1:" + server.getAddress().getPort())));
        } finally {
            server.stop(0);
            workers.shutdownNow();
        }
    }

    private void runGrpc(BindableService service,
            java.util.function.Consumer<ProductServiceGrpc.ProductServiceBlockingStub> test) throws Exception {
        Server server = ServerBuilder.forPort(0).addService(service).build().start();
        ManagedChannel channel = GrpcClients.connect("127.0.0.1", server.getPort());
        try { test.accept(ProductServiceGrpc.newBlockingStub(channel)); }
        finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
