package com.akon.catalog.exception;

public class CatalogException extends RuntimeException {
    public enum Kind { INVALID, NOT_FOUND, KEY_REUSED, VERSION_CONFLICT, PRECONDITION_REQUIRED }
    private final Kind kind;

    public CatalogException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() { return kind; }

    public static CatalogException invalid(String message) {
        return new CatalogException(Kind.INVALID, message);
    }
}
