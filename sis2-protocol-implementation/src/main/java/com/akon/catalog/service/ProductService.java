package com.akon.catalog.service;

import com.akon.catalog.exception.CatalogException;
import com.akon.catalog.model.*;
import com.akon.catalog.repository.ProductRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static com.akon.catalog.exception.CatalogException.Kind.*;

@Service
public class ProductService {
    private final ProductRepository repository;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;

    public ProductService(ProductRepository repository, TransactionTemplate transaction, ObjectMapper json) {
        this.repository = repository;
        this.transaction = transaction;
        this.json = json;
        this.transaction.setTimeout(5);
    }

    public Product get(String id) {
        return repository.find(uuid(id)).orElseThrow(() ->
                new CatalogException(NOT_FOUND, "Товар не найден"));
    }

    public ProductPage list(int limit, String cursor) {
        if (limit < 1 || limit > 100) throw CatalogException.invalid("limit должен быть от 1 до 100");
        String afterId = "";
        if (cursor != null && !cursor.isBlank()) {
            try {
                afterId = uuid(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw CatalogException.invalid("Некорректный cursor");
            }
        }
        var rows = repository.list(afterId, limit + 1);
        boolean hasMore = rows.size() > limit;
        var items = rows.subList(0, Math.min(limit, rows.size()));
        String next = hasMore ? Base64.getUrlEncoder().withoutPadding().encodeToString(
                items.getLast().id().getBytes(StandardCharsets.UTF_8)) : null;
        return new ProductPage(items, next);
    }

    public Product create(String key, ProductInput input) {
        String validKey = uuid(key);
        ProductInput validInput = validate(input);
        String body = encode(validInput);
        try {
            return transaction.execute(status -> {
                // PRIMARY KEY защищает от дублей даже при двух экземплярах приложения.
                repository.reserveKey(validKey, body);
                Product product = product(UUID.randomUUID().toString(), 1, validInput);
                repository.insert(product);
                repository.saveResponse(validKey, encode(product));
                return product;
            });
        } catch (DuplicateKeyException duplicate) {
            // Сюда попадаем ПОСЛЕ отката транзакции. PostgreSQL нельзя читать внутри
            // транзакции, в которой уже произошла ошибка уникальности.
            var saved = repository.getSavedRequest(validKey);
            if (!saved.body().equals(body)) {
                throw new CatalogException(KEY_REUSED, "Этот ключ уже использован с другими данными");
            }
            return decode(saved.response());
        }
    }

    public Product update(String id, long version, ProductInput input) {
        String validId = uuid(id);
        if (version < 1 || version == Long.MAX_VALUE) throw CatalogException.invalid("Некорректная version");
        ProductInput validInput = validate(input);
        return transaction.execute(status -> {
            get(validId);
            Product product = product(validId, version + 1, validInput);
            if (!repository.update(product, version)) {
                throw new CatalogException(VERSION_CONFLICT, "Товар изменился. Прочитайте его заново");
            }
            return product;
        });
    }

    public void delete(String id) {
        if (!repository.delete(uuid(id))) throw new CatalogException(NOT_FOUND, "Товар не найден");
    }

    private ProductInput validate(ProductInput input) {
        if (input == null || input.title() == null || input.title().isBlank() || input.title().length() > 200)
            throw CatalogException.invalid("Название должно содержать от 1 до 200 символов");
        if (input.price() == null || !input.price().matches("[0-9]{1,17}(\\.[0-9]{1,2})?"))
            throw CatalogException.invalid("Цена: неотрицательная строка, максимум 17 цифр и 2 знака после точки");
        BigDecimal price = new BigDecimal(input.price()).setScale(2, RoundingMode.UNNECESSARY);
        int stock = input.stock() == null ? 0 : input.stock();
        if (stock < 0) throw CatalogException.invalid("Остаток не может быть отрицательным");
        if (input.currency() == null || !input.currency().matches("[A-Z]{3}"))
            throw CatalogException.invalid("currency: три заглавные латинские буквы, например KZT");
        if (input.categoryId() == null || input.categoryId() < 1)
            throw CatalogException.invalid("category_id должен быть положительным");
        return new ProductInput(input.title().trim(), price.toPlainString(), stock, input.currency(), input.categoryId());
    }

    private Product product(String id, long version, ProductInput input) {
        return new Product(id, input.title(), input.price(), input.stock(), version,
                input.currency(), input.categoryId(), Instant.now().toString());
    }

    private String uuid(String value) {
        try {
            if (value == null || value.length() != 36) throw new IllegalArgumentException();
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException e) {
            throw CatalogException.invalid("id и Idempotency-Key должны быть UUID");
        }
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Ошибка сериализации", e); }
    }

    private Product decode(String value) {
        try { return json.readValue(value, Product.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Ошибка чтения сохранённого ответа", e); }
    }
}
