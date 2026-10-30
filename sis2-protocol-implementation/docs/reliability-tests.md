# Проверки надёжности

## Результат

30.10.2026: 10 тестов, 0 ошибок, 0 пропусков.
Один прогон выполнен на H2, второй — на локальной PostgreSQL 17rc1.
Java 22.0.2; Maven компилирует с release=21.
Сводные текстовые отчёты PostgreSQL сохранены в `docs/test-results`.

## Что проверяется

| Тест | Проверка |
|---|---|
| restAndGrpcShareDataAndVersion | POST REST → GET/PUT gRPC → GET/DELETE REST, ETag, 412, 428, NOT_FOUND |
| replayReturnsOriginalEvenAfterUpdateAndDelete | Повтор POST до/после изменения и удаления; тот же id, исходная версия; 422 |
| concurrentRestAndGrpcCreateOnlyOneProduct | 10 одновременных запросов через оба протокола, ровно один товар |
| paginationAndInvalidRequests | Список, следующий курсор, limit, неверный UUID/цена/название, отсутствующий ключ |
| concurrentUpdatesHaveExactlyOneWinner | Два параллельных обновления: одно успешно, другое ABORTED |
| grpcRetriesUnavailableAndThenSucceeds | Два UNAVAILABLE, затем успех; ровно три попытки |
| grpcNeverRetriesValidationOrNotFound | NOT_FOUND, INVALID_ARGUMENT, ABORTED: одна попытка |
| grpcDeadlineAndRetryLimit | DEADLINE_EXCEEDED и предел три попытки при постоянном UNAVAILABLE |
| restRetries503ButNot404Or400 | 503 повторяется; 400/404 не повторяются |
| restHasAnOverallTimeout | Зависший HTTP-сервер → HttpTimeoutException |

Integration-тесты запускают настоящий Spring Boot, HTTP и gRPC по случайным портам.
Reliability-тесты запускают небольшие HTTP/gRPC-серверы с заданными ошибками.
Это проверка сетевых клиентов, а не имитация промышленного отказа PostgreSQL.

## Запуск без Docker

```powershell
.\mvnw.cmd test
```

По умолчанию тесты используют временную H2, обычное приложение при этом не требуется.

## Запуск на PostgreSQL

Создайте **отдельную тестовую базу** catalog_test.
Тесты добавляют данные и могут оставлять их после выполнения.
Затем:

```powershell
.\mvnw.cmd test "-Dspring.datasource.url=jdbc:postgresql://localhost:5432/catalog_test" "-Dspring.datasource.username=catalog" "-Dspring.datasource.password=catalog"
```

Профиль теста называется demo, но эти параметры заменяют настройки его datasource.
В логе должно быть `org.postgresql.jdbc.PgConnection`, а не H2.
Результаты Maven: `target/surefire-reports`.

## Что ещё не доказано

Не проводились многопроцессные и длительные нагрузочные испытания,
аварийное завершение БД во время commit и проверка производственного окружения.
Не следует приравнивать прохождение этих тестов к гарантии exactly-once
для произвольных распределённых операций.
