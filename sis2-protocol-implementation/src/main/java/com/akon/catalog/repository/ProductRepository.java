package com.akon.catalog.repository;

import com.akon.catalog.model.Product;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public class ProductRepository {
    private final JdbcTemplate jdbc;

    // Превращаем одну строку SQL-результата в Java-объект.
    private final RowMapper<Product> productMapper = (row, number) -> new Product(
            row.getString("id"), row.getString("title"),
            row.getBigDecimal("price").toPlainString(), row.getInt("stock"),
            row.getLong("version"), row.getString("currency"),
            row.getLong("category_id"), row.getString("updated_at"));

    public ProductRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Product> find(String id) {
        return jdbc.query("SELECT * FROM products WHERE id = ?", productMapper, id)
                .stream().findFirst();
    }

    public List<Product> list(String afterId, int limit) {
        return jdbc.query("SELECT * FROM products WHERE id > ? ORDER BY id LIMIT ?",
                productMapper, afterId, limit);
    }

    public void insert(Product product) {
        jdbc.update("""
                INSERT INTO products (id, title, price, stock, version, currency, category_id, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, product.id(), product.title(), new BigDecimal(product.price()), product.stock(),
                product.version(), product.currency(), product.categoryId(), product.updatedAt());
    }

    public boolean update(Product product, long expectedVersion) {
        // Проверка версии и запись выполняются ОДНИМ SQL-запросом.
        return jdbc.update("""
                UPDATE products SET title=?, price=?, stock=?, version=?, currency=?, category_id=?, updated_at=?
                WHERE id=? AND version=?
                """, product.title(), new BigDecimal(product.price()), product.stock(), product.version(),
                product.currency(), product.categoryId(), product.updatedAt(), product.id(), expectedVersion) == 1;
    }

    public boolean delete(String id) {
        return jdbc.update("DELETE FROM products WHERE id=?", id) == 1;
    }

    public void reserveKey(String key, String body) {
        jdbc.update("INSERT INTO idempotency_keys VALUES (?, ?, ?)", key, body, "");
    }

    public void saveResponse(String key, String response) {
        jdbc.update("UPDATE idempotency_keys SET response_body=? WHERE request_key=?", response, key);
    }

    public SavedRequest getSavedRequest(String key) {
        return jdbc.queryForObject("SELECT * FROM idempotency_keys WHERE request_key=?",
                (row, number) -> new SavedRequest(row.getString("request_body"), row.getString("response_body")), key);
    }

    public record SavedRequest(String body, String response) {}
}
