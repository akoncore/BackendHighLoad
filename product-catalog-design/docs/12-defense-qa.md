# 12. Defence guide: pitch and likely questions

## 12.1 One-minute pitch

> The catalog is read-heavy: 10,000 RPS of reads against 20 RPS of writes, with only 1M products (about 2 GB). So the main tool is a multi-level cache: CDN, an in-process L1 and Redis (cache-aside, delete on write, TTL with jitter). With a 95% hit ratio only about 500 RPS reach PostgreSQL, and Little's Law shows about 5 queries in flight instead of 100. Writes are one short transaction guarded by a `version` column (lost-update protection) and an `Idempotency-Key` for retries. Each change also writes an outbox row, which Debezium publishes to Kafka; an idempotent Indexer then updates Elasticsearch, Redis, L1 and the CDN. That is eventual consistency, which is acceptable for a catalog. Search lives in Elasticsearch because B-trees cannot serve text search and multi-filter queries. No sharding is needed because 2 GB fits one PostgreSQL. For resilience we use deadlines, a 50 ms Redis timeout, bounded queues, semaphores, load shedding and retries in a single layer.

## 12.2 Likely questions

| Question | Short answer |
|---|---|
| Why is p99 < 80 ms achievable? | Hit path is ~1 ms. With 95% hits, p99 is the database's p99 (2-10 ms for an indexed lookup). Worst case, Redis times out at 50 ms and the DB answers: ~73 ms, still inside 80 ms |
| Why no sharding? | ~2 GB fits one PostgreSQL; caches and replicas carry the reads. Shard by `hash(product_id)` only if data grows ~100x |
| Why PostgreSQL, not Cassandra? | Small relational data, constraints and transactions matter, only 20 writes/s |
| Why Elasticsearch? | B-trees serve `=`, ranges, `ORDER BY`; they cannot do `LIKE '%x%'`, relevance or facets without a full scan |
| A price changed but the cache shows the old one | Delete on write plus a delayed second delete; CDN purge by tag; L1 pub/sub; TTL as the backstop. Browsers can lag up to `max-age` (60 s); purchase flows re-check the price |
| What if Redis dies? | 50 ms timeout, fall back to the database, circuit breaker, L1 keeps hot keys, load shedding protects PostgreSQL, Redis has a replica |
| What is a cache stampede and how do you stop it? | A hot key expires and many requests rebuild it. Coalescing (`SET lock NX`), serve stale, refresh early |
| Many requests for ids that do not exist? | Cache "not found" for 30-60 s and use a Bloom filter |
| Two sellers edit the same product | `version` + `If-Match`; the loser gets `412` and re-reads. Stock uses an atomic `stock - 1` |
| A retried `POST` | `Idempotency-Key` with a unique constraint; the stored response is replayed |
| Why not write to Kafka directly? | Dual write is not atomic and a network call inside a transaction holds locks. Outbox makes it atomic |
| What is CDC? | Reading PostgreSQL's WAL (change log) with Debezium to publish outbox rows to Kafka |
| What if an event is lost or late? | Lag alert, TTL as a safety net, external versioning ignores stale events, reindex via alias repairs Elasticsearch |
| Why cursor pagination? | OFFSET reads and discards rows; the cursor keeps page 5,000 as cheap as page 1 |
| CDN versus Redis? | CDN serves the user's browser with whole HTTP responses and never reaches us on a hit; Redis serves our service with data objects and shields PostgreSQL |
| If the CDN misses, does it go to PostgreSQL? | No. It goes to our API gateway, then Product Service checks L1 and Redis, and only then PostgreSQL |
| Why 412 and not 409 for a stale version? | `If-Match` precondition failure is `412 Precondition Failed` in HTTP. `409` is used here for an idempotent request still in progress |
| Where do image URLs come from? | Write Service issues a presigned URL, the client uploads to S3, Write Service stores the CDN URL in `image_urls`; readers fetch images from the CDN |
| What do you not cover? | Checkout and payments, reviews, recommendations, stock reservation, buyer accounts, seller ownership (see the limitations list) |
| Can the database survive without the cache? | Not at peak: ~100 queries in flight instead of ~5. That is why the cache has replicas, timeouts, a breaker and load shedding |

## 12.3 Numbers to remember

| Quantity | Value |
|---|---|
| Read : write | 500 : 1 |
| Product size / catalog | ~2 KB / ~2 GB |
| Images | ~1 TB (S3 + CDN) |
| Hit ratio 95% -> DB load | ~500 RPS (peak ~1,500) |
| Mean latency at 95% hits | ~1 ms |
| DB queries in flight | ~5 with cache, ~100 without |
| Redis timeout / worst-case path | 50 ms / ~73 ms |
| Stampede example | 5,000 RPS x 50 ms = 250 identical queries |
| Retry amplification | 3 layers x 3 retries = 27x |
