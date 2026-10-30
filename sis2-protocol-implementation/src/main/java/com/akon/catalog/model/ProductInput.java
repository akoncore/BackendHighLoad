package com.akon.catalog.model;

// Здесь только данные клиента. id, version и updatedAt назначает сервер.
public record ProductInput(
        String title, String price, Integer stock, String currency, Long categoryId) {}
