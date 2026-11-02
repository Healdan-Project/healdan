package com.logicaldoc.healdan.signin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.signin.ProviderClient.Refreshed;
import com.logicaldoc.healdan.token.ApiToken;
import com.logicaldoc.healdan.token.ApiTokens;
import com.logicaldoc.util.spring.Context;

/**
 * Checks a helper's sign-in back with the provider it came through (M4 spec
 * section 9), and revokes its device token only when the answer shows the
 * person may no longer sign in. The API's filter asks {@link #keep} on every
 * request with a token, before the token's CE session.
 * <p>
 * <b>When.</b> Only a token from a provider sign-in that kept a refresh token
 * is checked, and only through a provider that's on: a provider turned off or
 * removed isn't contacted, and the token is kept. A check is due when the
 * token's CE session is opened again, or the last check is
 * {@code signin.recheck.minutes} old (480). After a check that decided nothing,
 * none is tried for 15 minutes; that wait is kept in memory, so a restart
 * forgets it.
 * <p>
 * <b>What revokes.</b> Only an answer that says no:
 * <ul>
 * <li>the provider refused the refresh ({@code invalid_grant}, ruling
 * R12);</li>
 * <li>it accepted, with an ID token for another identity than the account's
 * link, or one whose groups the gate refuses ({@link SignInAccounts#refresh},
 * which also takes the account out of every mapped group);</li>
 * <li>the account has no link in use to the provider any more (ruling R19),
 * which needs no answer;</li>
 * <li>the stored refresh token can't be opened while the provider's client
 * secret can, as after Healdan's key was lost and the secret entered again
 * (spec section 13).</li>
 * </ul>
 * Anything else decides nothing and keeps the token (Review Focus 5): no
 * connection, a timeout, a server error, an error about the setup such as an
 * expired client secret ({@link ProviderClient#refresh}), an ID token that
 * fails its checks, a key that can't be read, or an account that couldn't be
 * updated. A new refresh token is kept whatever the answer, as a provider that
 * turns them over has retired the old one.
 * <p>
 * <b>One check per token at a time.</b> A request that finds its token being
 * checked doesn't wait for the provider: it goes on with the token as it
 * stands (ruling R20). If the running check revokes the token before that
 * request reaches its session, the session is closed and can't be opened again
 * for a revoked token, so the request is refused there. The mark is never
 * waited on, and nothing is held once {@link #keep} returns, so the filter
 * calls CE's {@code SessionManager.newSession} with nothing of this class held
 * (linking holds a sign-in lock while it takes {@code SessionManager}'s
 * monitor). The mark is this node's only.
 * <p>
 * Each outcome is logged with the provider, the token's id and the account;
 * never a token, refresh token or claim.
 */
@Component
public class ProviderRecheck {

	/** How long after a check that decided nothing the next may be tried */
	static final Duration WAIT_AFTER_UNDECIDED = Duration.ofMinutes(15);

	/** The clock checks are timed by; {@code SignInTestHooks} moves it in tests */
	static volatile Clock clock = Clock.systemUTC();

	private static final Logger log = LoggerFactory.getLogger(ProviderRecheck.class);

	/** The ids of the tokens being checked now */
	private final Set<Long> checking = ConcurrentHashMap.newKeySet();

	/** When each token's last check that decided nothing was tried, by the token's id */
	private final Map<Long, Instant> undecided = new ConcurrentHashMap<>();

	private final SignInProviders providers;

	private final ProviderClient client;

	private final SignInAccounts accounts;

	private final SignInIdentityDAO identities;

	private final SecretBox box;

	private final ApiTokens tokens;

	@Autowired
	public ProviderRecheck(SignInProviders providers, ProviderClient client, SignInAccounts accounts,
			SignInIdentityDAO identities, SecretBox box, ApiTokens tokens) {
		this.providers = providers;
		this.client = client;
		this.accounts = accounts;
		this.identities = identities;
		this.box = box;
		this.tokens = tokens;
	}

	/**
	 * @return the bean in the application context
	 */
	public static ProviderRecheck get() {
		return Context.get(ProviderRecheck.class);
	}

	/**
	 * Checks a usable token with its provider, if a check is due, and revokes
	 * it if the answer says no. Call it outside a transaction, as
	 * {@link SignInAccounts#refresh} runs its own.
	 *
	 * @param token a usable token, as the request read it
	 * @param sessionReopening true when the token's CE session is closed and the
	 *        request is about to open it again
	 * @return false when the provider refused and the token is now revoked
	 *         (spec section 9), or the token was revoked since the request read
	 *         it; true otherwise, the token kept
	 * @throws IllegalStateException if the database can't be read or written
	 */
	public boolean keep(ApiToken token, boolean sessionReopening) {
		Objects.requireNonNull(token, "token");
		// A password sign-in, or a provider that gave no refresh token: nothing to check with
		if (token.getProvider() == null || token.getRefresh() == null)
			return true;
		Instant now = clock.instant();
		if (!sessionReopening && !overdue(token.getChecked(), now))
			return true;
		if (waiting(token.getId(), now))
			return true;
		Optional<SignInProvider> provider = on(token.getProvider());
		if (provider.isEmpty())
			return true;

		// Another request is checking it: don't wait for the provider
		if (!checking.add(token.getId()))
			return true;
		try {
			// Read again: another request may have checked or revoked it since this one read it, and its refresh
			// token is then the new one; the old one, which a provider that turns them over refuses, is spent
			Optional<ApiTokens.Recheck> state = tokens.recheckState(token.getId());
			if (state.isEmpty())
				return false;
			if (after(state.get().checked(), token.getChecked()) || waiting(token.getId(), now)
					|| state.get().refresh() == null)
				return true;
			return check(token, provider.get(), state.get().refresh(), now);
		} finally {
			checking.remove(token.getId());
		}
	}

	/**
	 * @param tokenId a token's id
	 * @return true while a request is checking it
	 */
	boolean checking(long tokenId) {
		return checking.contains(tokenId);
	}

	private boolean check(ApiToken token, SignInProvider p, String sealed, Instant now) {
		User user = user(token);
		if (user == null)
			return revoke(token, p, "(deleted)", "the account no longer exists");
		String account = printable(user.getUsername());

		// Ruling R19: unlinked, or linked to another provider, the account isn't this provider's any more
		SignInIdentity link = link(token);
		if (link == null || !p.id().equals(link.getProvider()))
			return revoke(token, p, account, "the account isn't linked to the provider any more");

		String refresh = box.open(sealed);
		if (StringUtils.isBlank(refresh)) {
			// The client secret opened a moment ago; if it can't now, the key can't be read, which decides nothing
			if (providers.secret(p.id()) == null)
				return undecided(token, p, account, now, "Healdan's secret key couldn't be read");
			return revoke(token, p, account,
					"its refresh token can't be opened, as after Healdan's secret key was lost or replaced");
		}

		Refreshed answer = client.refresh(p, refresh);
		// Kept whatever the answer: a provider that turns refresh tokens over has retired the old one
		String next = seal(answer.refreshToken(), p, account);
		return switch (answer.outcome()) {
			case REFUSED -> revoke(token, p, account, "the provider refused it");
			case UNREACHABLE -> {
				tokens.recordRecheck(token, null, next);
				yield undecided(token, p, account, now, "the provider couldn't be reached, or gave no answer that "
						+ "decides it");
			}
			case ACCEPTED -> accepted(token, p, user, account, link, answer.claims(), next, now);
		};
	}

	private boolean accepted(ApiToken token, SignInProvider p, User user, String account, SignInIdentity link,
			IdClaims claims, String next, Instant now) {
		if (claims != null) {
			if (!link.getIdentity().equals(claims.identity()))
				return revoke(token, p, account, "the provider answered for another identity than the account's");
			boolean allowed;
			try {
				allowed = accounts.refresh(user, p, claims);
			} catch (PersistenceException | RuntimeException e) {
				// The class only: a database's message can quote the row
				log.debug("The re-check's update of the account failed", e);
				tokens.recordRecheck(token, null, next);
				return undecided(token, p, account, now,
						"the account couldn't be updated from the answer (" + e.getClass().getSimpleName() + ")");
			}
			if (!allowed)
				return revoke(token, p, account, "the provider's groups for it map to no Healdan group");
		}
		tokens.recordRecheck(token, Date.from(now), next);
		undecided.remove(token.getId());
		log.info("The sign-in provider {} accepted the re-check of the device token {} of the account {}{}", p.id(),
				token.getId(), account, claims == null ? ", with no ID token, so the account wasn't updated" : "");
		return true;
	}

	private boolean revoke(ApiToken token, SignInProvider p, String account, String why) {
		tokens.revoke(token);
		undecided.remove(token.getId());
		log.info("Revoked the device token {} of the account {} from the sign-in provider {}: {}", token.getId(),
				account, p.id(), why);
		return false;
	}

	private boolean undecided(ApiToken token, SignInProvider p, String account, Instant now, String why) {
		// Forget the waits that are over, so the map holds only the tokens of an outage still going on
		undecided.values().removeIf(tried -> !now.isBefore(tried.plus(WAIT_AFTER_UNDECIDED)));
		undecided.put(token.getId(), now);
		log.warn("Couldn't re-check the device token {} of the account {} with the sign-in provider {}: {}. It's "
				+ "kept, and checked again no sooner than {} minutes from now.", token.getId(), account, p.id(), why,
				WAIT_AFTER_UNDECIDED.toMinutes());
		return true;
	}

	/**
	 * @return the new refresh token sealed, or null if there is none or it
	 *         couldn't be sealed
	 */
	private String seal(String refreshToken, SignInProvider p, String account) {
		if (refreshToken == null)
			return null;
		try {
			return box.seal(refreshToken);
		} catch (IllegalStateException e) {
			// Its message holds no secret, and neither does this line
			log.warn("Couldn't seal the new refresh token the sign-in provider {} gave for the account {}, as "
					+ "Healdan's secret key couldn't be read or made. The device token keeps the old one, which the "
					+ "provider may refuse at the next check.", p.id(), account);
			return null;
		}
	}

	private boolean overdue(Date checked, Instant now) {
		if (checked == null)
			return true;
		return !now.isBefore(checked.toInstant().plus(Duration.ofMinutes(providers.recheckMinutes())));
	}

	private boolean waiting(long tokenId, Instant now) {
		Instant tried = undecided.get(tokenId);
		return tried != null && now.isBefore(tried.plus(WAIT_AFTER_UNDECIDED));
	}

	/**
	 * @return the provider, if it's one people can sign in through now: on,
	 *         with an issuer, a client id and a secret that opens
	 */
	private Optional<SignInProvider> on(String id) {
		return providers.enabled().stream().filter(p -> p.id().equals(id)).findFirst();
	}

	/** True if the time stored is later than the time the request read */
	private static boolean after(Date stored, Date read) {
		return stored != null && (read == null || stored.getTime() > read.getTime());
	}

	private static User user(ApiToken token) {
		try {
			return UserDAO.get().findById(token.getUserId());
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the user of a device token", e);
		}
	}

	private SignInIdentity link(ApiToken token) {
		try {
			return identities.findLinkedByUser(token.getUserId()).orElse(null);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read an account's sign-in link", e);
		}
	}

	/**
	 * A username for a log line: its control characters replaced, so it can't
	 * forge a line, and at most 100 characters
	 */
	private static String printable(String text) {
		if (text == null)
			return "(no username)";
		StringBuilder clean = new StringBuilder(text.length());
		text.codePoints().forEach(c -> clean.appendCodePoint(Character.isISOControl(c) ? '?' : c));
		return StringUtils.abbreviate(clean.toString(), 100);
	}
}
