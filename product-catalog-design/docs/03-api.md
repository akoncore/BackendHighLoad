# 3. API design

Machine-readable contract: [`api/openapi.yaml`](../api/openapi.yaml).

## 3.1 Conventions

| Topic | Decision | Reason |
|---|---|---|
| Style | REST, JSON over HTTPS | Public boundary: the client and its build are not ours, so use the most widely understood style. Plain `GET` + `ETag` + `Cache-Control` gives CDN caching for free (Lecture 3) |
| Versioning | In the path: `/api/v1/` | Visible in logs, metrics and routing rules (Lecture 3). A new major version is rare; additive change is the default |
| Auth | `GET` is public. `POST/PUT/DELETE` need a JWT with role `admin` or `seller` | RBAC at the API gateway |
| Money | `price` is a decimal **string** (`"450000.00"`) | No binary-float rounding |
| Errors | `{"error": {"code", "message", "request_id"}}` | One machine-readable shape for every failure |
| Time | RFC 3339 UTC | Unambiguous |

### Compatibility rules (Lecture 3)

- Adding an optional response field is safe, provided clients ignore unknown fields.
- Removing or renaming a field, changing its type, or making an optional field required is **breaking** and needs `/v2`.
- Deprecation is a process: announce (`Deprecation`/`Sunset` headers), instrument per client, brown out, then remove.

## 3.2 Endpoints

### Read

| Method and path | Purpose | Success |
|---|---|---|
| `GET /products/{id}` | Product card | 200 |
| `GET /categories/{id}/products?cursor=&limit=` | Products directly in a category, sorted by price | 200 |
| `GET /search?q=&brand=&category_id=&price_min=&price_max=&sort=&cursor=&limit=` | Search with filters | 200 |

### Write (role `admin` or `seller`)

| Method and path | Purpose | Success |
|---|---|---|
| `POST /products` (`Idempotency-Key`) | Create | 201 + `Location` |
| `PUT /products/{id}` (`If-Match`) | Replace | 200 |
| `DELETE /products/{id}` | Delete | 204 |
| `POST /products/{id}/images` | Get a presigned S3 upload URL | 200 |
| `POST /products/{id}/images/confirm` | Confirm the upload; the CDN URL is added to the product | 200 |

### Status codes

| Code | Meaning in this API |
|---|---|
| 200, 201, 204 | Success |
| 400 | Validation error |
| 401, 403 | Missing/invalid token; role not allowed |
| 404 | Unknown product or category |
| 409 | `POST` with an `Idempotency-Key` whose first request is still in progress |
| 412 | `If-Match` version is stale (lost-update protection) |
| 422 | `Idempotency-Key` reused with a different request body |
| 428 | `If-Match` header missing on `PUT` |
| 429 | Rate limit exceeded (`Retry-After`) |
| 503 | Overloaded or dependency down (`Retry-After`) |

## 3.3 Pagination: cursor (keyset), not offset

`OFFSET 100000 LIMIT 20` makes PostgreSQL read and discard 100,000 rows, so deep pages get slower and slower. Keyset pagination continues from the last row seen, using the index:

```sql
SELECT id, title, price, currency
  FROM products
 WHERE category_id = :category_id
   AND (price, id) > (:last_price, :last_id)
 ORDER BY price, id
 LIMIT :limit + 1;
```

The extra row tells the server whether another page exists. The response carries an opaque `next_cursor` (base64 of the last `(price, id)`), which is `null` on the last page. Page 1 and page 5,000 cost the same.
Search uses Elasticsearch `search_after` for the same reason.

```json
{
  "items": [ { "id": "...", "title": "...", "price": "15000.00" } ],
  "next_cursor": "eyJwIjoiMTUwMDAuMDAiLCJpIjoiNTUwZTg0MDAifQ"
}
```

## 3.4 Concurrency control: `ETag` and `If-Match`

Problem (lost update, Lecture 6): two sellers read version 5, both save, and the second silently overwrites the first.

1. `GET /products/42` returns `ETag: "5"` and `"version": 5`.
2. `PUT /products/42` sends `If-Match: "5"`.
3. The server runs `UPDATE ... WHERE id = :id AND version = 5` and increments the version.
4. **0 rows updated** means someone else changed the product: respond **412 Precondition Failed**; the client re-reads and re-applies its edit.

Stock changes use an atomic statement instead (`SET stock = stock - 1 WHERE stock > 0`), which needs no version check.

## 3.5 Idempotency

`POST` is neither safe nor idempotent: retrying a timed-out request could create two products. A timeout means "unknown outcome", not "failure" (Lecture 3).

1. The client generates a UUID **once per logical operation** and sends it as `Idempotency-Key`. It reuses the same key on every retry.
2. In one transaction the server inserts into `idempotency_keys` (the primary key is the unique constraint), creates the product and writes the outbox row.
3. The stored response is saved with the key.
4. A retry with the same key and body returns the **stored original response** (201, same body).
5. Same key, different body: `422`. Same key while the first request still runs: `409`.
6. Keys expire after 24 hours.

`PUT` and `DELETE` are idempotent by definition (a second `DELETE` returns 404, but the state is the same).

## 3.6 Caching headers

| Endpoint | Headers |
|---|---|
| `GET /products/{id}` | `Cache-Control: public, max-age=60, s-maxage=600, stale-while-revalidate=30`; `ETag: "<version>"`; `Surrogate-Key: product-{id} category-{category_id}` |
| `GET /categories/{id}/products` | `Cache-Control: public, max-age=30, s-maxage=120`; `Surrogate-Key: category-{id}` |
| `GET /search` | `Cache-Control: public, max-age=15, s-maxage=60` (popular queries only benefit) |
| `POST/PUT/DELETE` | `Cache-Control: no-store` |
| Anything personalised (none today) | `Cache-Control: private, no-store` |

- `max-age` is for the browser, `s-maxage` for the CDN; they may differ.
- `stale-while-revalidate` lets the CDN serve a stale copy while it refreshes in the background.
- `Surrogate-Key` lets the Indexer purge exactly the pages that show a changed product.
- Trade-off: a purge cannot reach browsers, so a price shown on a card can be up to `max-age` (60 s) stale in a browser. Purchase flows must re-check the price.

## 3.7 Rate limiting and deadlines

- The API gateway applies a per-client rate limit and answers `429` with `Retry-After` immediately (Lecture 4: refuse early and cheaply).
- The gateway starts a **deadline** of 80 ms and passes the remaining budget downstream in a header; services stop work once it is spent (Lecture 3).

## 3.8 Image upload

Image bytes never pass through our services.

1. `POST /products/{id}/images` with the content type. Write Service returns a **presigned URL** valid for 5 minutes and the final CDN URL.
2. The client uploads the file straight to S3 with `PUT`.
3. The client confirms the upload; Write Service checks that the object exists, then appends the CDN URL to `image_urls` (bumping `version` and writing an outbox row).
4. Readers get the URLs inside the product JSON and fetch images from the CDN (origin: S3).
