package com.akon.catalog.client;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.List;
import java.util.Map;

public final class GrpcClients {
    private GrpcClients() {}

    public static ManagedChannel connect(String host, int port) {
        // Повторяем только чтение и создание с прежним idempotency_key.
        // UPDATE не повторяем: версия после успешной записи уже могла измениться.
        var methods = List.of(
                Map.of("service", "catalog.ProductService", "method", "GetProduct"),
                Map.of("service", "catalog.ProductService", "method", "ListProducts"),
                Map.of("service", "catalog.ProductService", "method", "CreateProduct"));
        var policy = Map.of(
                "maxAttempts", 3.0,
                "initialBackoff", "0.1s",
                "maxBackoff", "0.2s",
                "backoffMultiplier", 2.0,
                "retryableStatusCodes", List.of("UNAVAILABLE"));
        return ManagedChannelBuilder.forAddress(host, port).usePlaintext()
                .enableRetry()
                .defaultServiceConfig(Map.of("methodConfig", List.of(Map.of("name", methods, "retryPolicy", policy))))
                .build();
    }
}
