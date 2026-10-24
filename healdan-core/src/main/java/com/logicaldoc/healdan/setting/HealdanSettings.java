package com.logicaldoc.healdan.setting;

import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.matter.NumberingMode;
import com.logicaldoc.util.spring.Context;

/**
 * Healdan's settings, kept in the {@code hd_setting} table. Every typed getter
 * falls back to an in-code default (the plan's Global Constraints) when its
 * setting is missing or blank; the ids of things Healdan creates have none.
 */
@Component
public class HealdanSettings {

	public static final String MATTER_LABEL = "matter.label";

	public static final String MATTER_LABEL_PLURAL = "matter.label.plural";

	public static final String MATTER_NUMBERING = "matter.numbering";

	public static final String STORE_TEMPLATES = "store.templates";

	public static final String PROFILE_TEMPLATE = "profile.template";

	public static final String CLASS_DEFAULT = "class.default";

	public static final String RECENT_MATTERS_DAYS = "recentmatters.days";

	public static final String API_TOKEN_IDLE_DAYS = "api.token.idledays";

	public static final String API_TOKEN_MAX_DAYS = "api.token.maxdays";

	public static final String API_CORS_ORIGINS = "api.cors.origins";

	public static final int DEFAULT_API_TOKEN_IDLE_DAYS = 30;

	public static final int DEFAULT_API_TOKEN_MAX_DAYS = 365;

	public static final String DEFAULT_MATTER_LABEL = "Matter";

	public static final String DEFAULT_MATTER_LABEL_PLURAL = "Matters";

	public static final NumberingMode DEFAULT_NUMBERING = NumberingMode.PER_CLIENT;

	public static final String DEFAULT_CLASS = "Docs";

	public static final int DEFAULT_RECENT_MATTERS_DAYS = 30;

	private static final Logger log = LoggerFactory.getLogger(HealdanSettings.class);

	private final JdbcTemplate jdbc;

	@Autowired
	public HealdanSettings(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	/**
	 * @return the settings bean
	 */
	public static HealdanSettings get() {
		return Context.get(HealdanSettings.class);
	}

	/**
	 * @param key the setting name
	 * @return the value, or null if the setting has none
	 */
	public String get(String key) {
		List<String> values = jdbc.queryForList("select value from hd_setting where name = ?", String.class, key);
		return values.isEmpty() ? null : values.get(0);
	}

	/**
	 * Writes a setting, replacing any previous value.
	 * 
	 * @param key the setting name
	 * @param value the new value
	 */
	public void set(String key, String value) {
		if (jdbc.update("update hd_setting set value = ? where name = ?", value, key) == 0)
			jdbc.update("insert into hd_setting(name, value) values (?, ?)", key, value);
	}

	/**
	 * Writes a setting only if it doesn't exist yet.
	 * 
	 * @param key the setting name
	 * @param value the value to give it
	 * @return true if the setting was written
	 */
	public boolean setIfMissing(String key, String value) {
		if (get(key) != null)
			return false;
		jdbc.update("insert into hd_setting(name, value) values (?, ?)", key, value);
		return true;
	}

	/**
	 * @return the matter label, "Matter" by default
	 */
	public String matterLabel() {
		return stringValue(MATTER_LABEL, DEFAULT_MATTER_LABEL);
	}

	/**
	 * @return the plural matter label, "Matters" by default
	 */
	public String matterLabelPlural() {
		return stringValue(MATTER_LABEL_PLURAL, DEFAULT_MATTER_LABEL_PLURAL);
	}

	/**
	 * @return the numbering mode, per client by default
	 */
	public NumberingMode numberingMode() {
		String value = get(MATTER_NUMBERING);
		try {
			return StringUtils.isBlank(value) ? DEFAULT_NUMBERING : NumberingMode.valueOf(value.trim());
		} catch (IllegalArgumentException e) {
			log.warn("Unknown numbering mode '{}', using {}", value, DEFAULT_NUMBERING);
			return DEFAULT_NUMBERING;
		}
	}

	/**
	 * @return the id of the Templates Store, null if none is set
	 */
	public Long templatesStoreId() {
		return longValue(STORE_TEMPLATES);
	}

	/**
	 * @return the id of the Healdan profile template, null if none is set
	 */
	public Long profileTemplateId() {
		return longValue(PROFILE_TEMPLATE);
	}

	/**
	 * @return the default Class, "Docs" by default
	 */
	public String defaultClass() {
		return stringValue(CLASS_DEFAULT, DEFAULT_CLASS);
	}

	/**
	 * @return how many days Recent Matters looks back, 30 by default
	 */
	public int recentMattersDays() {
		Long days = longValue(RECENT_MATTERS_DAYS);
		return days != null ? days.intValue() : DEFAULT_RECENT_MATTERS_DAYS;
	}

	/**
	 * @return how many days a device token may go unused, 30 by default
	 */
	public int apiTokenIdleDays() {
		Long days = longValue(API_TOKEN_IDLE_DAYS);
		return days != null ? days.intValue() : DEFAULT_API_TOKEN_IDLE_DAYS;
	}

	/**
	 * @return how many days a device token lives, 365 by default
	 */
	public int apiTokenMaxDays() {
		Long days = longValue(API_TOKEN_MAX_DAYS);
		return days != null ? days.intValue() : DEFAULT_API_TOKEN_MAX_DAYS;
	}

	/**
	 * @return the origins the API allows across origins, empty by default
	 */
	public List<String> apiCorsOrigins() {
		String value = get(API_CORS_ORIGINS);
		if (StringUtils.isBlank(value))
			return List.of();
		return Arrays.stream(value.split(",")).map(String::trim).filter(o -> !o.isEmpty()).toList();
	}

	private String stringValue(String key, String defaultValue) {
		String value = get(key);
		return StringUtils.isBlank(value) ? defaultValue : value;
	}

	private Long longValue(String key) {
		String value = get(key);
		if (StringUtils.isBlank(value))
			return null;
		try {
			return Long.valueOf(value.trim());
		} catch (NumberFormatException e) {
			log.warn("Setting {} is not a number: '{}'", key, value);
			return null;
		}
	}
}
