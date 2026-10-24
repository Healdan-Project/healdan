package com.logicaldoc.healdan.api.model;

/**
 * The optional body of {@code POST /v1/documents/{docNumber}/checkout} (M3
 * spec section 9.4).
 *
 * @param move true to move the caller's own check-out from another of their
 *        devices to this one
 * @param app the app the document is opened in, such as
 *        {@code LibreOffice}, or null
 */
public record CheckoutRequest(Boolean move, String app) {
}
