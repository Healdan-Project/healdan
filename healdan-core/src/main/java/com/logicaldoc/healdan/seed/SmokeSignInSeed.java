package com.logicaldoc.healdan.seed;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserSource;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.signin.GroupMapping;
import com.logicaldoc.healdan.signin.Preset;
import com.logicaldoc.healdan.signin.SignInProvider;
import com.logicaldoc.healdan.signin.SignInProviders;
import com.logicaldoc.util.spring.Context;

/**
 * For the smoke test only: when {@code HEALDAN_SMOKE_SIGNIN_ISSUER},
 * {@code HEALDAN_SMOKE_SIGNIN_CLIENT_ID} and
 * {@code HEALDAN_SMOKE_SIGNIN_CLIENT_SECRET} are set, as
 * {@code docker/tests/compose.signin.yaml} sets them, sets Healdan up to sign
 * in through the stand-in provider that overlay runs (M4 spec section 16.1):
 * <ul>
 * <li>the provider {@code smoke}, preset {@code other}, linking by email;</li>
 * <li>the group {@code Smoke SSO}, which the provider's group
 * {@code healdan-users} maps to;</li>
 * <li>the local account {@code sso-user}, with the email the stand-in gives
 * its {@code sso-user} and a password, in {@code Smoke SSO}, so that the
 * first sign-in links it by email;</li>
 * <li>{@code signin.recheck.minutes} at 0, so a device token is checked with
 * the provider on every request.</li>
 * </ul>
 * The issuer is plain HTTP, which needs {@code HEALDAN_SIGNIN_ALLOW_HTTP}
 * too; without it the step fails, and startup with it.
 * <p>
 * Production never sets the variables. Without all three the step does
 * nothing and writes no marker, so it can run on a later start.
 */
@Component
public class SmokeSignInSeed implements SeedStep {

	static final String ISSUER = "HEALDAN_SMOKE_SIGNIN_ISSUER";

	static final String CLIENT_ID = "HEALDAN_SMOKE_SIGNIN_CLIENT_ID";

	static final String CLIENT_SECRET = "HEALDAN_SMOKE_SIGNIN_CLIENT_SECRET";

	private static final List<String> VARIABLES = List.of(ISSUER, CLIENT_ID, CLIENT_SECRET);

	static final String PROVIDER = "smoke";

	static final String LABEL = "Sign In with Smoke SSO";

	static final String GROUP = "Smoke SSO";

	/** The stand-in's group whose members may sign in, mapped to {@link #GROUP} */
	static final String PROVIDER_GROUP = "healdan-users";

	static final String USERNAME = "sso-user";

	static final String EMAIL = "sso-user@smoke.example";

	/**
	 * A throwaway password for a throwaway account on a throwaway stack, which
	 * {@code docker/tests/test-signin.sh} signs in with before the account is
	 * linked, and is refused with after
	 */
	static final String LOCAL_PASSWORD = "Smoke-Local-Pw-42!x";

	private static final Logger log = LoggerFactory.getLogger(SmokeSignInSeed.class);

	private final Supplier<Map<String, String>> environment;

	@Autowired
	public SmokeSignInSeed() {
		this(System::getenv);
	}

	SmokeSignInSeed(Supplier<Map<String, String>> environment) {
		this.environment = environment;
	}

	@Override
	public String name() {
		return "smoke-signin";
	}

	@Override
	public boolean enabled() {
		Map<String, String> env = environment.get();
		List<String> set = VARIABLES.stream().filter(name -> StringUtils.isNotBlank(env.get(name))).toList();
		if (!set.isEmpty() && set.size() < VARIABLES.size())
			log.warn("The smoke test's sign-in seed needs {}, {} and {}, and only {} set: it waits for all three",
					ISSUER, CLIENT_ID, CLIENT_SECRET, String.join(" and ", set));
		return set.size() == VARIABLES.size();
	}

	/**
	 * Sets everything up in one transaction: a failure leaves nothing behind,
	 * so the next start, which finds no marker, can run it again
	 */
	@Override
	public void run() throws Exception {
		Map<String, String> env = environment.get();
		String issuer = env.get(ISSUER).strip();
		String clientId = env.get(CLIENT_ID).strip();
		String secret = env.get(CLIENT_SECRET).strip();
		if (issuer.toLowerCase(Locale.ROOT).startsWith("http:") && !SignInProviders.httpAllowed())
			throw new IllegalStateException(("%s is a plain http:// address, which Healdan refuses unless "
					+ "HEALDAN_SIGNIN_ALLOW_HTTP is true, as docker/tests/compose.signin.yaml sets it").formatted(ISSUER));

		new TransactionTemplate(Context.get(PlatformTransactionManager.class)).execute(status -> {
			try {
				create(issuer, clientId, secret);
			} catch (RuntimeException e) {
				throw e;
			} catch (Exception e) {
				throw new IllegalStateException(e.getMessage(), e);
			}
			return null;
		});
	}

	private void create(String issuer, String clientId, String secret) throws Exception {
		SignInProviders providers = SignInProviders.get();
		Preset preset = Preset.OTHER;
		providers.save(new SignInProvider(PROVIDER, preset, LABEL, true, issuer, clientId, false,
				preset.identityClaim(), preset.usernameClaim(), preset.emailClaim(), preset.groupsClaim(), true),
				secret);

		Group group = GroupDAO.get().findByName(GROUP, Tenant.DEFAULT_ID);
		if (group == null) {
			group = new Group();
			group.setName(GROUP);
			group.setDescription("The smoke test's stand-in provider's healdan-users");
			group.setTenantId(Tenant.DEFAULT_ID);
			GroupDAO.get().store(group);
			group = GroupDAO.get().findByName(GROUP, Tenant.DEFAULT_ID);
		}
		providers.saveGroups(PROVIDER, List.of(new GroupMapping(PROVIDER_GROUP, group.getId())));

		User user = new User();
		user.setTenantId(Tenant.DEFAULT_ID);
		user.setUsername(USERNAME);
		user.setFirstName("Sso");
		user.setName("User");
		user.setEmail(EMAIL);
		user.setDecodedPassword(LOCAL_PASSWORD);
		user.setType(User.Type.DEFAULT);
		user.setSource(UserSource.DEFAULT);
		user.setEnabled(true);
		user.addGroup(group);
		UserDAO.get().store(user);

		HealdanSettings.get().set("signin.recheck.minutes", "0");
	}
}
