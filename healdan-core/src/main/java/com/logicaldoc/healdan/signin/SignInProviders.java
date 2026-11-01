package com.logicaldoc.healdan.signin;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.util.BasicPlane;
import com.logicaldoc.util.spring.Context;

/**
 * The sign-in providers an admin set up, and the options that go with them
 * (M4, spec section 10). They are kept in {@code hd_setting} under
 * {@code signin.<id>.<name>}, with {@code signin.providers} listing the ids in
 * the order of their buttons, and the group maps in {@code hd_signin_group}.
 * <p>
 * Nothing is cached, so a change takes effect at once, with no restart. The
 * client secret is sealed by {@link SecretBox} and never leaves it except
 * through {@link #secret}, which the code that calls the provider uses; it is
 * never logged.
 */
@Component
public class SignInProviders {

	/** What a provider's id looks like: it appears in the sign-in address, so it can't change */
	public static final Pattern ID = Pattern.compile("[a-z0-9-]{1,32}");

	/** The environment variable that lets providers be reached over plain HTTP, which only the smoke overlay sets */
	static final String ALLOW_HTTP_VARIABLE = "HEALDAN_SIGNIN_ALLOW_HTTP";

	/** Reads the variable, as production does */
	static final Supplier<String> ENVIRONMENT = () -> System.getenv(ALLOW_HTTP_VARIABLE);

	/** Where {@link #httpAllowed()} reads the variable; {@code SignInTestHooks} replaces it in tests */
	static volatile Supplier<String> allowHttpSetting = ENVIRONMENT;

	static final String PROVIDERS = "signin.providers";

	static final String PASSWORD_LABEL = "signin.password.label";

	static final String ADMIN_ALERT = "signin.adminAlert";

	static final String ADMIN_ALERT_RECIPIENTS = "signin.adminAlert.recipients";

	static final String RECHECK_MINUTES = "signin.recheck.minutes";

	public static final String DEFAULT_PASSWORD_LABEL = "Sign In with a Password";

	public static final int DEFAULT_RECHECK_MINUTES = 480;

	private static final String DEFAULT_RECIPIENT = "admin";

	private static final String LABEL = "label";

	private static final String PRESET = "preset";

	private static final String ENABLED = "enabled";

	private static final String ISSUER = "issuer";

	private static final String CLIENT_ID = "clientId";

	private static final String CLIENT_SECRET = "clientSecret";

	private static final String IDENTITY_CLAIM = "claim.identity";

	private static final String USERNAME_CLAIM = "claim.username";

	private static final String EMAIL_CLAIM = "claim.email";

	private static final String GROUPS_CLAIM = "claim.groups";

	private static final String LINK_BY_EMAIL = "linkByEmail";

	/** Ids that name a page of the sign-in address, where a provider's id would be */
	private static final Set<String> RESERVED_IDS = Set.of("done");

	private static final int MAX_LABEL = 60;

	private static final int MAX_CLAIM = 100;

	private static final int MAX_CLIENT_ID = 255;

	private static final int MAX_ISSUER = 1000;

	private static final int MAX_SECRET = 1000;

	/** The width of {@code hd_signin_group.hd_value} */
	private static final int MAX_GROUP_VALUE = 255;

	private static final Logger log = LoggerFactory.getLogger(SignInProviders.class);

	private final HealdanSettings settings;

	private final SecretBox box;

	private final SignInIdentityDAO identities;

	private final SignInGroupDAO groupMaps;

	@Autowired
	public SignInProviders(HealdanSettings settings, SecretBox box, SignInIdentityDAO identities,
			SignInGroupDAO groupMaps) {
		this.settings = settings;
		this.box = box;
		this.identities = identities;
		this.groupMaps = groupMaps;
	}

	/**
	 * @return the bean in the application context
	 */
	public static SignInProviders get() {
		return Context.get(SignInProviders.class);
	}

	/**
	 * @return true if the environment variable {@code HEALDAN_SIGNIN_ALLOW_HTTP}
	 *         is {@code true}, which lets providers be reached over plain HTTP.
	 *         Only the smoke overlay sets it; production never does.
	 */
	public static boolean httpAllowed() {
		return "true".equalsIgnoreCase(StringUtils.strip(allowHttpSetting.get()));
	}

	/**
	 * @return every provider, in the order of their buttons
	 */
	public List<SignInProvider> all() {
		return ids().stream().map(this::read).toList();
	}

	/**
	 * @return the providers people can sign in through: those that are on and
	 *         have an issuer, a client id and a secret that can be opened. A
	 *         lost key turns a provider off rather than failing (spec section
	 *         13).
	 */
	public List<SignInProvider> enabled() {
		return all().stream().filter(p -> p.enabled() && p.issuer() != null && p.clientId() != null && p.hasSecret())
				.toList();
	}

	/**
	 * @param id a provider's id, possibly null
	 * @return the provider, or empty if there is none with that id
	 */
	public Optional<SignInProvider> find(String id) {
		return ids().contains(id) ? Optional.of(read(id)) : Optional.empty();
	}

	/**
	 * @param id a provider's id, possibly null
	 * @return the provider's client secret, opened; null if there is none or it
	 *         can't be opened, such as after the key was lost
	 */
	public String secret(String id) {
		return id == null ? null : box.open(settings.get(key(id, CLIENT_SECRET)));
	}

	/**
	 * Adds a provider, if no provider has its id, or else changes it. Every
	 * value is checked before any is written, so a refusal changes nothing. A
	 * new provider starts with no group map, whatever rows are under its id.
	 *
	 * @param p the provider; {@code hasSecret} is ignored
	 * @param newSecret the client secret to keep, sealed; null or blank keeps the
	 *        one stored, which a new provider doesn't have
	 * @throws PersistenceException error at data layer
	 * @throws HealdanRuleException if a value isn't acceptable; the message
	 *         names the field, for the person who typed it
	 */
	public synchronized void save(SignInProvider p, String newSecret) throws PersistenceException {
		String id = p == null ? null : p.id();
		if (id == null || !ID.matcher(id).matches())
			throw refuse("A provider's id can have only lower-case letters, digits and hyphens, and at most "
					+ "32 characters.");
		List<String> ids = ids();
		boolean adding = !ids.contains(id);
		// An id is also where its settings are, and the address of the pages around the providers'
		if (adding && (RESERVED_IDS.contains(id) || !settings.names(prefix(id)).isEmpty()))
			throw refuse("The id '%s' is already used. Choose another.".formatted(id));

		String label = required(p.label(), "label", MAX_LABEL);
		String issuer = checkIssuer(p.issuer());
		String clientId = required(p.clientId(), "client id", MAX_CLIENT_ID);
		String identityClaim = required(p.identityClaim(), "identity claim", MAX_CLAIM);
		String usernameClaim = required(p.usernameClaim(), "username claim", MAX_CLAIM);
		String emailClaim = required(p.emailClaim(), "email claim", MAX_CLAIM);
		String groupsClaim = required(p.groupsClaim(), "groups claim", MAX_CLAIM);
		Preset preset = p.preset() == null ? Preset.OTHER : p.preset();
		String sealed = sealedSecret(id, newSecret);

		// A new provider starts with no group map. Rows already under its id belong to nobody (the provider
		// they were saved for is gone), and the map is the gate and the grant, so they must not be adopted.
		if (adding) {
			int stale = groupMaps.findByProvider(id).size();
			if (stale > 0) {
				groupMaps.replace(id, List.of());
				log.warn("Dropped {} group map rows left under the id of the new sign-in provider {}", stale, id);
			}
		}

		settings.set(key(id, LABEL), label);
		settings.set(key(id, PRESET), preset.key());
		settings.set(key(id, ENABLED), Boolean.toString(p.enabled()));
		settings.set(key(id, ISSUER), issuer);
		settings.set(key(id, CLIENT_ID), clientId);
		if (sealed != null)
			settings.set(key(id, CLIENT_SECRET), sealed);
		settings.set(key(id, IDENTITY_CLAIM), identityClaim);
		settings.set(key(id, USERNAME_CLAIM), usernameClaim);
		settings.set(key(id, EMAIL_CLAIM), emailClaim);
		settings.set(key(id, GROUPS_CLAIM), groupsClaim);
		settings.set(key(id, LINK_BY_EMAIL), Boolean.toString(p.linkByEmail()));
		// Last, so that a provider is listed only once all of it is there
		if (adding) {
			List<String> listed = new ArrayList<>(ids);
			listed.add(id);
			settings.set(PROVIDERS, String.join(",", listed));
		}
		log.info("Sign-in provider {} {}", id, adding ? "added" : "saved");
	}

	/**
	 * Removes a provider that has no linked accounts: every setting of it and its
	 * group map go, and its id leaves the list. The rows that record which
	 * accounts it once linked stay, as history.
	 *
	 * @param id the provider's id
	 * @throws PersistenceException error at data layer
	 * @throws HealdanRuleException if there is no such provider, or accounts are
	 *         still linked to it
	 */
	public synchronized void remove(String id) throws PersistenceException {
		List<String> ids = ids();
		if (id == null || !ids.contains(id))
			throw refuse("That provider doesn't exist.");
		int linked = identities.countLinked(id);
		if (linked > 0)
			throw refuse("%s still has %s linked. Unlink its accounts first.".formatted(read(id).label(),
					linked == 1 ? "1 account" : linked + " accounts"));

		groupMaps.replace(id, List.of());
		for (String name : settings.names(prefix(id)))
			settings.delete(name);
		// Last, so that a failure leaves the provider listed, to be removed again
		List<String> left = new ArrayList<>(ids);
		left.remove(id);
		settings.set(PROVIDERS, String.join(",", left));
		log.info("Sign-in provider {} removed", id);
	}

	/**
	 * Puts the providers' buttons in a new order. A provider the list leaves out
	 * goes after the others, in its old order, so a stale list can't drop one.
	 *
	 * @param ids the providers' ids in the new order
	 * @throws HealdanRuleException if the list names a provider that doesn't
	 *         exist, or one twice
	 */
	public synchronized void reorder(List<String> ids) throws HealdanRuleException {
		if (ids == null)
			throw refuse("The list of providers is missing.");
		List<String> current = ids();
		Set<String> ordered = new LinkedHashSet<>();
		for (String id : ids) {
			if (!current.contains(id))
				throw refuse("The list names a provider that doesn't exist.");
			if (!ordered.add(id))
				throw refuse("The list names a provider twice.");
		}
		ordered.addAll(current);
		settings.set(PROVIDERS, String.join(",", ordered));
	}

	/**
	 * @param id a provider's id
	 * @return its group map, by provider group and then Healdan group. It holds
	 *         the rows of groups that were deleted too, for the tab to show.
	 * @throws PersistenceException error at data layer
	 */
	public List<GroupMapping> groups(String id) throws PersistenceException {
		if (id == null)
			return List.of();
		return groupMaps.findByProvider(id).stream().map(row -> new GroupMapping(row.getValue(), row.getGroupId()))
				.toList();
	}

	/**
	 * Replaces a provider's group map. The values are stripped and a repeated
	 * row is kept once. If any row is refused, the map stays as it was.
	 *
	 * @param id the provider's id
	 * @param mappings the new map
	 * @throws PersistenceException error at data layer
	 * @throws HealdanRuleException if there is no such provider, a value is
	 *         blank, too long or can't be stored, or a group doesn't exist or is
	 *         a person's own group
	 */
	public synchronized void saveGroups(String id, List<GroupMapping> mappings) throws PersistenceException {
		// Rows for an id nobody has would be adopted by the provider added under it later; the lock keeps
		// this from running between the steps of remove
		if (id == null || !ids().contains(id))
			throw refuse("That provider doesn't exist.");
		if (mappings == null)
			throw refuse("The group map is missing.");

		Set<GroupMapping> clean = new LinkedHashSet<>();
		Set<Long> found = new LinkedHashSet<>();
		for (GroupMapping mapping : mappings) {
			String value = required(mapping.value(), "provider group's value", MAX_GROUP_VALUE);
			if (!found.contains(mapping.groupId())) {
				Group group = GroupDAO.get().findById(mapping.groupId());
				if (group == null)
					throw refuse("There is no Healdan group with id %d.".formatted(mapping.groupId()));
				// The groups Security > Groups lists; a person's own group is hidden there and isn't one to map
				if (group.getType() == Group.Type.USER)
					throw refuse("The group with id %d is a person's own group, which can't be mapped."
							.formatted(mapping.groupId()));
				found.add(mapping.groupId());
			}
			clean.add(new GroupMapping(value, mapping.groupId()));
		}

		List<SignInGroup> rows = new ArrayList<>();
		for (GroupMapping mapping : clean) {
			SignInGroup row = new SignInGroup();
			row.setValue(mapping.value());
			row.setGroupId(mapping.groupId());
			rows.add(row);
		}
		groupMaps.replace(id, rows);
	}

	/**
	 * @return the label of the link to sign in with a password
	 */
	public String passwordLabel() {
		return StringUtils.defaultIfBlank(StringUtils.strip(settings.get(PASSWORD_LABEL)), DEFAULT_PASSWORD_LABEL);
	}

	/**
	 * @return true if the recipients are emailed when {@code admin} signs in with
	 *         its password
	 */
	public boolean adminAlert() {
		return "true".equalsIgnoreCase(StringUtils.strip(settings.get(ADMIN_ALERT)));
	}

	/**
	 * @return the usernames to email, {@code admin} if the setting is missing, and
	 *         nobody if an admin emptied it
	 */
	public List<String> adminAlertRecipients() {
		String value = settings.get(ADMIN_ALERT_RECIPIENTS);
		if (value == null)
			return List.of(DEFAULT_RECIPIENT);
		return Arrays.stream(value.split(",")).map(String::strip).filter(name -> !name.isEmpty()).distinct().toList();
	}

	/**
	 * @return how many minutes pass between a device token's checks with its
	 *         provider, 480 by default and when the setting isn't a number
	 *         from 0
	 */
	public int recheckMinutes() {
		try {
			int minutes = Integer.parseInt(StringUtils.strip(settings.get(RECHECK_MINUTES)));
			return minutes >= 0 ? minutes : DEFAULT_RECHECK_MINUTES;
		} catch (NumberFormatException e) {
			return DEFAULT_RECHECK_MINUTES;
		}
	}

	/**
	 * Saves the options that aren't a provider's. Every value is checked before
	 * any is written.
	 *
	 * @param passwordLabel the label of the link to sign in with a password
	 * @param adminAlert whether to email the recipients when {@code admin} signs
	 *        in with its password
	 * @param recipients the usernames to email; blank names are left out
	 * @throws PersistenceException error at data layer
	 * @throws HealdanRuleException if the label is blank, too long or can't be
	 *         stored, a recipient isn't a user, or the alert is on with no
	 *         recipient
	 */
	public void saveOptions(String passwordLabel, boolean adminAlert, List<String> recipients)
			throws PersistenceException {
		String label = required(passwordLabel, "password link's label", MAX_LABEL);
		List<String> names = new ArrayList<>();
		for (String entry : recipients == null ? List.<String>of() : recipients) {
			String name = StringUtils.strip(entry);
			if (StringUtils.isEmpty(name))
				continue;
			User user = UserDAO.get().getUser(name);
			if (user == null)
				throw refuse("'%s' isn't a user, so it can't get the alert.".formatted(name));
			if (!names.contains(user.getUsername()))
				names.add(user.getUsername());
		}
		if (adminAlert && names.isEmpty())
			throw refuse("Name at least one recipient for the alert.");

		settings.set(PASSWORD_LABEL, label);
		settings.set(ADMIN_ALERT, Boolean.toString(adminAlert));
		settings.set(ADMIN_ALERT_RECIPIENTS, String.join(",", names));
	}

	private static String key(String id, String name) {
		return prefix(id) + name;
	}

	private static String prefix(String id) {
		return "signin." + id + ".";
	}

	private static HealdanRuleException refuse(String message) {
		return new HealdanRuleException(message);
	}

	/** The ids in {@code signin.providers}, in order, leaving out any that aren't ids */
	private List<String> ids() {
		Set<String> ids = new LinkedHashSet<>();
		String list = settings.get(PROVIDERS);
		if (list != null)
			for (String entry : list.split(",")) {
				String id = entry.strip();
				if (ID.matcher(id).matches())
					ids.add(id);
			}
		return new ArrayList<>(ids);
	}

	private SignInProvider read(String id) {
		Preset preset = Preset.of(settings.get(key(id, PRESET)));
		return new SignInProvider(id, preset, setting(id, LABEL, preset.label()), flag(id, ENABLED, true),
				optional(id, ISSUER), optional(id, CLIENT_ID), secret(id) != null,
				setting(id, IDENTITY_CLAIM, preset.identityClaim()), setting(id, USERNAME_CLAIM, preset.usernameClaim()),
				setting(id, EMAIL_CLAIM, preset.emailClaim()), setting(id, GROUPS_CLAIM, preset.groupsClaim()),
				flag(id, LINK_BY_EMAIL, preset.linkByEmail()));
	}

	private String optional(String id, String name) {
		return StringUtils.trimToNull(settings.get(key(id, name)));
	}

	private String setting(String id, String name, String defaultValue) {
		return StringUtils.defaultIfBlank(StringUtils.strip(settings.get(key(id, name))), defaultValue);
	}

	private boolean flag(String id, String name, boolean defaultValue) {
		String value = StringUtils.strip(settings.get(key(id, name)));
		return StringUtils.isEmpty(value) ? defaultValue : "true".equalsIgnoreCase(value);
	}

	/**
	 * @param value what was typed
	 * @param field the field's name in plain words, for the refusal
	 * @param max the most characters it may have
	 * @return the value, stripped
	 * @throws HealdanRuleException if it's blank, too long, or has a character the
	 *         database can't store
	 */
	private static String required(String value, String field, int max) throws HealdanRuleException {
		String text = StringUtils.strip(value);
		if (StringUtils.isEmpty(text))
			throw refuse("The %s can't be empty.".formatted(field));
		if (text.length() > max)
			throw refuse("The %s can't be longer than %d characters.".formatted(field, max));
		// MariaDB's utf8 tables hold no character beyond the Basic Multilingual Plane
		if (!BasicPlane.holds(text))
			throw refuse("The %s can't contain emoji or other characters the database can't store.".formatted(field));
		return text;
	}

	/**
	 * @return the issuer address, stripped but otherwise as typed: it is compared
	 *         exactly with the {@code iss} of the ID tokens, so a trailing slash
	 *         matters
	 */
	private static String checkIssuer(String value) throws HealdanRuleException {
		String issuer = StringUtils.strip(value);
		if (StringUtils.isEmpty(issuer))
			throw refuse("The issuer address can't be empty.");
		if (issuer.length() > MAX_ISSUER)
			throw refuse("The issuer address can't be longer than %d characters.".formatted(MAX_ISSUER));
		URI uri;
		try {
			uri = new URI(issuer);
		} catch (URISyntaxException e) {
			throw refuse("The issuer address isn't a valid address.");
		}
		if (!uri.isAbsolute() || StringUtils.isEmpty(uri.getHost()))
			throw refuse("The issuer address must be a full address, such as https://login.example/realm.");
		if (uri.getRawQuery() != null || uri.getRawFragment() != null)
			throw refuse("The issuer address can't have a query or a fragment.");
		if (uri.getRawUserInfo() != null)
			throw refuse("The issuer address can't have a user name or password in it.");
		boolean https = "https".equalsIgnoreCase(uri.getScheme());
		boolean http = "http".equalsIgnoreCase(uri.getScheme());
		if (!https && !(http && httpAllowed()))
			throw refuse("The issuer address must start with https://.");
		return issuer;
	}

	/**
	 * @return the new secret sealed; null to keep the one stored
	 * @throws HealdanRuleException if there is neither
	 */
	private String sealedSecret(String id, String newSecret) throws HealdanRuleException {
		if (StringUtils.isBlank(newSecret)) {
			if (StringUtils.isBlank(settings.get(key(id, CLIENT_SECRET))))
				throw refuse("The client secret can't be empty.");
			return null;
		}
		String secret = newSecret.strip();
		if (secret.length() > MAX_SECRET)
			throw refuse("The client secret can't be longer than %d characters.".formatted(MAX_SECRET));
		try {
			return box.seal(secret);
		} catch (IllegalStateException e) {
			// The message holds no secret, and neither does this one
			throw new HealdanRuleException("The client secret couldn't be kept, because Healdan couldn't read or "
					+ "make its secret key file. Ask whoever runs the server to check the configuration folder.", e);
		}
	}
}
