# 11. Glossary

## Load and measurement

| Term | Meaning |
|---|---|
| RPS | Requests per second |
| p99 | 99% of requests complete at least this fast. The mean hides slow tails |
| Read-heavy | Reads far outnumber writes (here 500 : 1) |
| Eventual consistency | A change becomes visible everywhere after a short, bounded delay |
| HA / multi-AZ | The system survives the loss of an instance or a data centre (availability zone) |
| Little's Law | `L = lambda x W`: requests in flight = arrival rate x time in system |

## Caching

| Term | Meaning |
|---|---|
| Hit / miss | The data was / was not found in the cache |
| Hit ratio | Share of requests that are hits |
| Cache-aside | The application reads the cache, loads from the database on a miss and fills the cache itself |
| Write-through / write-behind | Writes go to cache and database together / to the cache first, database later (risk of data loss) |
| TTL | Time to live: how long a cache entry may exist |
| Jitter | Random variation (here in TTL or retry delay) so events do not happen simultaneously |
| Eviction, LFU | Removing entries when memory is full; LFU removes the least frequently used |
| L1 / L2 | In-process cache (Caffeine) / shared cache (Redis) |
| CDN | Network of edge servers close to users that caches responses and files |
| Surrogate-Key | Tag attached to a cached response so it can be purged by tag |
| stale-while-revalidate | Serve an expired copy while refreshing it in the background |
| Stale-write race | A slow reader writes an old value into the cache after the writer has already deleted the key |
| Delayed second delete | Deleting the key again shortly after commit to remove such a stale write |
| Stampede | A hot key expires and many requests rebuild it at once |
| Coalescing | Only one caller rebuilds a key; the others wait |
| Serve stale | Return the expired value while one caller refreshes it |
| Penetration | Requests for keys that do not exist always miss and reach the database |
| Bloom filter | Compact set that answers "definitely not present" or "maybe present" |
| Avalanche | Many keys expire together (or a cache node restarts empty) |

## API and reliability

| Term | Meaning |
|---|---|
| REST | Resources identified by URL; HTTP methods express the action |
| Idempotent | Doing it twice leaves the same state as doing it once (GET, PUT, DELETE) |
| Idempotency-Key | Client-generated id that makes a `POST` safe to retry |
| ETag / If-Match | Version tag of a resource / precondition that the version is still current (else 412) |
| Deadline | Total time budget of a request, shrinking as it passes downstream |
| Backoff + jitter | Increasing, randomised waits between retries |
| Retry amplification | Retrying at every layer multiplies the load (3 layers x 3 retries = 27x) |
| Load shedding | Rejecting excess requests immediately (429/503) to protect the rest |
| Rate limit | Cap on the requests one client may send |
| Semaphore | Limits how many calls may be in flight at once |
| Bounded queue | A queue with a maximum length; overflow is rejected |
| Circuit breaker | Stops calling a failing dependency for a while, then probes it |
| Cursor pagination | Continue from the last item seen (keyset) instead of skipping rows with OFFSET |
| Presigned URL | Temporary signed link allowing a direct upload to object storage |
| JWT / RBAC | Signed token carrying the user and role / permissions granted by role |
| API Gateway / LB | Entry point (auth, limits, routing) / spreads load across instances |
| Stateless | The service keeps no per-user state, so any instance can serve any request |

## Database

| Term | Meaning |
|---|---|
| B-tree | Shallow, wide sorted index; serves `=`, ranges and `ORDER BY` |
| Composite index / leftmost prefix | Index on several columns, usable from its leftmost column onward |
| Covering index | Contains all needed columns, so the table is not read (Index Only Scan) |
| EXPLAIN (ANALYZE, BUFFERS) | Shows the query plan and real timings and page reads |
| N+1 | One query for a list plus one per row; many round trips |
| Lost update | Two transactions read-modify-write the same row and one change vanishes |
| Optimistic lock | Update only if the version is unchanged; otherwise retry |
| MVCC | An update writes a new row version; readers do not block writers |
| VACUUM | Cleans dead row versions |
| jsonb | PostgreSQL binary JSON column, indexable with GIN |
| Replica / replication lag | Read-only copy / how far it is behind the primary |
| Failover | A replica takes over when the primary fails |
| Sharding | Splitting data across servers by key |

## Search and events

| Term | Meaning |
|---|---|
| Inverted index | Maps each term to the documents containing it |
| text / keyword | Analysed full-text field / exact-value field for filters |
| Shard / replica (Elasticsearch) | Slice of an index / copy of a slice |
| Alias reindex | Build a new index, then switch an alias to it without downtime |
| Dual write | Writing to two systems separately; not atomic, can diverge |
| Outbox | Event row written in the same transaction as the data change |
| CDC | Change data capture: reading the database's change log (WAL) to emit events |
| WAL | Write-ahead log, PostgreSQL's journal of changes |
| Kafka | Durable, partitioned log of events that consumers read at their own pace |
| At-least-once | An event may be delivered more than once, so consumers must be idempotent |
| External version | Version supplied by the writer; the store rejects older versions |
| Dead-letter topic | Where events that repeatedly fail are parked |
