package com.logicaldoc.healdan.api.model;

/**
 * A sign-in provider people can sign in through, as {@code GET /v1/server}
 * lists it (M4 spec section 11).
 *
 * @param id the provider's id, as in {@code /api/signin/<id>}
 * @param label the label of its button
 */
public record ProviderInfo(String id, String label) {
}
