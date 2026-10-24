package com.logicaldoc.healdan.seed;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.security.LoginThrottle;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.sequence.Sequence;
import com.logicaldoc.core.sequence.SequenceDAO;

/**
 * Deletes the sign-in throttle's failure counters that are named after an API
 * key itself. CE named a key's counter {@code loginfail-apikey-<key>}, so
 * {@code ld_sequence} kept every key that failed to sign in. Healdan names it
 * after the key's SHA-256 now. Those keys' counts start again from nothing.
 */
@Component
public class ApiKeyCountersSeed implements SeedStep {

	// How Encrypter.encryptSHA256 writes a digest
	private static final Pattern DIGEST = Pattern.compile("[0-9A-F]{64}");

	@Override
	public String name() {
		return "apikey-counters";
	}

	@Override
	public void run() throws Exception {
		SequenceDAO dao = SequenceDAO.get();
		for (Sequence counter : dao.findByName(LoginThrottle.LOGINFAIL_APIKEY, Tenant.SYSTEM_ID))
			if (!DIGEST.matcher(counter.getName().substring(LoginThrottle.LOGINFAIL_APIKEY.length())).matches())
				dao.delete(counter.getId());
	}
}
