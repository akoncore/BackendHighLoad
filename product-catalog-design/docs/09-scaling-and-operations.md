# 9. Scaling and operations

## 9.1 How each tier scales

| Tier | Scaling approach | Notes |
|---|---|---|
| Product / Search / Write services | **Horizontal**: add stateless instances; autoscale on RPS and CPU | Any instance can serve any request; the L1 cache is disposable |
| Load balancer / API gateway | Managed, multi-AZ | Per-request balancing |
| Redis | Cluster shards + one replica per shard | Hash-slot sharding; failover to the replica |
| PostgreSQL | One primary + read replicas (async) | Reads on replicas, writes on the primary |
| Elasticsearch | More shards/replicas, more nodes | Alias-based reindexing for mapping changes |
| Kafka | More partitions and consumers in a group | Key = product id keeps per-product order |
| CDN / S3 | Provider scale | Offloads images and public GETs |

### Why there is no sharding of PostgreSQL

The whole catalog is ~2 GB. One PostgreSQL primary holds it comfortably, and the read load is carried by Redis and replicas (only ~500 RPS, ~1,500 at peak, reach the database). Sharding would add complexity (cross-shard queries, resharding) for no benefit. If the catalog grew about 100x, shard by `hash(product_id)`.

### Vertical versus horizontal

Vertical scaling (a bigger machine) has a ceiling and a single point of failure. Services are stateless, so horizontal scaling is simple and also gives availability.

## 9.2 Deployment topology

- Services, Redis, Elasticsearch, Kafka and PostgreSQL span **at least 2-3 availability zones**.
- Capacity is planned for **N-1**: losing one instance or one zone must still serve peak traffic.
- Rolling releases with health checks; schema changes are additive (see API compatibility rules).
- PostgreSQL replicas in different zones from the primary, with automatic failover.

## 9.3 Observability

### Metrics

| Area | Metrics |
|---|---|
| Traffic | RPS per endpoint, error rate by class (4xx/5xx), 429/503 counts |
| Latency | p50/p95/p99 per endpoint and per layer (L1, Redis, PostgreSQL, Elasticsearch) |
| Cache | Hit ratio per layer, evictions, Redis memory and latency, rebuilds per key |
| Database | Queries/s, connection pool usage, slow queries, replication lag, `pg_stat_activity` long transactions |
| Search | Query latency, index size |
| Pipeline | Outbox backlog, Kafka consumer lag, Elasticsearch indexing lag, dead-letter count |
| Resilience | Circuit breaker state, retry counts, semaphore rejections |
| Per call | Latency histogram, error rate by class, retry count |

### Alerts (starting thresholds)

| Alert | Condition |
|---|---|
| Latency | p99 > 80 ms for 5 min |
| Cache | Hit ratio < 90% for 10 min |
| Redis | Down, or memory above 90% of `maxmemory` |
| Pipeline | Kafka/Elasticsearch lag > 30 s |
| Database | Replication lag > 5 s; any transaction idle for more than 30 s |
| Overload | 429/503 rate above 1% |
| Search | Elasticsearch cluster not green |

Measure percentiles and the mean: with a miss rate above 1%, p99 is the database's p99.

## 9.4 Load test plan

Tool: k6 (as in the course practice sessions). Key distribution must be **Zipf-like**; uniform keys hide hot-key problems.

| Scenario | Method | Pass criterion |
|---|---|---|
| Baseline | 10,000 RPS reads, 20 RPS writes, Zipf keys | p99 < 80 ms, hit ratio >= 95% |
| Peak | 30,000 RPS | Latency and error rate within target, autoscaling works |
| No cache | Compare: no cache, Redis only, L1 + Redis | Matches the hit-ratio table in docs/04 |
| Hot key | Expire one hot key under load; count DB queries/s with and without coalescing | ~1 rebuild instead of hundreds |
| Redis outage | Stop Redis under load | Service keeps answering; p99 stays near the budget; DB protected by shedding |
| Elasticsearch outage | Stop Elasticsearch | Search returns 503 + `Retry-After`; cards unaffected |
| Kafka lag | Pause the Indexer | Alert fires; TTL bounds staleness; catch-up after restart |
| Concurrent update | Two `PUT`s with the same `If-Match` | One 200, one 412 |
| Retry safety | Repeat `POST` with the same `Idempotency-Key` | One product created |

Also inspect plans for the hot queries with `EXPLAIN (ANALYZE, BUFFERS)`: expect `Index Scan` / `Index Only Scan`, no sequential scan on `products`.

## 9.5 Runbooks (short)

| Incident | First steps |
|---|---|
| Redis down | Confirm circuit breaker opened; watch PostgreSQL load and shedding rate; fail over to the replica; warm the cache gradually afterwards |
| Elasticsearch down | Expect 503 on search only; restart/restore nodes; Indexer catches up from Kafka; if the index is damaged, reindex into a new index and switch the alias |
| Indexer lag growing | Check consumer errors and the dead-letter topic; scale consumers; confirm Debezium is running and the replication slot is not accumulating WAL |
| High p99 | Check hit ratio first, then database queries/s and slow queries, then Redis latency |
| Stale price reported | Check Indexer lag, whether the `DEL`/purge ran, then wait for TTL; browser copies live up to `max-age` |

## 9.6 Security and limits

- JWT validated at the gateway; RBAC for write endpoints (`admin`, `seller`).
- Input validation on every write (title length, price >= 0, attribute shape), because PostgreSQL does not validate `jsonb` contents.
- Rate limiting per client at the gateway; request size caps; query size/depth caps on search.
- Presigned upload URLs are short-lived (5 minutes) and restricted to image content types.
