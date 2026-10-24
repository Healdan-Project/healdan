package com.logicaldoc.healdan.web;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.metadata.Template;
import com.logicaldoc.core.metadata.TemplateDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIClient;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIHealdanSettings;
import com.logicaldoc.gui.frontend.client.healdan.services.HealdanSettingsService;
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
import com.logicaldoc.healdan.token.ApiTokens;
import com.logicaldoc.healdan.util.BasicPlane;

import jakarta.servlet.annotation.WebServlet;

/**
 * The Healdan Settings panel's server side (spec 8.6). Everything but
 * {@link #labels()} is for administrators only.
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
