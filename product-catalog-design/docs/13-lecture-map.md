# 13. Course concepts applied in this design

Course: Backend for Highloaded Environment (KBTU, Fall 2026). Lectures 3-6 are used directly; later topics are noted separately.

## Lecture 3: networking and protocols

| Concept | Where applied |
|---|---|
| REST at the public boundary; `GET` + `ETag` + `Cache-Control` | [API 3.1](03-api.md#31-conventions), [3.6](03-api.md#36-caching-headers) |
| Version in the path | `/api/v1/` |
| Breaking versus non-breaking changes, deprecation process | [API 3.1](03-api.md#31-conventions) |
| Idempotent methods; idempotency keys (store the response, unique constraint, 24 h TTL) | [API 3.5](03-api.md#35-idempotency) |
| A timeout is not a failure (unknown outcome) | [Resilience 8.3](08-resilience.md#83-retries-lecture-3) |
| Deadlines travel down the chain | [Resilience 8.2](08-resilience.md#82-timeouts-and-deadlines-lecture-3) |
| Backoff with full jitter, 3 attempts, retry budget | [Resilience 8.3](08-resilience.md#83-retries-lecture-3) |
| Retry amplification (27x) | [Resilience 8.3](08-resilience.md#83-retries-lecture-3) |
| Boundary rule: REST outside, gRPC possible inside | [Architecture 5.8](05-architecture.md#58-protocol-choices-lecture-3-boundary-rule) |

## Lecture 4: concurrency inside a service

| Concept | Where applied |
|---|---|
| Little's Law `L = lambda x W` | [Capacity 4.5](04-capacity-estimation.md#45-concurrency-littles-law) |
| Bounded queues instead of unbounded | [Resilience 8.4](08-resilience.md#84-overload-protection-lecture-4) |
| Semaphore per downstream; cheap threads do not remove the DB limit | [Resilience 8.4](08-resilience.md#84-overload-protection-lecture-4) |
| Fail fast with `429/503` + `Retry-After`; serve 100% of capacity | [Resilience 8.4](08-resilience.md#84-overload-protection-lecture-4) |
| Drop requests whose deadline has passed | [Resilience 8.4](08-resilience.md#84-overload-protection-lecture-4) |
| Lightweight threads (goroutines, virtual threads, async) for waiting-heavy handlers, with explicit limits downstream | [Resilience 8.4](08-resilience.md#84-overload-protection-lecture-4) |

## Lecture 5: caching

| Concept | Where applied |
|---|---|
| Latency ladder; effective latency `T = h*tc + (1-h)*tdb` | [Capacity 4.4](04-capacity-estimation.md#44-cache-effectiveness) |
| Hit ratio moves the mean before the tail; p99 is the DB's p99 above 1% misses | [Capacity 4.4](04-capacity-estimation.md#44-cache-effectiveness) |
| Cache-aside, delete not set; write-through and write-behind compared | [Caching 6.2](06-caching.md#62-strategy-cache-aside-delete-on-write) |
| Redis: `maxmemory`, `allkeys-lfu`, client timeout, single-threaded commands | [Caching 6.6](06-caching.md#66-configuration) |
| TTL is never optional; TTL table; jitter formula | [Caching 6.3](06-caching.md#63-ttl-and-jitter) |
| Stale-write race, delayed second delete, versioned values | [Caching 6.4](06-caching.md#64-invalidation) |
| Invalidate by event (UPDATE + outbox -> CDC/Kafka -> delete keys, reindex, purge tag) | [Search and sync 7.4](07-search-and-sync.md#74-solution-transactional-outbox--cdc) |
| Stampede (250 queries) and its three defences | [Caching 6.5](06-caching.md#65-failure-modes-of-a-cache) |
| Penetration, avalanche, outage; "can the database survive without the cache?" | [Caching 6.5](06-caching.md#65-failure-modes-of-a-cache) |
| Two-level cache (Caffeine + Redis) with pub/sub invalidation fan-out | [Caching 6.4](06-caching.md#64-invalidation), [6.6](06-caching.md#66-configuration) |
| CDN headers: `max-age`, `s-maxage`, `stale-while-revalidate`, `Surrogate-Key`; `private, no-store` | [API 3.6](03-api.md#36-caching-headers), [Caching 6.1](06-caching.md#61-layers) |

## Lecture 6: databases I

| Concept | Where applied |
|---|---|
| B-tree (fan-out ~300), equality and ranges only | [Data model 2.5](02-data-model.md#25-indexes), [Search 7.1](07-search-and-sync.md#71-why-a-separate-search-engine) |
| Composite index and leftmost prefix; covering `INCLUDE` | [`db/schema.sql`](../db/schema.sql) |
| `EXPLAIN (ANALYZE, BUFFERS)`; plan nodes | [Operations 9.4](09-scaling-and-operations.md#94-load-test-plan) |
| N+1 | [API 3.3](03-api.md#33-pagination-cursor-keyset-not-offset) (list is one query) |
| Lost update and its three fixes (atomic, `FOR UPDATE`, optimistic version) | [API 3.4](03-api.md#34-concurrency-control-etag-and-if-match) |
| MVCC, VACUUM, open transactions | [Resilience 8.5](08-resilience.md#85-database-safety-settings-lecture-6) |
| `idle_in_transaction_session_timeout`, `statement_timeout`, `lock_timeout` | [Resilience 8.5](08-resilience.md#85-database-safety-settings-lecture-6) |
| Never call the network inside a transaction | [Search and sync 7.3](07-search-and-sync.md#73-the-synchronisation-problem) |
| Retry `40001` and `40P01` | [Resilience 8.5](08-resilience.md#85-database-safety-settings-lecture-6) |

## Announced for later weeks (not yet in the lectures)

| Topic | Course week | Used here for |
|---|---|---|
| Replication, read replicas and lag, failover, sharding, connection pooling | 7 | PostgreSQL topology, "no sharding" decision |
| Kafka, outbox, change data capture | 9 | Search synchronisation pipeline |
| Circuit breaker | 11 | Redis outage handling |
