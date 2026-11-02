package com.logicaldoc.healdan.api.model;

import java.util.List;
import java.util.Map;

/**
 * What {@code GET /v1/server} tells a client before it signs in (spec
 * section 6.1, M4 spec section 11).
 *
 * @param product always {@code Healdan}
 * @param version the server's release
 * @param apiVersion the API's version, {@code v1}
 * @param labels the display names of a matter: {@code matter} and
 *        {@code matters}
 * @param signIn the ways to sign in: {@code password}, and {@code provider}
 *        when a sign-in provider is on
 * @param providers the providers people can sign in through, in the order of
 *        their buttons
 * @param passwordLabel the label of the link to sign in with a password
 */
public record ServerInfo(String product, String version, String apiVersion, Map<String, String> labels,
		List<String> signIn, List<ProviderInfo> providers, String passwordLabel) {
}
