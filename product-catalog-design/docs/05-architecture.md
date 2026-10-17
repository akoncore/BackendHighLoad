# 5. Architecture

## 5.1 High-level design

```mermaid
flowchart LR
    C["Client<br/>(web / mobile)"]
    CDN["CDN"]
    GW["API Gateway<br/>LB, rate limit, auth"]
    S3[("S3<br/>images")]
    PS["Product Service<br/>(read, L1 cache)"]
    SS["Search Service"]
    WS["Admin / Write Service"]
    R[("Redis Cluster<br/>L2, replicas")]
    PG[("PostgreSQL<br/>primary + read replicas")]
    ES[("Elasticsearch")]
    K[["Kafka<br/>product-changes"]]
    IDX["Indexer / Invalidator"]

    C -->|"public GET, images"| CDN
    C -->|"writes (not cached)"| GW
    CDN -->|"image origin"| S3
    CDN -->|"API cache miss"| GW
    GW -->|"GET product, category"| PS
    GW -->|"GET search"| SS
    GW -->|"POST / PUT / DELETE"| WS
    PS -->|"1. get"| R
    PS -->|"2. miss"| PG
    SS -->|"query"| ES
    WS -->|"write + outbox (1 txn)"| PG
    PG -->|"CDC (Debezium)"| K
    K -->|"consume"| IDX
    IDX -->|"upsert / delete"| ES
    IDX -->|"DEL key"| R
    IDX -->|"purge by tag"| CDN
    IDX -.->|"pub/sub: invalidate L1"| PS
```

Editable diagram: [`diagrams/product-catalog.excalidraw`](../diagrams/product-catalog.excalidraw) (open it at <https://excalidraw.com>).

## 5.2 Components

| Component | Responsibility | State | Scales by |
|---|---|---|---|
| **CDN** | Edge cache for images and public GET responses; purge by `Surrogate-Key` | Cache only | Provider |
| **API Gateway / LB** | TLS, JWT check, rate limit (429), deadline start, routing, load balancing | Stateless | Instances |
| **Product Service** | Card and category reads. L1 (Caffeine) -> Redis -> PostgreSQL replica. Coalesces rebuilds | Stateless (L1 is disposable) | Instances |
| **Search Service** | Translates query parameters to Elasticsearch queries | Stateless | Instances |
| **Admin / Write Service** | Validation, authorisation, writes with `version` check, idempotency, outbox, presigned upload URLs | Stateless | Instances |
| **Redis Cluster** | L2 cache, locks for coalescing, idempotency fast path | Derived data | Shards + replicas |
| **PostgreSQL** | Source of truth (products, categories, brands, outbox, idempotency keys) | Durable | Read replicas |
| **Elasticsearch** | Text search and filters; eventually consistent copy | Derived data | Shards + replicas |
| **Kafka + Debezium** | Reliable transport of change events from the outbox | Durable log | Partitions, brokers |
| **Indexer / Invalidator** | Applies each event to Elasticsearch, Redis, L1 (pub/sub) and CDN | Stateless, idempotent | Consumers in a group |
| **S3** | Image files | Durable | Provider |

## 5.3 Read path: product card

```mermaid
sequenceDiagram
    participant U as Client
    participant CDN
    participant GW as API Gateway
    participant PS as Product Service
    participant L1 as L1 (Caffeine)
    participant R as Redis
    participant PG as PostgreSQL replica

    U->>CDN: GET /api/v1/products/42
    alt CDN hit
        CDN-->>U: 200 (cached)
    else CDN miss
        CDN->>GW: GET (origin)
        GW->>PS: GET (deadline 80 ms)
        PS->>L1: get product:42
        alt L1 hit
            L1-->>PS: product
        else L1 miss
            PS->>R: GET product:42 (timeout 50 ms)
            alt Redis hit
                R-->>PS: product
            else Redis miss
                PS->>PG: SELECT ... WHERE id = 42
                PG-->>PS: row
                PS->>R: SETEX product:42 (TTL + jitter)
            end
            PS->>L1: put
        end
        PS-->>GW: 200
        GW-->>CDN: 200 + Cache-Control
        CDN-->>U: 200
    end
```

A CDN miss never goes straight to PostgreSQL: it goes to our origin, which checks L1 and Redis first. Each layer shields the next one; roughly 5% of requests reach the database.

## 5.4 Write path: update a product

```mermaid
sequenceDiagram
    participant S as Seller
    participant GW as API Gateway
    participant WS as Write Service
    participant PG as PostgreSQL primary
    participant D as Debezium (CDC)
    participant K as Kafka
    participant I as Indexer
    participant ES as Elasticsearch
    participant R as Redis
    participant CDN

    S->>GW: PUT /products/42 (If-Match: "5", JWT)
    GW->>WS: PUT
    WS->>PG: one transaction: UPDATE ... WHERE id = 42 AND version = 5, INSERT outbox, COMMIT
    alt 0 rows updated
        PG-->>WS: version mismatch
        WS-->>S: 412 Precondition Failed
    else committed
        WS-->>S: 200 OK (version 6)
        PG->>D: WAL change (outbox row)
        D->>K: product_updated (key = 42)
        K->>I: consume
        I->>ES: upsert (external version 6)
        I->>R: DEL product:42 (again after ~500 ms)
        I->>R: PUBLISH invalidate product:42 (L1 fan-out)
        I->>CDN: purge Surrogate-Key product-42
    end
```

The service never calls Kafka, Elasticsearch or Redis inside the database transaction (Lecture 6: never call the network inside a transaction). It only writes rows; the outbox and CDC take care of delivery.

## 5.5 Search path

`GET /search` -> API Gateway -> Search Service -> Elasticsearch. Search results are not looked up in PostgreSQL. If Elasticsearch is down, search answers `503` with `Retry-After`, while cards and category lists keep working from the cache/PostgreSQL path.

## 5.6 Image path

Upload: the client receives a presigned URL from Write Service and uploads straight to S3. Read: product JSON contains CDN URLs; the browser fetches images from the CDN, which falls back to S3. Image bytes never pass through the services (see [API 3.8](03-api.md#38-image-upload)).

## 5.7 Where each copy of the data can be stale

| Copy | Maximum staleness | How it is refreshed |
|---|---|---|
| Browser | `max-age` (60 s) | Expiry (a purge cannot reach browsers) |
| CDN | `s-maxage` (600 s) unless purged | Purge by `Surrogate-Key` from the Indexer |
| L1 (in process) | ~10 s TTL | Pub/sub invalidation; TTL is the backstop |
| Redis | TTL 5-15 min (stock/price 10-60 s) | `DEL` on change + delayed second `DEL`; TTL is the backstop |
| Elasticsearch | Event lag (ms to seconds) | Indexer upsert with external version |
| PostgreSQL replica | Replication lag (ms) | Asynchronous replication |

This is the system's **eventual consistency** model: bounded, observable and acceptable for a catalog.

## 5.8 Protocol choices (Lecture 3 boundary rule)

- **Client <-> gateway: REST/JSON.** We own neither the client nor its build, so be boring and cacheable.
- **Gateway <-> services:** REST is sufficient at this load. gRPC would be a valid internal choice (compiled contract, smaller payloads, deadlines propagate automatically), but it needs L7 load balancing because one HTTP/2 connection carries many calls. Not required for 10,000 RPS.
- **Events:** JSON in Kafka for readability. Avro with a schema registry is the usual upgrade when many consumers appear.
