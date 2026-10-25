package com.logicaldoc.core.security;

import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.apikey.ApiKey;
import com.logicaldoc.core.security.apikey.ApiKeyDAO;
import com.logicaldoc.core.security.authentication.AuthenticationException;
import com.logicaldoc.core.security.user.HibernateUserDAO;
import com.logicaldoc.core.sequence.Sequence;
import com.logicaldoc.core.sequence.SequenceDAO;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.crypt.Encrypter;
import com.logicaldoc.util.spring.Context;

/**
 * How {@link SessionManager#newSession} runs CE's {@link LoginThrottle} on
 * every sign-in route (M2 spec section 5.7).
 * <p>
 * Healdan addition: lives in its own file so upstream merges don't touch it.
 */
final class HealdanThrottle {

    private static final Logger log = LoggerFactory.getLogger(HealdanThrottle.class);

    private HealdanThrottle() {
    }

    /**
     * The account a sign-in checks the password of, as the throttle counts it:
     * the name before an impersonation's {@code >}, trimmed, and in lower case
     * when sign-in ignores case. Every way of writing a name then shares its
     * account's count.
     *
     * @param username the username as given, possibly null
     *
     * @return the account, empty if there is none: no failure is ever counted
     *         under an empty name
     */
    static String account(String username) {
        String account = StringUtils.substringBefore(StringUtils.defaultString(username), ">").trim();
        return HibernateUserDAO.ignoreCaseLogin() ? account.toLowerCase(Locale.ROOT) : account;
    }

    /**
     * Runs the login throttle. A blocked attempt is logged as a failed login,
     * which the old login page reads to say why, but is not counted.
     *
     * @param account the account, as {@link #account(String)} gives it
     * @param key the key presented, possibly null
     * @param client the client, possibly null
     *
     * @throws AuthenticationException if the throttle blocks the sign-in
     */
    static void check(String account, String key, Client client) throws AuthenticationException {
        try {
            LoginThrottle.checkLoginThrottle(account, key, client != null ? client.getAddress() : null);
        } catch (AuthenticationException e) {
            LoginThrottle.recordFailedLogin(account, client, e.getMessage());
            throw e;
        }
    }

    /**
     * Clears the counts after a sign-in succeeded. Only a password clears them:
     * with a key, an API key or a device token may have signed in instead. The
     * address's count is cleared only once its last failure is older than the
     * wait, so that one account's success can't reset an attack on others from
     * the same address.
     *
     * @param account the account, as {@link #account(String)} gives it
     * @param key the key presented, possibly null
     * @param client the client, possibly null
     */
    static void succeeded(String account, String key, Client client) {
        if (StringUtils.isNotEmpty(key))
            return;
        String address = client != null ? client.getAddress() : null;
        LoginThrottle.clearFailures(account, failedRecently(address) ? null : address);
    }

    /**
     * The failure counter of an API key. It's named after the key's SHA-256,
     * which is also what {@code ld_apikey} stores, so that {@code ld_sequence}
     * never holds a key.
     *
     * @param key the key presented
     *
     * @return the counter's name
     */
    static String keyCounter(String key) {
        try {
            return LoginThrottle.LOGINFAIL_APIKEY + Encrypter.encryptSHA256(key);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * How an alert or the log names an API key, never with the key itself: the
     * label the API Keys screen shows when it's a key of this install.
     *
     * @param key the key presented
     *
     * @return the key's label, or {@code unknown}
     */
    static String keyLabel(String key) {
        try {
            ApiKey apiKey = ApiKeyDAO.get().findByKey(key);
            if (apiKey != null)
                return apiKey.getLabel();
        } catch (PersistenceException | NoSuchAlgorithmException e) {
            log.warn(e.getMessage(), e);
        }
        return "unknown";
    }

    /**
     * Whether a failure just counted starts a block, which the checks then
     * enforce: it's the limit's own count, and the limit is on. CE alerted on
     * every blocked attempt; Healdan alerts once, when a block starts.
     *
     * @param limit {@code username}, {@code ip} or {@code apikey}, as in the
     *        {@code throttle.<limit>.max} setting
     * @param count the counter's value after this failure
     *
     * @return true when the alert is due
     */
    static boolean startsBlock(String limit, long count) {
        ContextProperties config = Context.get().getConfig();
        int max = config.getInt("throttle." + limit + ".max", 0);
        return max > 0 && config.getInt("throttle." + limit + ".wait", 0) > 0 && count == max;
    }

    private static boolean failedRecently(String address) {
        ContextProperties config = Context.get().getConfig();
        if (StringUtils.isEmpty(address) || !config.getBoolean("throttle.enabled"))
            return false;
        try {
            Sequence counter = SequenceDAO.get().findByAlternateKey(LoginThrottle.LOGINFAIL_IP + address, 0L,
                    Tenant.SYSTEM_ID);
            Date waitStart = DateUtils.addMinutes(new Date(), -config.getInt("throttle.ip.wait", 0));
            return counter != null && waitStart.before(counter.getLastModified());
        } catch (PersistenceException e) {
            log.warn(e.getMessage(), e);
            return true;
        }
    }
}
