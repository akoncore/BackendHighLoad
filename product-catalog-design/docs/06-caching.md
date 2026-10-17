# 6. Caching

Caching is the core of the design. The catalog is read 500 times more often than it is written, and it is small. Principle (Lecture 5): *the fastest call is the one you do not make*.

## 6.1 Layers

| Layer | Location | Holds | Latency | Size | TTL |
|---|---|---|---|---|---|
| Browser | Client | HTTP responses | 0 | n/a | `max-age` 60 s |
| **CDN** | Edge, near the user | Images, public GET responses | ~10-50 ms to the user | Provider | `s-maxage` 600 s, purge by tag |
| **L1** (Caffeine) | Inside each Product Service instance | Hottest product objects | ~0.1 us | ~10,000 keys (1% of catalog) | ~10 s |
| **L2** (Redis Cluster) | Shared, same zone | All product objects | 0.3-1 ms | ~2 GB data, `maxmemory 4gb` | 5-15 min + jitter |
| Source | PostgreSQL replica | Everything | 2-10 ms | ~2 GB | n/a |

Latency ladder (Lecture 5): in-process ~0.1 us, Redis same zone 0.3-1 ms, indexed PostgreSQL 2-10 ms.

### CDN versus Redis

| | CDN | Redis |
|---|---|---|
| Serves | The user's browser | Our own service |
| Stores | Complete HTTP responses and image files | Data objects (`product:42` -> JSON) |
| Controlled by | HTTP headers (`Cache-Control`, `Surrogate-Key`) | Our code (`GET`, `SETEX`, `DEL`) |
| On a hit | The request never reaches our system | The request is already inside our system |
| Protects | Our servers, our network, long distance | PostgreSQL |

They do not replace each other.

## 6.2 Strategy: cache-aside, delete on write

```java
Product get(long id) {
    String key = "product:" + id;
    Product p = redis.get(key);
    if (p != null) return p;            // hit
    p = db.findProduct(id);             // miss -> PostgreSQL
    redis.setex(key, ttlWithJitter(), p);
    return p;
}

void update(Product p) {
    db.save(p);
    redis.del("product:" + p.id());     // delete, not set
}
```

- **Why cache-aside:** only data that is actually read is cached; if Redis is down, reads fall through to the database.
- **Why `DEL`, not `SET`:** two concurrent `SET`s can land in the wrong order; a `DEL` cannot.
- **Rejected alternatives:** write-through (slower writes, caches cold data) and write-behind (a crash loses data the user was told was saved; suitable only for counters).

## 6.3 TTL and jitter

| Data | TTL |
|---|---|
| Product card | 5-15 min |
| Stock and price | 10-60 s, plus invalidation |
| "Not found" marker | 30-60 s |
| Category tree (in process) | refreshed every minute |

The TTL is **never optional**: it bounds staleness when an invalidation is lost.

```
ttl = base * (0.9 + 0.2 * random())      // base 10 min -> 9..11 min
```

Jitter stops keys that were filled together (after a deploy or warm-up) from expiring together.

## 6.4 Invalidation

1. **On change:** the Indexer deletes `product:{id}` in Redis, driven by the outbox event (see [docs/07](07-search-and-sync.md)).
2. **Delayed second delete (~500 ms later).** Closes the stale-write race:

   | Time | Reader | Writer |
   |---|---|---|
   | t1 | cache miss, reads price = 100 | |
   | t2 | paused (GC, slow network) | `UPDATE price = 120`, commit |
   | t3 | still paused | `DEL product:42` |
   | t4 | `SET product:42 = 100` | cache is now wrong until TTL |

   The second delete removes the stale value written at t4. A replica that lags behind the primary causes the same effect, so the second delete covers that case too.
3. **Versioned values (optional hardening).** Store `version` in the cached value and refuse to overwrite a newer version with an older one.
4. **L1 fan-out.** A delete in Redis does not reach the in-process L1 caches. The Indexer also publishes `invalidate product:42` on a Redis pub/sub channel; every Product Service instance subscribes and evicts the key. Pub/sub is fire-and-forget, so the ~10 s L1 TTL remains the backstop (Lecture 5: invalidation must fan out to every instance's L1, or instances disagree).
5. **CDN purge** by `Surrogate-Key` (`product-42`).

## 6.5 Failure modes of a cache

| Failure | What happens | Defence |
|---|---|---|
| **Stampede** (hot key expires) | 5,000 RPS x 50 ms rebuild = 250 identical queries; the extra load slows the rebuild and causes more misses | Coalescing: `SET lock NX PX=2000`, only the winner rebuilds, others wait briefly or **serve stale**. In process, Caffeine `get(key, loader)` does this; `refreshAfterWrite` refreshes early |
| **Penetration** (ids that do not exist) | Always a miss, always hits the database | Cache "not found" for 30-60 s; Bloom filter of valid ids. On product creation the key is deleted so a cached "not found" does not hide a new product |
| **Avalanche** (many keys expire together, or a node restarts empty) | Database receives a wave | TTL jitter, gradual warm-up, replicas so one node is not everything |
| **Outage** (Redis down) | All traffic reaches PostgreSQL | 50 ms client timeout, fall back to the database, circuit breaker, load shedding, L1 for hot keys |

Decide in advance: *can the database survive with no cache at all?* If not, the cache is part of capacity and must be operated like it. Here it is: Redis has replicas, alarms and a tested fallback, and load shedding protects PostgreSQL.

## 6.6 Configuration

### Redis

```
maxmemory 4gb
maxmemory-policy allkeys-lfu      # evict the least frequently used key
# client side
timeout = 50 ms
pool max = 32
on error -> read the database
```

- Without `maxmemory`, Redis grows until the operating system kills it. The default policy `noeviction` rejects writes when memory is full.
- Redis executes commands one at a time: never run `KEYS *`, and keep values small (a 50 MB value stalls every client).

### L1 (Caffeine)

```java
LoadingCache<String, Product> l1 = Caffeine.newBuilder()
    .maximumSize(10_000)
    .expireAfterWrite(Duration.ofSeconds(10))
    .build(key -> loadFromRedisOrDb(key));   // one loader call per key (coalescing)
```

### Why two cache levels

- A very popular product would otherwise land on **one Redis shard**; L1 absorbs most of its traffic.
- L1 costs nothing on the network (~0.1 us instead of ~1 ms).
- If Redis fails, hot products keep being served for a few seconds.
- Costs: one copy per instance, empty after restart, and an invalidation fan-out to design (6.4).

## 6.7 What is not cached

- Money-critical reads at purchase time (price and stock are re-read from the database).
- Anything personalised (`Cache-Control: private, no-store`). Caching a personalised page as `public` is the classic incident.

## 6.8 Metrics

Hit ratio per layer, p50/p99 of each layer, evictions, Redis memory and command latency, number of rebuilds per key, database queries per second. Measure p99 as well as the mean: if misses exceed 1%, p99 is the database's p99.
