// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.people;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import org.apache.commons.lang3.StringUtils;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.TenantDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.admin.FieldRefusal;
import com.logicaldoc.healdan.util.StorableText;
import com.logicaldoc.i18n.I18N;
import com.logicaldoc.util.LocaleUtil;
import com.logicaldoc.util.html.HTMLSanitizer;
import com.logicaldoc.util.spring.Context;

/**
 * A person's details as New User or Edit gives them ({@link UserDraft}), checked: each text without the whitespace at
 * either end, and every field Healdan doesn't take refused at once, in the form's order ({@link FieldRefusal}). An
 * edit takes the person's current names, language and time zone as they are, as the app sends back what it was
 * given: a provider account's blank name, or a language turned off since, saves.
 *
 * @param username the username, null for an edit
 * @param firstName the first name; on an edit, the stored one when it's unchanged, a blank one among them
 * @param lastName the last name; on an edit, the stored one when it's unchanged, a blank one among them
 * @param email the email address, null for none
 * @param language the language, never null
 * @param timeZone the time zone, null for none
 */
record UserForm(String username, String firstName, String lastName, String email, String language,
		String timeZone) {

	static final String NO_USERNAME = "Give the person a username.";

	static final String LONG_USERNAME = "A username can't be longer than 255 characters.";

	static final String BAD_USERNAME = "A username can't contain '>', control characters or "
			+ StorableText.CANT_KEEP + ".";

	static final String USERNAME_FIXED = "A username never changes.";

	static final String NO_FIRST_NAME = "Give the person a first name.";

	static final String NO_LAST_NAME = "Give the person a last name.";

	static final String LONG_NAME = "A name can't be longer than 255 characters.";

	static final String BAD_NAME = "A name can't contain control characters, markup or " + StorableText.CANT_KEEP
			+ ".";

	static final String LONG_EMAIL = "An email address can't be longer than 255 characters.";

	static final String BAD_EMAIL = "That isn't an email address.";

	static final String BAD_LANGUAGE = "Choose one of the languages offered.";

	static final String BAD_TIME_ZONE = "Choose one of the time zones offered.";

	/** The longest username, name or email address the account's columns hold */
	static final int MAX_TEXT = 255;

	/** The language a person gets when none is chosen, when it's offered */
	static final String DEFAULT_LANGUAGE = "en";

	/**
	 * @param draft the details given
	 * @param tenantId the person's tenant
	 * @param current the person an edit is of; null for a new account, which gives its username
	 * @param taken whether a username another account has, in any case, is the one given
	 * @return the details
	 * @throws FieldRefusal for the fields Healdan doesn't take, in the form's order
	 */
	static UserForm check(UserDraft draft, long tenantId, User current, Predicate<String> taken)
			throws FieldRefusal {
		List<FieldRefusal.Field> fields = new ArrayList<>();
		String username = null;
		if (current == null) {
			username = StringUtils.strip(draft.username());
			if (StringUtils.isEmpty(username))
				fields.add(new FieldRefusal.Field("username", FieldRefusal.REQUIRED, NO_USERNAME));
			else if (username.length() > MAX_TEXT)
				fields.add(new FieldRefusal.Field("username", FieldRefusal.TOO_LONG, LONG_USERNAME));
			else if (username.contains(">") || !StorableText.holds(username)
					|| username.chars().anyMatch(Character::isISOControl))
				fields.add(new FieldRefusal.Field("username", FieldRefusal.INVALID, BAD_USERNAME));
			else if (taken.test(username))
				fields.add(new FieldRefusal.Field("username", FieldRefusal.TAKEN, UserAdministration.TAKEN));
		} else if (draft.username() != null) {
			fields.add(new FieldRefusal.Field("username", FieldRefusal.NOT_EDITABLE, USERNAME_FIXED));
		}
		String firstName = name(draft.firstName(), current != null ? current.getFirstName() : null, current != null,
				"firstName", NO_FIRST_NAME, fields);
		String lastName = name(draft.lastName(), current != null ? current.getName() : null, current != null,
				"lastName", NO_LAST_NAME, fields);

		String email = StringUtils.strip(draft.email());
		if (StringUtils.isEmpty(email))
			email = null;
		else if (email.length() > MAX_TEXT)
			fields.add(new FieldRefusal.Field("email", FieldRefusal.TOO_LONG, LONG_EMAIL));
		else if (!email.contains("@") || email.startsWith("@") || email.endsWith("@") || !StorableText.holds(email)
				|| email.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)))
			fields.add(new FieldRefusal.Field("email", FieldRefusal.INVALID, BAD_EMAIL));

		String language = StringUtils.strip(draft.language());
		if (StringUtils.isEmpty(language))
			language = defaultLanguage(tenantId);
		else if (!(current != null && language.equals(current.getLanguage()))
				&& languages(tenantId).stream().noneMatch(offered -> offered.code().equals(draft.language().strip())))
			fields.add(new FieldRefusal.Field("language", FieldRefusal.INVALID, BAD_LANGUAGE));

		String timeZone = StringUtils.strip(draft.timeZone());
		if (StringUtils.isEmpty(timeZone))
			timeZone = null;
		else if (!(current != null && timeZone.equals(current.getTimeZone())) && !timeZone(timeZone))
			fields.add(new FieldRefusal.Field("timeZone", FieldRefusal.INVALID, BAD_TIME_ZONE));

		if (!fields.isEmpty())
			throw new FieldRefusal(fields);
		return new UserForm(username, firstName, lastName, email, language, timeZone);
	}

	/**
	 * A first or last name, which every person has, as LogicalDOC would store it. On an edit, the stored name, the
	 * whitespace at either end aside, is taken as it is, a blank one included, as a provider account's can be: a
	 * blank name stays blank until one is typed.
	 *
	 * @param stored the stored name, on an edit
	 * @param editing whether it's an edit
	 * @return the name; on an edit that leaves it as it is, the stored one
	 */
	private static String name(String given, String stored, boolean editing, String field, String missing,
			List<FieldRefusal.Field> fields) {
		String name = StringUtils.strip(given);
		if (editing && StringUtils.defaultString(name).equals(StringUtils.strip(StringUtils.defaultString(stored))))
			return StringUtils.defaultString(stored);
		if (StringUtils.isEmpty(name))
			fields.add(new FieldRefusal.Field(field, FieldRefusal.REQUIRED, missing));
		else if (name.length() > MAX_TEXT)
			fields.add(new FieldRefusal.Field(field, FieldRefusal.TOO_LONG, LONG_NAME));
		else if (!StorableText.holds(name) || name.chars().anyMatch(Character::isISOControl)
				|| !name.equals(HTMLSanitizer.sanitizeSimpleText(name)))
			fields.add(new FieldRefusal.Field(field, FieldRefusal.INVALID, BAD_NAME));
		return name;
	}

	/**
	 * @return the languages a person's screens can be in, as the tenant turns them on
	 *         ({@code <tenant>.lang.<code>.gui}), by name; English when it turns none on
	 */
	static List<UserOptions.Language> languages(long tenantId) {
		String tenant;
		try {
			tenant = TenantDAO.get().getTenantName(tenantId);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the tenant's name", e);
		}
		List<UserOptions.Language> languages = new ArrayList<>();
		for (String code : I18N.getLocales())
			if ("enabled".equals(Context.get().getConfig().getProperty(tenant + ".lang." + code + ".gui")))
				languages.add(language(code));
		if (languages.isEmpty())
			languages.add(language(DEFAULT_LANGUAGE));
		languages.sort(Comparator.comparing((UserOptions.Language language) -> language.name().toLowerCase(Locale.ROOT))
				.thenComparing(UserOptions.Language::code));
		return languages;
	}

	private static UserOptions.Language language(String code) {
		return new UserOptions.Language(code,
				StringUtils.capitalize(LocaleUtil.toLocale(code).getDisplayName(Locale.ENGLISH)));
	}

	/**
	 * @return the language a person gets when none is chosen: English when it's offered, else the first offered
	 */
	static String defaultLanguage(long tenantId) {
		List<UserOptions.Language> offered = languages(tenantId);
		return offered.stream().anyMatch(language -> language.code().equals(DEFAULT_LANGUAGE)) ? DEFAULT_LANGUAGE
				: offered.get(0).code();
	}

	/**
	 * @return the time zones a person can have: the region ids the JVM knows, such as {@code Europe/London}, and
	 *         {@code UTC}, sorted
	 */
	static List<String> timeZones() {
		return ZoneId.getAvailableZoneIds().stream().filter(UserForm::timeZone).sorted().toList();
	}

	private static boolean timeZone(String zone) {
		return ZoneId.getAvailableZoneIds().contains(zone)
				&& ("UTC".equals(zone) || zone.contains("/") && !zone.startsWith("SystemV/"));
	}
}
