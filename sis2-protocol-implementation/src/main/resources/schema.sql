CREATE TABLE IF NOT EXISTS products (
    id VARCHAR(36) PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    price DECIMAL(19,2) NOT NULL CHECK (price >= 0),
    currency VARCHAR(3) NOT NULL,
    category_id BIGINT NOT NULL,
    stock INTEGER NOT NULL CHECK (stock >= 0),
    version BIGINT NOT NULL,
    updated_at VARCHAR(40) NOT NULL
);

-- Сохраняем исходный ответ: повтор POST вернёт его даже после PUT или DELETE.
CREATE TABLE IF NOT EXISTS idempotency_keys (
    request_key VARCHAR(36) PRIMARY KEY,
    request_body TEXT NOT NULL,
    response_body TEXT NOT NULL
);
