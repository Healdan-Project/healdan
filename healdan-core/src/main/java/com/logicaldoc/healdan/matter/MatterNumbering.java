package com.logicaldoc.healdan.matter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.spring.Context;

/**
 * Matter numbers under the current {@link NumberingMode}: what to suggest, what
 * is taken, and switching the mode (spec 5.1). A deleted matter keeps its
 * number, so every check here counts deleted matters too.
 */
@Component
public class MatterNumbering {

	private final HealdanSettings settings;

	private final JdbcTemplate jdbc;

	@Autowired
	public MatterNumbering(HealdanSettings settings, DataSource dataSource) {
		this.settings = settings;
		this.jdbc = new JdbcTemplate(dataSource);
	}

	/**
	 * @return the numbering bean
	 */
	public static MatterNumbering get() {
		return Context.get(MatterNumbering.class);
	}

	/**
	 * @param clientId the id of the client the matter is for
	 * @return the number to pre-fill: the highest number in use (the client's,
	 *         or the whole install's when system-wide) plus 1
	 * @throws PersistenceException error at data layer
	 */
	public long suggest(long clientId) throws PersistenceException {
		Long scope = settings.numberingMode() == NumberingMode.SYSTEM_WIDE ? null : clientId;
		return MatterDAO.get().maxNumber(scope) + 1;
	}

	/**
	 * Refuses a number that is taken under the current mode.
	 * 
	 * @param clientId the id of the client the matter is for
	 * @param number the wanted number
	 * @throws HealdanRuleException if the number is not positive or is taken,
	 *         even by a deleted matter
	 */
	public void checkAvailable(long clientId, long number) throws HealdanRuleException {
		if (number < 1)
			throw new HealdanRuleException("The %s number must be a positive whole number."
					.formatted(settings.matterLabel().toLowerCase()));

		List<String> holders;
		if (settings.numberingMode() == NumberingMode.SYSTEM_WIDE)
			holders = jdbc.queryForList("select c.hd_number from hd_matter m join hd_client c on c.ld_id = m.hd_clientid "
					+ "where m.hd_number = ? order by c.hd_number", String.class, number);
		else
			holders = jdbc.queryForList("select c.hd_number from hd_matter m join hd_client c on c.ld_id = m.hd_clientid "
					+ "where m.hd_number = ? and m.hd_clientid = ?", String.class, number, clientId);

		if (!holders.isEmpty())
			throw new HealdanRuleException(settings.numberingMode() == NumberingMode.SYSTEM_WIDE
					? "%s number %d is already used (by client %s). Numbers are unique across all clients."
							.formatted(settings.matterLabel(), number, holders.get(0))
					: "%s number %d is already used by this client.".formatted(settings.matterLabel(), number));
	}

	/**
	 * @return one line for each matter number that more than one client uses,
	 *         such as {@code 1: 998.1, 999.1}; empty if there is none
	 */
	public List<String> systemWideClashes() {
		Map<Long, List<String>> byMatter = new TreeMap<>();
		jdbc.query("select c.hd_number, m.hd_number from hd_matter m join hd_client c on c.ld_id = m.hd_clientid "
				+ "order by m.hd_number, c.hd_number", rs -> {
					long client = rs.getLong(1);
					long matter = rs.getLong(2);
					byMatter.computeIfAbsent(matter, k -> new ArrayList<>()).add("%d.%d".formatted(client, matter));
				});

		return byMatter.entrySet().stream().filter(e -> e.getValue().size() > 1)
				.map(e -> "%d: %s".formatted(e.getKey(), String.join(", ", e.getValue())))
				.collect(Collectors.toList());
	}

	/**
	 * Changes the numbering mode.
	 * 
	 * @param mode the new mode
	 * @throws HealdanRuleException if switching to system-wide while two
	 *         clients share a matter number; the message lists them
	 */
	public void setMode(NumberingMode mode) throws HealdanRuleException {
		checkMode(mode);
		settings.set(HealdanSettings.MATTER_NUMBERING, mode.name());
	}

	/**
	 * Checks a mode change without making it.
	 *
	 * @param mode the new mode
	 * @throws HealdanRuleException if the mode is system-wide while two clients
	 *         share a matter number; the message lists them
	 */
	public void checkMode(NumberingMode mode) throws HealdanRuleException {
		if (mode == NumberingMode.SYSTEM_WIDE) {
			List<String> clashes = systemWideClashes();
			if (!clashes.isEmpty())
				throw new HealdanRuleException(
						"Numbers must be unique across all clients, but these are shared: %s. Renumber them first."
								.formatted(String.join("; ", clashes)));
		}
	}
}
