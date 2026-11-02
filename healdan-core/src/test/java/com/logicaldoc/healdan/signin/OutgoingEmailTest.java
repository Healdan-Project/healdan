package com.logicaldoc.healdan.signin;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * When outgoing email counts as set up (plan correction 3): CE ships
 * {@code localhost} and {@code logicaldoc@acme.com}, and a stack nobody set up
 * keeps them, so neither counts.
 */
public class OutgoingEmailTest {

	private static final String SERVER = "smtp.healdan.example";

	private static final String SENDER = "office@healdan.example";

	@Test
	public void testSetUp() {
		assertTrue(OutgoingEmail.isSetUp(SERVER, SENDER));
		assertTrue(OutgoingEmail.isSetUp(" " + SERVER + " ", " " + SENDER + " "));
		// An address on the LAN is a mail server like any other
		assertTrue(OutgoingEmail.isSetUp("192.0.2.25", SENDER));
	}

	@Test
	public void testThisMachineIsNoMailServer() {
		for (String host : new String[] { "localhost", "LOCALHOST", " localhost ", "127.0.0.1", "::1", "[::1]" })
			assertFalse(host, OutgoingEmail.isSetUp(host, SENDER));
	}

	@Test
	public void testBlankServer() {
		assertFalse(OutgoingEmail.isSetUp(null, SENDER));
		assertFalse(OutgoingEmail.isSetUp("", SENDER));
		assertFalse(OutgoingEmail.isSetUp("  ", SENDER));
	}

	@Test
	public void testShippedOrBlankSender() {
		assertFalse(OutgoingEmail.isSetUp(SERVER, "logicaldoc@acme.com"));
		assertFalse(OutgoingEmail.isSetUp(SERVER, " LogicalDOC@Acme.com "));
		assertFalse(OutgoingEmail.isSetUp(SERVER, null));
		assertFalse(OutgoingEmail.isSetUp(SERVER, ""));
		assertFalse(OutgoingEmail.isSetUp(SERVER, "  "));
	}
}
