// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.signin.SecretBox;
import com.logicaldoc.healdan.storage.backend.BackendFailingException;
import com.logicaldoc.healdan.storage.backend.BackendUnreachableException;
import com.logicaldoc.healdan.storage.backend.FakeDns;
import com.logicaldoc.healdan.storage.backend.S3Backend;

/**
 * Building backends from their rows: a failed build is remembered for a while, and every way a build can be refused
 * is a backend failure.
 */
public class BackendsTest extends AbstractStorageTestCase {

	private static long s3Row(String endpoint, String secret) throws Exception {
		StorageBackend row = new StorageBackend();
		row.setTenantId(Tenant.DEFAULT_ID);
		row.setName("Cloud " + endpoint);
		row.setKind(StorageBackend.S3);
		row.setEndpoint(endpoint);
		row.setRegion("test-1");
		row.setBucket("healdan-tests");
		row.setPrefix("healdan/");
		row.setAccessKey("AKTEST");
		// http:// needs it
		row.setPathStyleOn(true);
		row.setSecret(secret == null ? null : SecretBox.get().seal(secret));
		StorageBackendDAO.get().store(row);
		return row.getId();
	}

	private static void setSecret(long id, String secret) throws Exception {
		StorageBackend row = StorageBackendDAO.get().findById(id);
		row.setSecret(SecretBox.get().seal(secret));
		StorageBackendDAO.get().store(row);
	}

	@Test
	public void testFailedBuildRemembered() throws Exception {
		Backends backends = Backends.get();
		AtomicLong now = new AtomicLong(1_000_000L);
		backends.clock = now::get;
		long id = s3Row("https://s3.example.test", null);

		BackendFailingException first = assertThrows(BackendFailingException.class, () -> backends.entry(id));
		assertTrue(first.getMessage(), first.getMessage().contains("doesn't open"));

		// The secret is entered again, but for 30 seconds the failure is answered as it was, without building
		setSecret(id, "a secret");
		now.addAndGet(29_000L);
		BackendFailingException again = assertThrows(BackendFailingException.class, () -> backends.entry(id));
		assertEquals(first.getMessage(), again.getMessage());
		assertFalse(again instanceof BackendUnreachableException);

		now.addAndGet(2_000L);
		assertTrue(backends.entry(id).backend() instanceof S3Backend);

		// forget clears a remembered failure at once, as saving a backend calls it
		long other = s3Row("https://s3.example.test", null);
		assertThrows(BackendFailingException.class, () -> backends.entry(other));
		setSecret(other, "a secret");
		assertThrows(BackendFailingException.class, () -> backends.entry(other));
		backends.forget(other);
		assertTrue(backends.entry(other).backend() instanceof S3Backend);
	}

	@Test
	public void testUnreachableHostRememberedAsUnreachable() throws Exception {
		Backends backends = Backends.get();
		AtomicLong now = new AtomicLong(1_000_000L);
		backends.clock = now::get;
		// http needs every address of the host private, so the host is looked up when the backend is built
		long id = s3Row("http://no-such-host.lan:3900", "a secret");

		try (FakeDns dns = FakeDns.of(Map.of())) {
			assertThrows(BackendUnreachableException.class, () -> backends.entry(id));
			now.addAndGet(10_000L);
			assertThrows("an outage is remembered as one", BackendUnreachableException.class, () -> backends.entry(id));
			assertEquals("looked up once, in the map", List.of("no-such-host.lan"), dns.looked());
		}
	}

	@Test
	public void testRefusedSettingsAreFailures() throws Exception {
		// A secret that opens blank: S3Backend refuses it with an IllegalArgumentException
		long blank = s3Row("https://s3.example.test", " ");
		BackendFailingException refused = assertThrows(BackendFailingException.class,
				() -> Backends.get().entry(blank));
		assertFalse(refused instanceof BackendUnreachableException);
		assertTrue(refused.getMessage(), refused.getMessage().contains("secret"));

		// Settings S3 refuses: a bucket that's blank
		long noBucket = s3Row("https://s3.example.test", "a secret");
		StorageBackend row = StorageBackendDAO.get().findById(noBucket);
		row.setBucket(" ");
		StorageBackendDAO.get().store(row);
		assertThrows(BackendFailingException.class, () -> Backends.get().entry(noBucket));
	}
}
