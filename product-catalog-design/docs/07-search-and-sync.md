# 7. Search and data synchronisation

> Lectures 3-6 cover caching, databases, concurrency and retries. Outbox, CDC and Kafka are scheduled for week 9 of the course and replication for week 7, so the details below are standard practice and should be checked against those lectures when they are available.

## 7.1 Why a separate search engine

PostgreSQL B-trees answer `=`, `<`, `BETWEEN` and `ORDER BY` (Lecture 6). They cannot serve `LIKE '%iphone%'`, relevance ranking or multi-filter facets efficiently: the planner falls back to a sequential scan over 1M rows. Elasticsearch uses an **inverted index** (term -> documents):

```
"iphone" -> [42, 77, 103]
"15"     -> [42, 103, 250]
query "iphone 15" -> intersection -> [42, 103]
```

PostgreSQL stays the source of truth; Elasticsearch is a derived, eventually consistent copy.

## 7.2 Index design

```json
{
  "mappings": {
    "properties": {
      "title":        { "type": "text" },
      "description":  { "type": "text" },
      "brand":        { "type": "keyword" },
      "category_ids": { "type": "keyword" },
      "price":        { "type": "scaled_float", "scaling_factor": 100 },
      "currency":     { "type": "keyword" },
      "attributes": {
        "type": "nested",
        "properties": {
          "name":  { "type": "keyword" },
          "value": { "type": "keyword" }
        }
      },
      "version":      { "type": "long" }
    }
  }
}
```

- `text` fields are analysed (split into terms, lower-cased) for full-text search.
- `keyword` fields are exact values used for filters and facets.
- `category_ids` contains the category and all its ancestors, so one filter matches a whole subtree.
- 3 primary shards with 1 replica each. Shards parallelise queries; replicas give availability and read capacity.

### Query shape

Free text goes in `must` (scored). Filters go in `filter` (yes/no, no scoring, cached by Elasticsearch):

```json
{
  "size": 20,
  "query": {
    "bool": {
      "must":   [ { "multi_match": { "query": "iphone 15", "fields": ["title^3", "description"] } } ],
      "filter": [
        { "term":  { "brand": "Apple" } },
        { "term":  { "category_ids": "2" } },
        { "range": { "price": { "gte": 300000, "lte": 600000 } } }
      ]
    }
  },
  "sort": [ { "_score": "desc" }, { "price": "asc" } ],
  "search_after": [ 12.3, 450000 ]
}
```

Paging uses `search_after` (the sort values of the last hit), the cursor equivalent; deep offsets are expensive in Elasticsearch.

## 7.3 The synchronisation problem

A product change must reach four places: PostgreSQL, Elasticsearch, Redis and the CDN.

**Dual write is unsafe:**

```java
db.save(product);       // succeeds
kafka.send(event);      // network fails -> Elasticsearch and Redis stay stale forever
```

A database and a message broker cannot share one transaction. Calling the network inside a database transaction is also forbidden (Lecture 6): the row lock would be held for as long as the network call takes.

## 7.4 Solution: transactional outbox + CDC

```sql
BEGIN;
UPDATE products SET price = 450000, version = 6 WHERE id = 42 AND version = 5;
INSERT INTO outbox (aggregate_id, type, payload)
VALUES (42, 'product_updated', '{"id": 42, "version": 6}');
COMMIT;
```

Both writes commit or neither does, so an event can neither be lost nor describe a change that did not happen.

```
PostgreSQL (products + outbox)
   -> WAL (write-ahead log, PostgreSQL's own change journal)
   -> Debezium (CDC) reads the outbox rows
   -> Kafka topic product-changes (key = product id)
   -> Indexer / Invalidator
        |- Elasticsearch: upsert or delete document _id = product id
        |- Redis: DEL product:42 (again after ~500 ms)
        |- L1: PUBLISH invalidate product:42
        |- CDN: purge Surrogate-Key product-42
```

This is exactly the "invalidate by event" design from Lecture 5: the writer only says what changed; each consumer knows its own keys.

### Alternatives considered

| Approach | Problem |
|---|---|
| Dual write | Not atomic; copies diverge |
| Polling (`WHERE updated_at > ...`) | Slow, adds database load, cannot see deletes |
| Database triggers | Slow writes, hard to maintain |
| **Outbox + CDC** | More moving parts, but atomic and reliable |

## 7.5 Delivery semantics

- **At-least-once.** An event can be delivered more than once (after a restart, for example), so the Indexer must be **idempotent**:
  - Elasticsearch: `_id = product_id` with **external versioning** (`version = product.version`). Elasticsearch rejects an older version, so a late or duplicate event cannot overwrite newer data. Out-of-order delivery is harmless for the same reason.
  - Redis `DEL`, L1 eviction and CDN purge are naturally idempotent.
- **Ordering.** The Kafka key is the product id, so all events of one product go to one partition and are read in order.
- **Lag.** Normally milliseconds to seconds. Alert when lag exceeds 30 s.
- **Poison messages.** An event that keeps failing goes to a dead-letter topic after a few attempts, so one bad record does not block the partition.
- **Lost events.** Kafka retention is 7 days, so a stopped Indexer catches up. If an event is lost anyway, the **TTL** on cached copies is the safety net (Lecture 5: events can be lost, keep the TTL), and a full reindex repairs Elasticsearch.

## 7.6 Rebuilding the index without downtime

1. Create `products_v2` with the new mapping.
2. Fill it from PostgreSQL (and replay events from the time the copy started).
3. Atomically switch the alias `products` from `products_v1` to `products_v2`.
4. Delete the old index after a safety period.

Clients and the Search Service always use the alias, so nothing changes for them.

## 7.7 Operational notes

- Delete published outbox rows periodically (for example older than 7 days); otherwise the table grows forever.
- Monitor the Debezium replication slot: if the connector is stopped, PostgreSQL retains WAL and can fill the disk.
- Each product change adds one extra `INSERT` (the outbox row). At 20-60 writes/s this is negligible.
