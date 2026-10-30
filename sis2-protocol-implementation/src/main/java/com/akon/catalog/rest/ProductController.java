package com.akon.catalog.rest;

import com.akon.catalog.exception.CatalogException;
import com.akon.catalog.model.*;
import com.akon.catalog.service.ProductService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {
    private final ProductService service;

    public ProductController(ProductService service) { this.service = service; }

    @GetMapping("/{id}")
    public ResponseEntity<Product> get(@PathVariable String id) {
        return response(service.get(id));
    }

    @GetMapping
    public ProductPage list(@RequestParam(defaultValue = "20") int limit,
                            @RequestParam(required = false) String cursor) {
        return service.list(limit, cursor);
    }

    @PostMapping
    public ResponseEntity<Product> create(@RequestHeader("Idempotency-Key") String key,
                                          @RequestBody ProductInput input) {
        Product product = service.create(key, input);
        return ResponseEntity.created(URI.create("/api/v1/products/" + product.id()))
                .eTag(Long.toString(product.version())).body(product);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Product> update(@PathVariable String id,
            @RequestHeader(value = "If-Match", required = false) String version,
            @RequestBody ProductInput input) {
        if (version == null) throw new CatalogException(
                CatalogException.Kind.PRECONDITION_REQUIRED, "Передайте If-Match из ETag");
        if (!version.matches("\"[1-9][0-9]*\"")) throw CatalogException.invalid("If-Match: версия в двойных кавычках");
        try {
            return response(service.update(id, Long.parseLong(version.substring(1, version.length() - 1)), input));
        } catch (NumberFormatException e) {
            throw CatalogException.invalid("Слишком большая version");
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<Product> response(Product product) {
        return ResponseEntity.ok().eTag(Long.toString(product.version())).body(product);
    }
}
