package com.logicaldoc.healdan.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.SystemInfo;
import com.logicaldoc.healdan.api.model.ProviderInfo;
import com.logicaldoc.healdan.api.model.ServerInfo;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.signin.SignInProviders;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * {@code GET /v1/server}: what a client checks first, before it signs in
 * (spec section 6.1), with the ways to sign in: a password, and the sign-in
 * providers that are on, which the sign-in page and the helper show as buttons
 * (M4 spec section 11).
 */
@Component
@Path("/v1/server")
@Produces(MediaType.APPLICATION_JSON)
public class ServerResource {

	/** Signing in with a username and password */
	static final String PASSWORD = "password";

	/** Signing in through a sign-in provider, at {@code /api/signin/<id>} */
	static final String PROVIDER = "provider";

	@GET
	@Public
	public ServerInfo get() {
		HealdanSettings settings = HealdanSettings.get();
		Map<String, String> labels = new LinkedHashMap<>();
		labels.put("matter", settings.matterLabel());
		labels.put("matters", settings.matterLabelPlural());

		SignInProviders signIn = SignInProviders.get();
		// Only those people can sign in through: on, and with an issuer, a client id and a secret that opens
		List<ProviderInfo> providers = signIn.enabled().stream().map(p -> new ProviderInfo(p.id(), p.label()))
				.toList();
		List<String> ways = providers.isEmpty() ? List.of(PASSWORD) : List.of(PASSWORD, PROVIDER);
		return new ServerInfo("Healdan", SystemInfo.get().getRelease(), "v1", labels, ways, providers,
				signIn.passwordLabel());
	}
}
