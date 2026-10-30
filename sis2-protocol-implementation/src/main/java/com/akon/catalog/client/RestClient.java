package com.akon.catalog.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;

public class RestClient implements AutoCloseable {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

    public HttpResponse<String> get(URI uri) throws IOException, InterruptedException {
        return execute(timeout -> HttpRequest.newBuilder(uri).timeout(timeout).GET().build());
    }

    public HttpResponse<String> create(URI uri, UUID key, String json) throws IOException, InterruptedException {
        // Ключ и тело создаются до цикла и не меняются между попытками.
        return execute(timeout -> HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key.toString())
                .POST(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    private HttpResponse<String> execute(Function<Duration, HttpRequest> request)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        for (int attempt = 1; attempt <= 3; attempt++) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new HttpTimeoutException("Общий тайм-аут: 2 секунды");
            HttpResponse<String> response;
            try {
                response = http.send(request.apply(Duration.ofNanos(remaining)), HttpResponse.BodyHandlers.ofString());
            } catch (HttpTimeoutException timeout) {
                throw timeout; // После истечения общего бюджета новых попыток нет.
            } catch (IOException connectionError) {
                if (attempt == 3) throw connectionError;
                pause(attempt * 100L, deadline);
                continue;
            }
            if (response.statusCode() != 503 || attempt == 3) return response;
            long delay = attempt * 100L;
            try {
                // Сервер этого проекта возвращает Retry-After в секундах.
                delay = Math.max(delay, Math.multiplyExact(Long.parseLong(
                        response.headers().firstValue("Retry-After").orElse("0")), 1000));
            } catch (NumberFormatException | ArithmeticException ignored) {
                // Если заголовок некорректен, используем обычную задержку.
            }
            pause(delay, deadline);
        }
        throw new IllegalStateException("Недостижимая ветка");
    }

    private void pause(long milliseconds, long deadline) throws InterruptedException, HttpTimeoutException {
        if (milliseconds >= (deadline - System.nanoTime()) / 1_000_000)
            throw new HttpTimeoutException("Не осталось времени на повтор запроса");
        Thread.sleep(milliseconds);
    }

    @Override
    public void close() { http.close(); }
}
