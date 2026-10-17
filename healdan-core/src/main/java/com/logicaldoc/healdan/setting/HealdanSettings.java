package com.logicaldoc.healdan.setting;

import java.util.List;

import javax.sql.DataSource;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.healdan.matter.NumberingMode;
import com.logicaldoc.util.spring.Context;

/**
 * Healdan's settings, kept in the {@code hd_setting} table.
 */
@Component
public class HealdanSettings {

	public static final String MATTER_LABEL = "matter.label";

	public static final String MATTER_LABEL_PLURAL = "matter.label.plural";

	public static final String MATTER_NUMBERING = "matter.numbering";

	public static final String STORE_DEFAULT = "store.default";

	public static final String STORE_TEMPLATES = "store.templates";

	public static final String PROFILE_TEMPLATE = "profile.template";

	public static final String CLASS_DEFAULT = "class.default";

	public static final String RECENT_MATTERS_DAYS = "recentmatters.days";

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

	public String matterLabel() {
		return get(MATTER_LABEL);
	}

	public String matterLabelPlural() {
		return get(MATTER_LABEL_PLURAL);
	}

	public NumberingMode numberingMode() {
		String value = get(MATTER_NUMBERING);
		try {
			return StringUtils.isBlank(value) ? NumberingMode.PER_CLIENT : NumberingMode.valueOf(value.trim());
		} catch (IllegalArgumentException e) {
			log.warn("Unknown numbering mode '{}', using per client", value);
			return NumberingMode.PER_CLIENT;
		}
	}

	/**
	 * @return the id of the default Store, or null if none is set or that
	 *         folder is gone or deleted
	 */
	public Long defaultStoreId() {
		Long id = longValue(STORE_DEFAULT);
		if (id == null)
			return null;
		try {
			Folder store = FolderDAO.get().findById(id);
			return store != null ? id : null;
		} catch (PersistenceException e) {
			log.warn("Cannot read the default Store {}", id, e);
			return null;
		}
	}

	public Long templatesStoreId() {
		return longValue(STORE_TEMPLATES);
	}

	public Long profileTemplateId() {
		return longValue(PROFILE_TEMPLATE);
	}

	public String defaultClass() {
		return get(CLASS_DEFAULT);
	}

	public int recentMattersDays() {
		Long days = longValue(RECENT_MATTERS_DAYS);
		return days != null ? days.intValue() : 30;
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
