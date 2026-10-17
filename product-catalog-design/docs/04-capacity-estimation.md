# 4. Capacity estimation

All figures are estimates derived from the stated assumptions. Items marked *(verify)* must be confirmed by the load test in [docs/09-scaling-and-operations.md](09-scaling-and-operations.md).

## 4.1 Traffic

| Quantity | Calculation | Result |
|---|---|---|
| Average reads | given | 10,000 RPS |
| Reads per day | 10,000 x 86,400 | ~864 million |
| Peak reads (x3) | 10,000 x 3 | 30,000 RPS |
| Average writes | given | 20 RPS |
| Writes per day | 20 x 86,400 | ~1.7 million |
| Peak writes (x3) | 20 x 3 | 60 RPS |
| Read : write ratio | 10,000 / 20 | 500 : 1 |

## 4.2 Storage

| Item | Calculation | Result |
|---|---|---|
| One product row | see [data model](02-data-model.md#22-product) | ~2 KB (2,051 B) |
| Products table | 1,000,000 x 2 KB | **~2 GB** |
| PostgreSQL indexes | estimate | ~1 GB |
| Redis data | 1M products x ~2 KB | ~2 GB; `maxmemory 4gb` leaves room for overhead and keys |
| Elasticsearch index | estimate (analysed text + inverted index) | ~3 GB primary; with 1 replica ~6 GB in total |
| Images | 1,000,000 x 5 x 200 KB | **~1 TB** in S3 (served by CDN) |
| Kafka | 20 events/s x ~0.5 KB | ~10 KB/s, negligible |

The metadata is small enough to hold entirely in memory. Images are the only large data set and they never touch the databases.

## 4.3 Bandwidth

| Quantity | Calculation | Result |
|---|---|---|
| JSON egress at the origin | 10,000 RPS x 2 KB | 20 MB/s = **160 Mbit/s** |
| Effect of the CDN | Public GETs are cached at the edge | The origin sees only CDN misses |
| Image traffic | Served from CDN/S3 | Does not pass through our services |

## 4.4 Cache effectiveness

Effective latency (Lecture 5): `T = h * t_cache + (1 - h) * t_db`, with `t_cache = 0.5 ms` and `t_db = 10 ms`.

| Hit ratio | Mean latency | DB queries per second (10,000 RPS) | DB queries at peak (30,000 RPS) |
|---|---|---|---|
| 80% | 2.4 ms | 2,000 | 6,000 |
| **95%** | **~1.0 ms** | **500** | **1,500** |
| 99% | 0.6 ms | 100 | 300 |
| 99.9% | 0.5 ms | 10 | 30 |

For 95%: `0.95 x 0.5 + 0.05 x 10 = 0.975 ms`.

**Important:** if the miss rate is above 1%, **p99 equals the database's p99**, not the cache's. The design therefore only meets the p99 target if PostgreSQL answers an indexed primary-key lookup well inside the budget (2-10 ms typical) *(verify)*.

## 4.5 Concurrency (Little's Law)

`L = lambda x W` (Lecture 4): requests in flight = arrival rate x time in system.

| Case | Calculation | In flight |
|---|---|---|
| Database with cache (95%) | 500 RPS x 0.010 s | **~5 queries** |
| Database without cache | 10,000 RPS x 0.010 s | **~100 queries** |
| Cache hit path | 10,000 RPS x ~0.001 s | ~10 requests |

The cache cuts database concurrency by 20x. A connection pool of about 20 per service instance group is enough on the miss path; without the cache a pool of 100+ would be needed and PostgreSQL would be the bottleneck.

## 4.6 Component sizing (starting point, verify under load)

| Component | Starting size | Notes |
|---|---|---|
| Product Service | 4+ stateless instances across 2-3 AZs | Autoscale on RPS/CPU; N-1 capacity must still serve peak |
| Redis Cluster | 3 shards x (1 primary + 1 replica) | ~0.7 GB data per shard; one Redis node handles on the order of 10^5 simple ops/s, so 10,000 RPS is far below the limit. Replica is mandatory (cache is part of capacity) |
| PostgreSQL | 1 primary + 2 read replicas, async | Reads on replicas; primary takes only 20-60 writes/s |
| Elasticsearch | 3 primary shards + 1 replica each | One replica copy keeps search alive when a node is lost |
| Kafka | 3 brokers, topic `product-changes`, 6 partitions, replication factor 3 | Key = product id keeps per-product order |
| L1 cache | 10,000 keys per instance (1% of catalog) | TTL ~10 s |

## 4.7 Latency budget (p99 < 80 ms)

| Step | Budget (estimate) |
|---|---|
| CDN / load balancer / gateway | ~5 ms |
| Product Service CPU and serialisation | ~3 ms |
| L1 lookup | ~0.01 ms |
| Redis lookup (normal) | ~1 ms |
| PostgreSQL replica on a miss | ~10-15 ms |

| Path | Total |
|---|---|
| L1 hit | ~8 ms |
| Redis hit | ~9 ms |
| Miss (Redis + DB) | ~20-25 ms |
| **Worst case: Redis times out at 50 ms, then DB** | 5 + 3 + 50 + 15 = **~73 ms**, still under 80 ms |

This is why the Redis client timeout is 50 ms and not longer. A circuit breaker removes the 50 ms wait while Redis is known to be down.
