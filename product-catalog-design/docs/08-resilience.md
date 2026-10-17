# 8. Resilience and failure handling

Goal (NFR7): when something breaks, degrade gracefully instead of collapsing. A service at 100% of capacity should serve 100% of capacity; without load shedding it serves close to zero, slowly (Lecture 4).

> The circuit breaker mechanism is covered in week 11 of the course (Lecture 3 defers it); it is described here at a standard-practice level.

## 8.1 Failure matrix

| Failure | What happens | Defence |
|---|---|---|
| **Redis down** | All traffic reaches PostgreSQL | 50 ms client timeout, fall back to the database, circuit breaker, load shedding; L1 keeps hot products |
| **Hot key expires** | Stampede: many identical rebuilds | Coalescing (`SET lock NX`), serve stale, refresh early |
| **Unknown ids** (penetration) | Every request misses | Cache "not found" for 30-60 s, Bloom filter |
| **Many keys expire together** (avalanche) | Wave of misses | TTL jitter, gradual warm-up, replicas |
| **Elasticsearch down** | No search | `GET /search` returns `503` + `Retry-After`; cards and category lists use cache/PostgreSQL; Kafka retains events for catch-up |
| **Database overloaded** | Queues grow, pool fills, everything times out | Load shedding (`429/503`), semaphore, bounded queue, drop already-expired requests |
| **Kafka lag or lost event** | Elasticsearch/cache stale | Lag alert (> 30 s), TTL as safety net, reindex via alias |
| **Retry storm** | Retries multiply load | Retry only idempotent calls, backoff + jitter, one layer, retry budget |
| **PostgreSQL primary fails** | Writes fail | Automatic failover to a replica; reads continue on remaining replicas |
| **Service instance fails** | Capacity drops | Stateless instances behind the LB, health checks, N-1 capacity |
| **AZ fails** | A third of capacity lost | Multi-AZ placement and headroom |

### Walk-through: Redis down

1. A request misses L1 and calls Redis; no answer within **50 ms**.
2. The service reads PostgreSQL (~10-15 ms) and answers. Worst case 5 + 3 + 50 + 15 = **~73 ms**, inside the 80 ms budget.
3. After repeated failures the **circuit breaker opens**: for about 30 s the service skips Redis entirely, removing the 50 ms wait; then it probes with one request and closes again when Redis answers.
4. Meanwhile the database sees up to the full read load, so the semaphore and load shedding (8.4) cap what reaches it, and the Redis replica fails over.

## 8.2 Timeouts and deadlines (Lecture 3)

- A network call without a timeout is a bug. Every client sets a **connect timeout** (short, near the round-trip time) and a **request timeout** (a business decision: how long the user waits), derived from measured percentiles.
- The gateway starts an **80 ms deadline**. Each service passes the **remaining budget** downstream (gRPC does this automatically; with HTTP the budget travels in a header) and checks it before starting more work. A service that keeps working after its caller has given up wastes capacity at the worst possible moment.

## 8.3 Retries (Lecture 3)

A timeout is not a failure; it is the absence of information (the work may have been done). Retry only when all three hold:

1. The operation is **idempotent**: `GET`, `PUT`, `DELETE`, or `POST` with an `Idempotency-Key`.
2. The error may be **transient**: connection refused, `503`, `429` with `Retry-After`, a timeout. Never `400/401/403/404/422`.
3. The **budget still allows it**: do not sleep past the caller's deadline.

```java
long base = 50, cap = 2_000;
for (int attempt = 0; ; attempt++) {
    try { return call(); }
    catch (Retryable e) {
        if (attempt == 3) throw e;                       // at most 3 attempts
        long window = Math.min(cap, base * (1L << attempt));
        long wait = rnd.nextLong(window);                // full jitter
        if (wait > budget.remaining()) throw e;
        Thread.sleep(wait);
    }
}
```

**Retry amplification:** if every layer retries 3 times, 1,000 RPS at the client becomes 3,000, 9,000 and finally **27,000 RPS** at the database; a brief slowdown turns into a sustained outage. Therefore:

- Retry in **one layer only** (the one closest to the user); everything below fails fast and reports upward.
- Give retries a **budget** (about 10% of successful traffic); above that, stop retrying.
- Add a circuit breaker for dependencies that are clearly down.

## 8.4 Overload protection (Lecture 4)

| Mechanism | Setting (starting point) | Purpose |
|---|---|---|
| Rate limit at the gateway | per IP/token | Refuse abusive clients early with `429` + `Retry-After` |
| Bounded queues | sized to what can be drained within one deadline | An unbounded queue turns overload into latency and then memory exhaustion |
| Semaphore per downstream | e.g. 40 concurrent calls to PostgreSQL, 50 ms acquire timeout, else `503` | Cheap threads (goroutines, virtual threads) remove the thread limit but **not** the database limit |
| Load shedding | `429/503` returned in microseconds | Serve 100% of capacity instead of ~0% slowly |
| Deadline check | drop requests whose deadline already passed | Do not do work for nobody |

## 8.5 Database safety settings (Lecture 6)

```
idle_in_transaction_session_timeout = '30s'
statement_timeout = '5s'            -- per role, not global
lock_timeout = '2s'
```

- Keep transactions short and never call the network inside one (a lock wait holds a pooled connection).
- Retry the whole transaction on `40001` (serialisation failure) and `40P01` (deadlock), with backoff.
- One forgotten open transaction stops VACUUM from cleaning dead row versions anywhere.

## 8.6 Cascades

Failures chain: Redis down -> database slows -> timeouts -> clients retry -> database gets more load. The defences are layered precisely to break the chain:

| Layer | Breaks the chain by |
|---|---|
| 50 ms timeout + circuit breaker | Not waiting on a dead Redis |
| L1 cache | Absorbing hot-key traffic |
| Semaphore + bounded queue | Limiting what reaches PostgreSQL |
| Load shedding | Rejecting the excess immediately |
| Retry in one layer with jitter and budget | Preventing multiplication of load |

## 8.7 Graceful degradation summary

| If this is down | Still works | Degraded |
|---|---|---|
| Redis | Cards, categories, search (slower) | Higher latency, load on PostgreSQL |
| Elasticsearch | Cards, categories | Search returns 503 |
| Kafka / Indexer | Cards, categories, search on current data | Changes appear late; TTL bounds staleness |
| A PostgreSQL replica | Everything | Less read capacity |
| PostgreSQL primary | All reads | Writes fail until failover |
| CDN | Everything (origin serves directly) | Higher origin load |
