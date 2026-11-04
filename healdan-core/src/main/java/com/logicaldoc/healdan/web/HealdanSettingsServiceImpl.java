// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.web;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.metadata.Template;
import com.logicaldoc.core.metadata.TemplateDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIClient;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIGroupMapping;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIHealdanSettings;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUILinkedAccount;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUISignInOptions;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUISignInProvider;
import com.logicaldoc.gui.frontend.client.healdan.services.HealdanSettingsService;
import com.logicaldoc.gui.frontend.client.healdan.services.SecretKeyMissingException;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.docnumber.DocNumberCounter;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.ClientService;
import com.logicaldoc.healdan.matter.MatterNumbering;
import com.logicaldoc.healdan.matter.NumberingMode;
import com.logicaldoc.healdan.profile.ProfileSchema;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.signin.GroupMapping;
import com.logicaldoc.healdan.signin.OutgoingEmail;
import com.logicaldoc.healdan.signin.Preset;
import com.logicaldoc.healdan.signin.ProviderClient;
import com.logicaldoc.healdan.signin.ProviderClient.Check;
import com.logicaldoc.healdan.signin.SignInAdmin;
import com.logicaldoc.healdan.signin.SignInIdentityDAO;
import com.logicaldoc.healdan.signin.SignInProvider;
import com.logicaldoc.healdan.signin.SignInProviders;
import com.logicaldoc.healdan.token.ApiTokens;
import com.logicaldoc.healdan.util.BasicPlane;
import com.logicaldoc.healdan.web.PublicAddress.Address;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The Healdan Settings panel's server side (spec 8.6), with its Sign-In tab's
 * (M4 spec section 10.3). Everything but {@link #labels()} is for
 * administrators only. No answer holds a sign-in provider's client secret.
 */
@WebServlet("/frontend/healdansettings")
public class HealdanSettingsServiceImpl extends HealdanRemoteService implements HealdanSettingsService {

	private static final long serialVersionUID = 1L;

	/** The longest matter label */
	static final int MAX_LABEL_LENGTH = 50;

	/** The longest Recent Matters window, in days: ten years */
	static final int MAX_RECENT_DAYS = 3650;

	/**
	 * The highest next doc #: far above the spec's 999,999,999, well below the
	 * counter's overflow and the 2^53 a browser's number keeps exactly
	 */
	static final long MAX_NEXT_DOC_NUMBER = 999_999_999_999L;

	/** The longest device sign-in lifetime, in days: ten years */
	static final int MAX_API_DAYS = 3650;

	/** What only an administrator may do with sign-in, for the refusal */
	private static final String SIGN_IN = "set up sign-in";

	/** The Check's problem when Healdan has no address the sign-in pages can use (ruling R5) */
	static final String NO_PUBLIC_ADDRESS = "Healdan doesn't know its own address, so nobody can sign in through a "
			+ "provider yet. Set LOGICALDOC_PUBLIC_URL to the server's https:// address, such as "
			+ "https://healdan.example/, and restart Healdan. Until then, the redirect address above is only a guess, "
			+ "from the address you're using.";

	/** An origin the API may be called from: scheme, host and optional port, no path */
	private static final Pattern ORIGIN = Pattern.compile("^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?$");

	@Override
	public GUIHealdanSettings load() throws ServerException {
		admin("change Healdan's settings");
		try {
			return current();
		} catch (PersistenceException | RuntimeException e) {
			throw failure("load the settings", e);
		}
	}

	@Override
	public GUIHealdanSettings save(GUIHealdanSettings settings) throws ServerException {
		admin("change Healdan's settings");
		try {
			Values values = check(settings);
			write(values);
			return current();
		} catch (HealdanRuleException e) {
			throw new ServerException(e.getMessage());
		} catch (PersistenceException | RuntimeException e) {
			throw failure("save the settings", e);
		}
	}

	@Override
	public String[] labels() throws ServerException {
		session();
		HealdanSettings settings = HealdanSettings.get();
		return new String[] { settings.matterLabel(), settings.matterLabelPlural() };
	}

	@Override
	public List<GUIClient> listClients() throws ServerException {
		admin("manage clients");
		try {
			List<GUIClient> clients = new ArrayList<>();
			for (Client client : ClientDAO.get().findAllByNumber())
				clients.add(toGUI(client));
			return clients;
		} catch (PersistenceException e) {
			throw failure("list the clients", e);
		}
	}

	@Override
	public GUIClient saveClient(GUIClient client) throws ServerException {
		User user = admin("manage clients");
		try {
			// A client's number never changes, so an edit only renames
			Client saved = client.getId() == 0L
					? ClientService.get().create(client.getNumber(), client.getName(), user)
					: ClientService.get().rename(client.getId(), client.getName(), user);
			return toGUI(saved);
		} catch (HealdanRuleException e) {
			throw new ServerException(e.getMessage());
		} catch (PersistenceException e) {
			throw failure("save the client", e);
		}
	}

	@Override
	public long suggestClientNumber() throws ServerException {
		admin("manage clients");
		try {
			return ClientService.get().suggestNumber();
		} catch (PersistenceException e) {
			throw failure("suggest a client number", e);
		}
	}

	/** The request being answered, which the redirect addresses are worked out from */
	protected HttpServletRequest request() {
		return getThreadLocalRequest();
	}

	/** Where Save Provider keeps a provider and its group map */
	protected SignInProviders providers() {
		return SignInProviders.get();
	}

	@Override
	public GUISignInOptions loadSignIn() throws ServerException {
		User user = admin(SIGN_IN);
		return call("load the sign-in settings", () -> {
			SignInProviders providers = SignInProviders.get();
			String base = redirectBase().base();
			List<GUISignInProvider> list = new ArrayList<>();
			for (SignInProvider provider : providers.all())
				list.add(toGUI(provider, base));
			GUISignInOptions options = new GUISignInOptions();
			options.setProviders(list.toArray(GUISignInProvider[]::new));
			options.setPasswordLabel(providers.passwordLabel());
			options.setAdminAlert(providers.adminAlert());
			options.setRecipients(providers.adminAlertRecipients().toArray(String[]::new));
			options.setOutgoingEmail(OutgoingEmail.isSetUp());
			options.setGroups(SignInBeans.mappableGroups(user.getTenantId()));
			return options;
		});
	}

	@Override
	public GUISignInProvider newProvider(String id, String preset) throws ServerException {
		admin(SIGN_IN);
		return call("start a sign-in provider", () -> {
			SignInProviders.get().checkNewId(id);
			GUISignInProvider gui = SignInBeans.toGUI(SignInProvider.fromPreset(id, Preset.of(preset)),
					redirect(redirectBase().base(), id), new GUIGroupMapping[0], 0);
			gui.setAdding(true);
			return gui;
		});
	}

	@Override
	public GUISignInProvider saveProvider(GUISignInProvider provider, String secret) throws ServerException {
		admin(SIGN_IN);
		return call("save the sign-in provider", () -> {
			if (provider == null)
				throw new HealdanRuleException("The provider is missing.");
			String id = provider.getId();
			SignInProviders providers = providers();
			// One lock with save's and saveGroups', so that two admins adding the same id can't both pass the check
			synchronized (providers) {
				if (provider.isAdding())
					providers.checkNewId(id);
				else if (providers.find(id).isEmpty())
					throw new HealdanRuleException("That provider doesn't exist. Someone may have removed it meanwhile.");
				// Checked first, so that a refused map doesn't leave the provider saved without it
				List<GroupMapping> map = providers.checkGroups(SignInBeans.mappings(provider.getGroups()));
				try {
					// In one transaction, so a failure part-way leaves the provider as it was, or an id never added
					providers.save(SignInBeans.fromGUI(provider), secret, provider.isNewKey(), map);
				} catch (SignInProviders.SecretKeyMissing e) {
					// Nothing saved: the tab asks the admin, and saves again with newKey if they make a new key
					throw new SecretKeyMissingException(e.getMessage(), e.sealed());
				} finally {
					// The next sign-in fetches the discovery document again, for the provider as it now is
					ProviderClient.get().forget(id);
				}
			}
			return toGUI(providers.find(id).orElseThrow(), redirectBase().base());
		});
	}

	@Override
	public void removeProvider(String id) throws ServerException {
		admin(SIGN_IN);
		call("remove the sign-in provider", () -> {
			try {
				SignInProviders.get().remove(id);
			} finally {
				ProviderClient.get().forget(id);
			}
			return null;
		});
	}

	@Override
	public void reorderProviders(String[] ids) throws ServerException {
		admin(SIGN_IN);
		call("reorder the sign-in providers", () -> {
			SignInProviders.get().reorder(ids == null ? null : Arrays.asList(ids));
			return null;
		});
	}

	@Override
	public String[] checkProvider(String id) throws ServerException {
		admin(SIGN_IN);
		return call("check the sign-in provider", () -> {
			SignInProvider provider = SignInProviders.get().find(id)
					.orElseThrow(() -> new HealdanRuleException("That provider doesn't exist."));
			RedirectBase base = redirectBase();
			URI redirect = SignInProviders.callback(base.base(), provider.id());
			Check check = ProviderClient.get().check(provider, redirect, base.usable());

			// The address first, always (ruling R5), then what's wrong
			List<String> lines = new ArrayList<>();
			lines.add(redirect.toString());
			if (!base.usable())
				lines.add(NO_PUBLIC_ADDRESS);
			lines.addAll(check.problems());
			return lines.toArray(String[]::new);
		});
	}

	@Override
	public void saveSignInOptions(String passwordLabel, boolean adminAlert, String[] recipients)
			throws ServerException {
		admin(SIGN_IN);
		call("save the sign-in options", () -> {
			SignInProviders.get().saveOptions(passwordLabel, adminAlert,
					recipients == null ? null : Arrays.asList(recipients));
			return null;
		});
	}

	@Override
	public GUILinkedAccount[] linkedAccounts(String id) throws ServerException {
		admin(SIGN_IN);
		return call("list the linked accounts",
				() -> SignInAdmin.get().linked(id).stream().map(SignInBeans::toGUI).toArray(GUILinkedAccount[]::new));
	}

	@Override
	public String unlink(long userId) throws ServerException {
		// SignInAdmin doesn't check who unlinks, so this does
		User by = admin(SIGN_IN);
		return call("unlink the account", () -> SignInAdmin.get().unlink(userId, by));
	}

	@Override
	public String[] unlinkEveryone(String id) throws ServerException {
		User by = admin(SIGN_IN);
		return call("unlink the accounts", () -> SignInAdmin.get().unlinkEveryone(id, by).passwords().entrySet()
				.stream().map(entry -> entry.getKey() + ": " + entry.getValue()).toArray(String[]::new));
	}

	@Override
	public int countLinked(String id) throws ServerException {
		admin(SIGN_IN);
		return call("count the linked accounts", () -> id == null ? 0 : SignInIdentityDAO.get().countLinked(id));
	}

	private GUISignInProvider toGUI(SignInProvider provider, String base) throws PersistenceException {
		return SignInBeans.toGUI(provider, redirect(base, provider.id()),
				SignInBeans.groups(SignInProviders.get().groups(provider.id())),
				SignInIdentityDAO.get().countLinked(provider.id()));
	}

	private static String redirect(String base, String id) {
		return SignInProviders.callback(base, id).toString();
	}

	/**
	 * @param base what the redirect addresses start with, with no slash at the
	 *        end
	 * @param usable true if it's the public address the sign-in pages use; false
	 *        if Healdan has none it can use, and it's only the address this
	 *        request came to
	 */
	private record RedirectBase(String base, boolean usable) {
	}

	/**
	 * The start of the redirect addresses, as the sign-in pages work them out
	 * (ruling R2). With no address Healdan can use, the sign-in pages refuse to
	 * start, and the tab shows the address this request came to as the best
	 * guess, with {@link #NO_PUBLIC_ADDRESS} (ruling R5).
	 */
	private RedirectBase redirectBase() {
		HttpServletRequest request = request();
		Optional<Address> address = PublicAddress.of(request);
		if (address.isPresent())
			return new RedirectBase(address.get().base(), true);
		return new RedirectBase(StringUtils.removeEnd(PublicAddress.requested(request), "/"), false);
	}

	private static GUIHealdanSettings current() throws PersistenceException {
		HealdanSettings settings = HealdanSettings.get();
		GUIHealdanSettings gui = new GUIHealdanSettings();
		gui.setMatterLabel(settings.matterLabel());
		gui.setMatterLabelPlural(settings.matterLabelPlural());
		gui.setNumberingMode(settings.numberingMode().name());
		gui.setDefaultClass(settings.defaultClass());
		gui.setClasses(new ArrayList<>(ProfileSchema.get().classOptions()));
		gui.setRecentMattersDays(settings.recentMattersDays());
		gui.setNextDocNumber(DocNumberCounter.get().peekNext());
		gui.setApiTokenIdleDays(settings.apiTokenIdleDays());
		gui.setApiTokenMaxDays(settings.apiTokenMaxDays());
		gui.setApiCorsOrigins(StringUtils.defaultString(settings.get(HealdanSettings.API_CORS_ORIGINS)));
		return gui;
	}

	private static GUIClient toGUI(Client client) {
		GUIClient gui = new GUIClient();
		gui.setId(client.getId());
		gui.setNumber(client.getNumber());
		gui.setName(client.getName());
		return gui;
	}

	/** The settings to save, each one checked */
	private record Values(String label, String plural, NumberingMode mode, String docClass, int recentDays,
			Long nextDocNumber, int apiIdleDays, int apiMaxDays, String apiOrigins) {
	}

	/*
	 * Checks every value before anything is written, so a refused save changes
	 * nothing
	 */
	private static Values check(GUIHealdanSettings settings) throws PersistenceException {
		String label = label(settings.getMatterLabel(), "singular");
		String plural = label(settings.getMatterLabelPlural(), "plural");

		NumberingMode mode = mode(settings.getNumberingMode());
		MatterNumbering.get().checkMode(mode);

		String docClass = StringUtils.strip(settings.getDefaultClass());
		if (StringUtils.isEmpty(docClass))
			throw new HealdanRuleException("The default Class cannot be empty.");
		List<String> classes = ProfileSchema.get().classOptions();
		if (!classes.isEmpty() && !classes.contains(docClass))
			throw new HealdanRuleException("The default Class must be one of the Class options.");

		int days = settings.getRecentMattersDays();
		if (days < 1 || days > MAX_RECENT_DAYS)
			throw new HealdanRuleException(
					"The Recent %s window must be between 1 and %d days.".formatted(plural, MAX_RECENT_DAYS));

		Long next = settings.getNextDocNumber();
		long currentNext = DocNumberCounter.get().peekNext();
		if (next != null && next < currentNext)
			throw new HealdanRuleException(
					"The next doc # can only be raised. The next document gets #%d.".formatted(currentNext));
		if (next != null && next > MAX_NEXT_DOC_NUMBER)
			throw new HealdanRuleException("The next doc # can't be higher than %d.".formatted(MAX_NEXT_DOC_NUMBER));

		int idleDays = settings.getApiTokenIdleDays();
		if (idleDays < 1 || idleDays > MAX_API_DAYS)
			throw new HealdanRuleException(
					"A device sign-in must expire after between 1 and %d unused days.".formatted(MAX_API_DAYS));
		int maxDays = settings.getApiTokenMaxDays();
		if (maxDays < 1 || maxDays > MAX_API_DAYS)
			throw new HealdanRuleException(
					"A device sign-in must last between 1 and %d days.".formatted(MAX_API_DAYS));
		if (maxDays < idleDays)
			throw new HealdanRuleException("A device sign-in can't last fewer days than it may go unused.");
		String origins = origins(settings.getApiCorsOrigins());

		return new Values(label, plural, mode, docClass, days, next, idleDays, maxDays, origins);
	}

	private static String label(String value, String which) throws HealdanRuleException {
		String label = StringUtils.strip(value);
		if (StringUtils.isEmpty(label))
			throw new HealdanRuleException("The %s label cannot be empty.".formatted(which));
		if (label.length() > MAX_LABEL_LENGTH)
			throw new HealdanRuleException(
					"The %s label cannot be longer than %d characters.".formatted(which, MAX_LABEL_LENGTH));
		// MariaDB's utf8 tables hold no character beyond the Basic Multilingual Plane (R33)
		if (!BasicPlane.holds(label))
			throw new HealdanRuleException(
					"The %s label cannot contain emoji or other characters the database can't store.".formatted(which));
		return label;
	}

	/* The origins as entered, trimmed and joined by ", ". Each one is checked. */
	private static String origins(String value) throws HealdanRuleException {
		List<String> origins = new ArrayList<>();
		for (String entry : StringUtils.split(StringUtils.defaultString(value), ',')) {
			String origin = StringUtils.strip(entry);
			if (origin.isEmpty())
				continue;
			if (!ORIGIN.matcher(origin).matches())
				throw new HealdanRuleException(
						"'%s' isn't an origin. Write it as https://host or https://host:port, with no path."
								.formatted(origin));
			origins.add(origin);
		}
		return String.join(", ", origins);
	}

	private static NumberingMode mode(String value) throws HealdanRuleException {
		if (StringUtils.isBlank(value))
			throw new HealdanRuleException("Choose a numbering mode.");
		try {
			return NumberingMode.valueOf(value.strip());
		} catch (IllegalArgumentException e) {
			throw new HealdanRuleException("Unknown numbering mode: " + value);
		}
	}

	private static void write(Values values) throws PersistenceException {
		HealdanSettings settings = HealdanSettings.get();
		settings.set(HealdanSettings.MATTER_LABEL, values.label());
		settings.set(HealdanSettings.MATTER_LABEL_PLURAL, values.plural());
		renameMatterAttribute(values.label());
		MatterNumbering.get().setMode(values.mode());
		settings.set(HealdanSettings.CLASS_DEFAULT, values.docClass());
		settings.set(HealdanSettings.RECENT_MATTERS_DAYS, Integer.toString(values.recentDays()));
		settings.set(HealdanSettings.API_TOKEN_IDLE_DAYS, Integer.toString(values.apiIdleDays()));
		settings.set(HealdanSettings.API_TOKEN_MAX_DAYS, Integer.toString(values.apiMaxDays()));
		// A lower maximum applies to the device sign-ins already made too
		ApiTokens.get().capLifetime(values.apiMaxDays());
		settings.set(HealdanSettings.API_CORS_ORIGINS, values.apiOrigins());
		if (values.nextDocNumber() != null && values.nextDocNumber() > DocNumberCounter.get().peekNext())
			DocNumberCounter.get().raiseNext(values.nextDocNumber());
	}

	/** The profile's matter attribute is labelled with the singular label */
	private static void renameMatterAttribute(String label) throws PersistenceException {
		Template template = ProfileSchema.get().template();
		Attribute matter = template.getAttribute(HealdanConstants.ATTR_MATTER);
		if (matter != null && !label.equals(matter.getLabel())) {
			matter.setLabel(label);
			TemplateDAO.get().store(template);
		}
	}
}
