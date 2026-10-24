package com.logicaldoc.healdan.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.util.BasicPlane;
import com.logicaldoc.util.spring.Context;

/**
 * Issues, finds and revokes device tokens. A token is {@code hd_} and the 43
 * characters of 32 random bytes in base64url; only its SHA-256 is stored.
 */
@Component
public class ApiTokens {

	public static final String PREFIX = "hd_";

	public static final String UNNAMED = "Unnamed device";

	static final int MAX_NAME_LENGTH = 100;

	private static final int TOKEN_LENGTH = PREFIX.length() + 43;

	private static final long TOUCH_EVERY_MILLIS = TimeUnit.MINUTES.toMillis(5);

	private static final Logger log = LoggerFactory.getLogger(ApiTokens.class);

	private static final SecureRandom RANDOM = new SecureRandom();

	/**
	 * A new token and its stored row. The token is shown once and not kept.
	 * 
	 * @param token the token itself
	 * @param row the stored row
	 */
	public record Issued(String token, ApiToken row) {
	}

	private final ApiTokenDAO dao;

	private final HealdanSettings settings;

	@Autowired
	public ApiTokens(ApiTokenDAO dao, HealdanSettings settings) {
		this.dao = dao;
		this.settings = settings;
	}

	/**
	 * @return the bean in the application context
	 */
	public static ApiTokens get() {
		return Context.get(ApiTokens.class);
	}

	/**
	 * @param token a token
	 * @return its lower-case hex SHA-256
	 */
	public static String hash(String token) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * @param key a presented key, possibly null
	 * @return true if it has the shape of a device token
	 */
	public static boolean looksLikeToken(String key) {
		return key != null && key.length() == TOKEN_LENGTH && key.startsWith(PREFIX);
	}

	/**
	 * Issues a token for a user.
	 * 
	 * @param user the user it signs in as
	 * @param name what the device is called; blank becomes "Unnamed device"
	 * @param sid the CE session it is bound to, or null
	 * @return the token and its row
	 * @throws PersistenceException error at data layer
	 * @throws HealdanRuleException if the name is too long or holds characters
	 *         the database can't store
	 */
	public Issued issue(User user, String name, String sid) throws PersistenceException {
		String clean = checkName(name);

		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

		Date now = new Date();
		ApiToken row = new ApiToken();
		row.setTenantId(user.getTenantId());
		row.setUserId(user.getId());
		row.setHash(hash(token));
		row.setName(clean);
		row.setLastUsed(now);
		row.setExpires(new Date(now.getTime() + TimeUnit.DAYS.toMillis(settings.apiTokenMaxDays())));
		row.setSid(sid);
		dao.store(row);
		return new Issued(token, row);
	}

	/**
	 * @param token a presented token
	 * @return its row, or empty if it isn't a token, is unknown or was revoked
	 */
	public Optional<ApiToken> find(String token) {
		if (!looksLikeToken(token))
			return Optional.empty();
		try {
			return Optional.ofNullable(dao.findByHash(hash(token)));
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't look up a device token", e);
		}
	}

	/**
	 * @param token a token row
	 * @param now the time to judge it at
	 * @return true if it isn't revoked, hasn't passed its absolute expiry and
	 *         was used within the idle limit
	 */
	public boolean usable(ApiToken token, Date now) {
		if (token.getDeleted() != 0)
			return false;
		long idleEnd = token.getLastUsed().getTime() + TimeUnit.DAYS.toMillis(settings.apiTokenIdleDays());
		return now.before(token.getExpires()) && now.getTime() < idleEnd;
	}

	/**
	 * Records a use, at most once every five minutes. The write is conditional,
	 * so of two parallel requests one wins and the other does nothing.
	 * 
	 * @param token a token row
	 * @param now the time of the use
	 */
	public void touch(ApiToken token, Date now) {
		Date cutoff = new Date(now.getTime() - TOUCH_EVERY_MILLIS);
		if (token.getLastUsed().after(cutoff))
			return;
		if (update("set lastUsed = :now where id = :id and deleted = 0 and lastUsed < :cutoff",
				Map.of("now", now, "id", token.getId(), "cutoff", cutoff)) > 0)
			token.setLastUsed(now);
	}

	/**
	 * @param token a token row
	 * @param sid the CE session to bind it to
	 */
	public void bind(ApiToken token, String sid) {
		if (update("set sid = :sid where id = :id and deleted = 0", Map.of("sid", sid, "id", token.getId())) > 0)
			token.setSid(sid);
	}

	/**
	 * Revokes a token and closes the session bound to it, and any other
	 * session opened with it as an API key (CE's {@code loginApiKey} routes).
	 * Revoking a revoked token does nothing.
	 * 
	 * @param token a token row
	 */
	public void revoke(ApiToken token) {
		// The bound session is read from the database: it may have changed since the row was loaded
		String sid = currentSid(token.getId());
		update("set deleted = 1 where id = :id and deleted = 0", Map.of("id", token.getId()));
		token.setDeleted(1);
		kill(sid);
		killKeySessions(List.of(token.getHash()));
	}

	/**
	 * Revokes all of a user's tokens and closes their sessions, as
	 * {@link #revoke(ApiToken)} does.
	 * 
	 * @param userId the user's id
	 * @return how many tokens were revoked
	 */
	public int revokeAll(long userId) {
		try {
			List<ApiToken> rows = dao.findByUser(userId);
			List<String> sids = rows.stream().map(t -> currentSid(t.getId())).filter(Objects::nonNull).toList();
			List<String> hashes = rows.stream().map(ApiToken::getHash).toList();
			int revoked = update("set deleted = 1 where userId = :userId and deleted = 0", Map.of("userId", userId));
			sids.forEach(ApiTokens::kill);
			killKeySessions(hashes);
			return revoked;
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't list device tokens", e);
		}
	}

	/**
	 * @param userId the user's id
	 * @return the user's usable tokens, newest first
	 */
	public List<ApiToken> list(long userId) {
		try {
			Date now = new Date();
			return dao.findByUser(userId).stream().filter(t -> usable(t, now)).toList();
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't list device tokens", e);
		}
	}

	private int update(String expression, Map<String, Object> parameters) {
		try {
			return dao.bulkUpdate(expression, parameters);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't save a device token", e);
		}
	}

	private String currentSid(long id) {
		try {
			return dao.queryForList("select hd_sid from hd_api_token where ld_id = " + id, String.class).stream()
					.filter(Objects::nonNull).findFirst().orElse(null);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read a device token", e);
		}
	}

	private static void kill(String sid) {
		if (sid == null)
			return;
		try {
			SessionManager.get().kill(sid);
		} catch (RuntimeException e) {
			log.warn("Couldn't close session {} of a revoked device token", sid, e);
		}
	}

	/**
	 * Closes the open sessions whose API key is one of these tokens. CE keeps
	 * a session's key as the upper-case hex SHA-256 of the key it was opened
	 * with; ours are lower-case.
	 * 
	 * @param hashes the tokens' hashes
	 */
	private static void killKeySessions(Collection<String> hashes) {
		Set<String> keys = hashes.stream().filter(Objects::nonNull).map(h -> h.toLowerCase(Locale.ROOT))
				.collect(Collectors.toSet());
		if (keys.isEmpty())
			return;
		SessionManager.get().getSessions().stream()
				.filter(s -> s.isOpen() && s.getKey() != null && keys.contains(s.getKey().toLowerCase(Locale.ROOT)))
				.map(Session::getSid).forEach(ApiTokens::kill);
	}

	/**
	 * Checks a device's name, so that a caller can refuse it before signing
	 * in.
	 * 
	 * @param name what the device is called, possibly null
	 * @return the name stripped, or "Unnamed device" if it is blank
	 * @throws HealdanRuleException if the name is too long or holds characters
	 *         the database can't store
	 */
	public static String checkName(String name) throws HealdanRuleException {
		String clean = name == null ? "" : name.strip();
		if (clean.isEmpty())
			return UNNAMED;
		if (clean.length() > MAX_NAME_LENGTH)
			throw new HealdanRuleException(
					"A device name can't be longer than %d characters.".formatted(MAX_NAME_LENGTH));
		if (!BasicPlane.holds(clean))
			throw new HealdanRuleException(
					"A device name can't contain emoji or other characters the database can't store.");
		return clean;
	}
}
