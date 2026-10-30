package com.akon.catalog.model;

// record — обычный объект данных: Java сама создаёт конструктор и методы чтения.
public record Product(
        String id, String title, String price, Integer stock,
        long version, String currency, Long categoryId, String updatedAt) {}
