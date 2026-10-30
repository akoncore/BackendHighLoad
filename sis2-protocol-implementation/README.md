# SIS 2 — Каталог товаров

Простой учебный backend на Java: один каталог, одна база, два API — REST и gRPC.
Код и документация рассчитаны на пошаговое изучение.

**Быстрый старт:** нужен JDK 21 или 22. Maven отдельно устанавливать не нужно:
в проекте есть Maven Wrapper. При первом запуске нужен интернет.

## 1. Запуск за две команды

Откройте PowerShell в этой папке:

```powershell
.\mvnw.cmd clean package
java -jar target/product-catalog-1.0.0.jar --spring.profiles.active=demo
```

REST: `http://127.0.0.1:8080/api/v1/products`. gRPC: `127.0.0.1:9090`.
В режиме demo используется H2: после остановки данные исчезают.
Остановка приложения — Ctrl+C.

В другом окне PowerShell запустите проверку:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/demo.ps1
```

Скрипт создаёт товар дважды с одним ключом, проверяет одинаковый id,
читает, обновляет и удаляет этот товар.

Если `java` не найдена, установите JDK и добавьте его `bin` в PATH.
Если Maven сообщает об ошибке JAVA_HOME, укажите папку установленного JDK:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
```

Путь выше — пример; он должен совпадать с вашей установкой.

## 2. Запуск с PostgreSQL

Для этого варианта нужен Docker Desktop с запущенным движком:

```powershell
docker compose up -d --wait
.\mvnw.cmd clean package
java -jar target/product-catalog-1.0.0.jar
```

Настройки по умолчанию: localhost:5432, база catalog, пользователь catalog,
пароль catalog. Это локальные учебные настройки.

Если PostgreSQL уже установлена, создайте отдельную базу и задайте подключение:

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/catalog'
$env:DB_USER = 'catalog'
$env:DB_PASSWORD = 'ваш-пароль'
java -jar target/product-catalog-1.0.0.jar
```

Таблицы создаются автоматически из `schema.sql`. Данные сохраняются в PostgreSQL.
Не подключайте SIS 2 к рабочей базе SIS 1: это самостоятельная учебная схема.
Остановка контейнера без удаления данных: `docker compose down`.

## 3. Создание и чтение товара

```powershell
$base = 'http://127.0.0.1:8080/api/v1/products'
$key = [guid]::NewGuid().ToString()
$body = @{
    title = 'MacBook Pro'
    price = '750000.00'
    stock = 10
    currency = 'KZT'
    category_id = 1
} | ConvertTo-Json

$product = Invoke-RestMethod -Method Post -Uri $base -ContentType 'application/json' -Headers @{ 'Idempotency-Key' = $key } -Body $body
$product
Invoke-RestMethod -Uri "$base/$($product.id)"
```

Снова выполните тот же POST с прежними `$key` и `$body`: получите исходный товар.
Для другой операции создания нужен новый ключ.

| Действие | REST | gRPC |
|---|---|---|
| Получить товар | GET /api/v1/products/{id} | GetProduct |
| Получить список | GET /api/v1/products?limit=20 | ListProducts |
| Создать | POST /api/v1/products | CreateProduct |
| Заменить данные | PUT /api/v1/products/{id} | UpdateProduct |
| Удалить | DELETE /api/v1/products/{id} | DeleteProduct |

Для PUT передайте `If-Match: "1"`, где 1 — прочитанная версия.
GET возвращает её также в ETag. Каждое успешное обновление увеличивает version.
DELETE возвращает 204, отсутствующий товар — 404.

gRPC можно вызвать через grpcurl, если он установлен. Подставьте id из REST:

```powershell
grpcurl -plaintext -import-path src/main/proto -proto product.proto -d '{\"id\":\"ВАШ-UUID\"}' localhost:9090 catalog.ProductService/GetProduct
```

В Windows PowerShell 5.1 надёжнее передать JSON через stdin:

```powershell
@{ id = $product.id } | ConvertTo-Json -Compress | grpcurl -plaintext -import-path src/main/proto -proto product.proto -d '@' localhost:9090 catalog.ProductService/GetProduct
```

Файл proto передаётся явно: reflection не требуется.
Автоматические тесты вызывают все пять gRPC-методов без установки grpcurl.

## 4. Как читать код

Начните с [простого объяснения](docs/code-guide.md).
Основная цепочка:

```text
REST → ProductController   ┐
                          ├→ ProductService → ProductRepository → PostgreSQL
gRPC → ProductGrpcService ┘
```

- `model` — какие данные храним.
- `service` — проверка данных и правила работы.
- `repository` — понятные SQL-запросы.
- `rest`, `grpc` — перевод запросов в вызовы общей логики.
- `client` — примеры безопасных повторов и тайм-аутов.
- `benchmark` — сравнение протоколов.

Нет Lombok, MapStruct, Kafka, Redis и дополнительных микросервисов.
Вместо JPA используется Spring JDBC: SQL виден непосредственно в коде.
gRPC-классы генерируются Maven из proto в target; их не нужно редактировать.

## 5. Тесты и измерения

```powershell
.\mvnw.cmd test
```

Тесты по умолчанию используют H2 и случайные порты.
[Проверки надёжности и запуск на PostgreSQL](docs/reliability-tests.md).

Запустите сервис, затем в другом окне:

```powershell
.\mvnw.cmd compile exec:java "-Dexec.mainClass=com.akon.catalog.benchmark.BenchmarkRunner" "-Dexec.args=10000 10 3 benchmark/results/my-run"
```

[Как устроен benchmark](benchmark/README.md) ·
[Реальные результаты и диаграмма](docs/benchmark-report.md).

## 6. Что проверено

30 октября 2026 года: проект собран JDK 22 с целевой версией Java 21;
тесты выполнены на H2 и установленной PostgreSQL 17rc1.
Benchmark: 10000 запросов × 10 клиентов × 3 повтора на каждый протокол,
60000 измеренных запросов, ошибок нет. Это локальный эксперимент, не гарантия SLA.
Команды Docker Compose подготовлены, но Docker на этой машине отсутствует.

## 7. Связь с SIS 1 и границы

[Решения по протоколам и отличия от SIS 1](docs/protocol-decision.md).

Сохранены UUID, decimal string, обязательные currency/category_id, ETag/If-Match,
первоначальный ответ при повторном POST и основные коды ошибок.
Это сокращённый учебный контракт: дополнительные поля SIS 1, JWT,
категории, поиск и загрузка изображений не реализованы.
Сервер слушает только localhost. Текущий вариант предназначен для локальной защиты.

Папку проекта можно положить рядом с `product-catalog-design` в BackendHighLoad.
В GitHub в этой сессии ничего не опубликовано.

## 8. Защита за пять минут

1. Объясните цепочку «два API → один сервис → одна база».
2. Запустите приложение и покажите создание через REST.
3. Прочитайте этот id через gRPC.
4. Покажите повтор POST и одинаковый id.
5. Запустите тесты и объясните конфликт версии, deadline и retry.
6. Откройте CSV и диаграмму. Объясните влияние прогрева и ограничения измерений.
