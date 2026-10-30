package com.akon.catalog.model;

import java.util.List;

public record ProductPage(List<Product> items, String nextCursor) {}
