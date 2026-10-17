# 10. Design decisions and trade-offs

Each entry follows: context, decision, consequences.

## ADR-1: Multi-level cache (CDN, L1, Redis) in front of PostgreSQL

- **Context:** 10,000 RPS reads versus 20 RPS writes; the data is only ~2 GB.
- **Decision:** CDN at the edge, Caffeine L1 in each instance, Redis Cluster as shared L2; PostgreSQL is reached only on a miss of all layers.
- **Consequences:** ~95% hit ratio leaves ~500 RPS on PostgreSQL and ~5 queries in flight (Little's Law). The cache becomes part of capacity, so it needs replicas, timeouts, a circuit breaker and load shedding. Several copies of the data can be stale, bounded by TTLs and purges.

## ADR-2: Cache-aside with delete on write

- **Context:** Reads dominate; stale reads are acceptable for seconds.
- **Decision:** The application fills the cache on a miss and deletes the key on change (never `SET`), plus a delayed second delete and a TTL.
- **Rejected:** write-through (slower writes, fills the cache with cold data), write-behind (data loss on crash).
- **Consequences:** Simple, robust to Redis outages; every caller must follow the same rules.

## ADR-3: PostgreSQL as the source of truth

- **Context:** Relational data, 1M rows, 20 writes/s, need for transactions and constraints.
- **Decision:** PostgreSQL with `jsonb` for heterogeneous attributes.
- **Rejected:** Cassandra or another wide-column store (no need for write scale; weaker constraints and transactions).
- **Consequences:** Strong constraints (`CHECK`, foreign keys), atomic updates, mature tooling. Vertical limit is far above 2 GB.

## ADR-4: No database sharding

- **Context:** ~2 GB of data; reads are carried by caches and replicas.
- **Decision:** One primary plus read replicas.
- **Consequences:** No cross-shard complexity. Revisit if data grows ~100x (shard by `hash(product_id)`).

## ADR-5: Elasticsearch for search

- **Context:** Text search with filters and facets; B-trees only serve equality, ranges and ordering.
- **Decision:** A separate Elasticsearch index fed from PostgreSQL.
- **Consequences:** Fast search, one more system to run, and an eventually consistent copy to keep in sync.

## ADR-6: Transactional outbox with CDC and Kafka

- **Context:** One change must reach Elasticsearch, Redis, L1 and the CDN; dual writes can lose events, and network calls must not happen inside a transaction.
- **Decision:** Write the change and an outbox row in one transaction; Debezium publishes outbox rows to Kafka; an idempotent Indexer applies them.
- **Consequences:** Atomic and reliable, with at-least-once delivery and bounded lag. More components (Debezium, Kafka) and WAL/outbox housekeeping to operate.

## ADR-7: Optimistic locking with `version` and `If-Match`

- **Context:** Two sellers can edit the same product (lost update).
- **Decision:** `UPDATE ... WHERE id = :id AND version = :v`; HTTP `If-Match` carries the version; a stale version yields `412`. Stock uses an atomic `stock = stock - 1 WHERE stock > 0`.
- **Rejected:** `SELECT ... FOR UPDATE` (holds locks and a connection while the client thinks).
- **Consequences:** No locks held; clients must handle `412` by re-reading.

## ADR-8: Idempotency keys for `POST`

- **Context:** A timeout leaves the outcome unknown; retrying `POST` could create duplicates.
- **Decision:** Client-generated `Idempotency-Key`, stored with the response in the same transaction, unique constraint, 24 h TTL.
- **Consequences:** Retries are safe; a small table to maintain.

## ADR-9: Cursor (keyset) pagination

- **Context:** Deep `OFFSET` pages read and discard rows.
- **Decision:** `WHERE (price, id) > (:last_price, :last_id) ORDER BY price, id` with an opaque cursor; `search_after` in Elasticsearch.
- **Consequences:** Constant cost per page; no jumping to an arbitrary page number.

## ADR-10: REST/JSON on the public boundary

- **Context:** We control neither the client nor its build.
- **Decision:** REST with CDN-friendly `GET`, `ETag` and `Cache-Control`; path versioning `/api/v1/`.
- **Consequences:** Universal and cacheable; no compiled contract. gRPC remains an option between internal services.

## ADR-11: Eventual consistency, with purchase-time re-validation

- **Context:** Catalog reads can tolerate seconds of staleness; money cannot.
- **Decision:** Accept bounded staleness on cards and search; price and stock must be re-read from the database at purchase time (outside this system's scope).
- **Consequences:** Large cache gains; a stale price on a card is possible for up to the documented bound (browser `max-age` 60 s).

## ADR-12: Fail fast and shed load

- **Context:** Without limits, overload turns into queues and timeouts for everyone.
- **Decision:** Deadlines, 50 ms Redis timeout, bounded queues, semaphores, `429/503` with `Retry-After`, retries only in one layer with jitter.
- **Consequences:** Some requests are rejected on purpose, so the rest stay fast.

## Known limitations and open items

| Item | Note |
|---|---|
| Seller ownership | Roles are `admin` and `seller`, but there is no `seller_id` column, so a seller can edit any product. Add `seller_id` and an ownership check to fix |
| Subtree browsing | `/categories/{id}/products` lists direct members only; subtrees go through `/search?category_id=` |
| Capacity figures | Index sizes, instance counts and latency budget are estimates to be confirmed by the load test |
| Outbox, CDC, Kafka, replication, circuit breaker | Covered by later course lectures (weeks 7, 9, 11); details here are standard practice |
| Browser staleness | Purges do not reach browsers; price on a card can be up to 60 s old there |
