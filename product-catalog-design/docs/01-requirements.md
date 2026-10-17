# 1. Requirements

## 1.1 Problem statement

Design the backend of an online store's **product catalog**: product cards, categories, and search with filters.

| Parameter | Value |
|---|---|
| Read load | ~10,000 RPS |
| Write load | ~20 RPS |
| Catalog size | 1,000,000 products |
| Latency target | p99 < 80 ms |

The workload is **read-heavy** (500 : 1). Everything in this design follows from that ratio and from the fact that the data set is small (about 2 GB).

## 1.2 Functional requirements

| ID | Requirement |
|---|---|
| FR1 | View a product card by id |
| FR2 | Browse the products of a category (pagination) |
| FR3 | Search with filters: text, brand, price range, attributes |
| FR4 | An admin or seller creates, updates and deletes products (including images) |

## 1.3 Non-functional requirements

| ID | Requirement | Target |
|---|---|---|
| NFR1 | Read throughput | ~10,000 RPS (peak assumed 3x = 30,000 RPS) |
| NFR2 | Write throughput | ~20 RPS |
| NFR3 | Data volume | 1M products, ~2 GB metadata, ~1 TB images |
| NFR4 | Latency | p99 < 80 ms for card, category list and search |
| NFR5 | Consistency | Eventual. A change may take a few seconds to appear everywhere |
| NFR6 | Availability | High availability, multi-AZ; no single point of failure |
| NFR7 | Overload behaviour | Degrade gracefully: reject early with 429/503, never collapse |

### Consistency expectations

| Data | Acceptable staleness |
|---|---|
| Product card (title, description, images) | Seconds to minutes |
| Price | Seconds (card), but the price must be re-validated at purchase time (out of scope) |
| Stock | 10-60 seconds on the card |
| Search results | Seconds (index lag) |

## 1.4 Out of scope

- Cart, checkout, orders and payments
- Reviews, ratings, likes and comments
- Recommendations and personalisation
- Stock reservation and warehouse logic
- Buyer accounts and login (only staff roles `admin` and `seller` exist)
- Per-seller ownership checks (would require a `seller_id` column; see Known limitations in the README)
- Multi-currency conversion and internationalisation

## 1.5 Assumptions

| # | Assumption |
|---|---|
| A1 | Peak traffic is about 3x the average |
| A2 | A product record averages ~2 KB (see docs/04-capacity-estimation.md) |
| A3 | Traffic is skewed: a small share of products receives most requests (Zipf-like). This is what makes a small L1 cache effective |
| A4 | A CDN, an object store (S3-compatible), Kafka with Debezium, Redis Cluster, Elasticsearch and PostgreSQL are available |
| A5 | Cache hit ratio of at least 95% is reachable and is verified by load test |
| A6 | Product pages are public; only write endpoints need authentication |

## 1.6 Acceptance criteria

1. p99 < 80 ms for card reads at 10,000 RPS with a Zipf-like key distribution.
2. With Redis stopped, the service keeps answering (slower) and returns 503 instead of timing out when overloaded.
3. A price change is visible on the card within the staleness budget in 1.3.
4. A retried `POST /products` with the same `Idempotency-Key` never creates a second product.
5. Two concurrent `PUT`s with the same `If-Match` version: exactly one succeeds, the other gets `412`.
6. With Elasticsearch stopped, card and category pages keep working; search returns `503` with `Retry-After`.
