package com.logicaldoc.healdan.signin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.logicaldoc.util.spring.Context;

/**
 * The sign-ins through a provider that have started and not come back yet
 * (M4 spec sections 5.2 and 12), kept in memory by their state for 10
 * minutes. A restart drops them, and the person starts again.
 * <p>
 * Each is taken once, whatever becomes of it, so a state can't be used twice.
 * The map can't grow without end, however many sign-ins are started and never
 * finished: every {@link #put} first drops the ones that have expired, and
 * when {@value #MAX} are still waiting, it drops the oldest.
 */
@Component
public class PendingSignIns {

	/** How long a sign-in may take, from its start to its callback */
	public static final Duration LIFETIME = Duration.ofMinutes(10);

	/** The most sign-ins kept at once; past it, the oldest go */
	static final int MAX = 10_000;

	/** What the lifetimes are measured by; {@code SignInTestHooks} moves it in tests */
	static volatile Clock clock = Clock.systemUTC();

	/** In the order they were put, which is the order they expire in */
	private final LinkedHashMap<String, Pending> pending = new LinkedHashMap<>();

	/**
	 * A sign-in, started.
	 *
	 * @param provider the id of the provider it started with
	 * @param state the state sent to the provider, which the map is keyed by;
	 *        secret
	 * @param nonce the nonce the ID token must carry; secret
	 * @param verifier the PKCE verifier; secret
	 * @param browserHash the SHA-256, in hex, of the {@code hd_signin} cookie
	 *        set on the browser that started it
	 * @param expires when it can't come back any more
	 * @param helperRedirect the helper's loopback address, for a sign-in from
	 *        the helper; null for the web page's
	 * @param helperState the helper's own state; null for the web page's
	 * @param helperChallenge the helper's PKCE challenge; null for the web
	 *        page's
	 */
	public record Pending(String provider, String state, String nonce, String verifier, String browserHash,
			Instant expires, String helperRedirect, String helperState, String helperChallenge) {

		@Override
		public String toString() {
			return "Pending[provider=" + provider + ", expires=" + expires + ", helper="
					+ (helperRedirect != null ? "yes" : "no") + "]";
		}
	}

	/**
	 * @return the bean in the application context
	 */
	public static PendingSignIns get() {
		return Context.get(PendingSignIns.class);
	}

	/**
	 * @return when a sign-in starting now expires: {@link #LIFETIME} from now
	 */
	public Instant expiry() {
		return clock.instant().plus(LIFETIME);
	}

	/**
	 * Keeps a sign-in until its callback takes it, or it expires.
	 *
	 * @param p the sign-in
	 */
	public synchronized void put(Pending p) {
		sweep();
		while (pending.size() >= MAX) {
			Iterator<String> oldest = pending.keySet().iterator();
			oldest.next();
			oldest.remove();
		}
		pending.put(p.state(), p);
	}

	/**
	 * Takes a sign-in by its state. It's removed whatever comes of it, so a
	 * second callback with the same state finds nothing.
	 *
	 * @param state the state the provider sent back; possibly null
	 * @return the sign-in, or empty if the state is unknown, was used or has
	 *         expired
	 */
	public synchronized Optional<Pending> take(String state) {
		if (state == null)
			return Optional.empty();
		Pending p = pending.remove(state);
		if (p == null || expired(p, clock.instant()))
			return Optional.empty();
		return Optional.of(p);
	}

	/**
	 * @return the sign-in kept for a state, left in place; for tests
	 */
	synchronized Optional<Pending> peek(String state) {
		return Optional.ofNullable(pending.get(state));
	}

	/**
	 * @return how many sign-ins are kept, expired ones included until the next
	 *         {@link #put}
	 */
	synchronized int size() {
		return pending.size();
	}

	/** Drops the expired sign-ins at the head, the oldest */
	private void sweep() {
		Instant now = clock.instant();
		Iterator<Map.Entry<String, Pending>> entries = pending.entrySet().iterator();
		while (entries.hasNext()) {
			if (!expired(entries.next().getValue(), now))
				break;
			entries.remove();
		}
	}

	private static boolean expired(Pending p, Instant now) {
		return p.expires() == null || !now.isBefore(p.expires());
	}
}
