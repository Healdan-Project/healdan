package com.logicaldoc.healdan.seed;

import org.springframework.stereotype.Component;

import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;

/**
 * Turns on CE's sign-in throttle with Healdan's limits: five wrong passwords
 * block a username for 15 minutes, and 20 failures block an address for 15
 * minutes. CE ships the throttle off. This overwrites the shipped values once;
 * an admin's later changes stay.
 */
@Component
public class SignInThrottleSeed implements SeedStep {

	@Override
	public String name() {
		return "throttle-signin";
	}

	@Override
	public void run() throws Exception {
		ContextProperties config = Context.get().getConfig();
		config.setProperty("throttle.enabled", "true");
		config.setProperty("throttle.username.max", "5");
		config.setProperty("throttle.username.wait", "15");
		config.setProperty("throttle.ip.max", "20");
		config.setProperty("throttle.ip.wait", "15");
		config.setProperty("throttle.username.disableuser", "false");
		config.write();
	}
}
