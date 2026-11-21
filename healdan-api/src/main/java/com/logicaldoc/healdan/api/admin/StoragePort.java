// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.healdan.admin.storage.SecretKeyMissingProblem;
import com.logicaldoc.healdan.admin.storage.StorageAdministration;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendDetail;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.util.spring.Context;

/**
 * The storage port the Admin app's routes read and change storage through (Admin app spec section 9.7): the one
 * {@code healdan-storage} makes, found by type, as {@code healdan-api} can't depend on that module. With none, as on a
 * server without the storage module, the Storage routes answer {@code 503 storage-unavailable}, and the Stores routes
 * answer a Store's storage parts null.
 */
@Component
public class StoragePort {

	/** The {@code 503}'s sentence */
	static final String UNAVAILABLE = "This server has no storage module, so its storage backends can't be read or "
			+ "changed.";

	/** The storage ports, of which there's one with healdan-storage, else none; tests give their own */
	volatile Supplier<List<StorageAdministration>> ports = StorageAdministration::ports;

	/**
	 * @return the bean in the application context
	 */
	public static StoragePort get() {
		return Context.get(StoragePort.class);
	}

	/**
	 * @return the port; empty on a server without the storage module
	 */
	public Optional<StorageAdministration> find() {
		List<StorageAdministration> found = ports.get();
		return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
	}

	/**
	 * @return the port
	 * @throws ApiProblem {@code 503 storage-unavailable} on a server without the storage module
	 */
	public StorageAdministration require() {
		return find().orElseThrow(() -> new ApiProblem(503, "storage-unavailable", UNAVAILABLE));
	}

	/** One call of the port's, which may throw */
	@FunctionalInterface
	interface Call<T> {
		T run() throws PersistenceException;
	}

	/**
	 * Runs a call of the port's, answering what it refuses as the routes do: a missing secret key file as
	 * {@code 409 secret-key-missing}, with the sentence and how many stored values are sealed with it, and any other
	 * refusal, a {@code HealdanRuleException} with the Storage tab's sentence, as {@code 409 refused}, which the
	 * ProblemMapper answers from the cause.
	 *
	 * @param <T> what it returns
	 * @param failure what failed, for the log
	 * @param call the call
	 * @return what it returned
	 */
	static <T> T run(String failure, Call<T> call) {
		try {
			return call.run();
		} catch (SecretKeyMissingProblem e) {
			throw new ApiProblem(409, "secret-key-missing", e.getMessage()).with("sealed", e.sealed());
		} catch (PersistenceException e) {
			throw new IllegalStateException(failure, e);
		}
	}

	/**
	 * @param port the storage port
	 * @param id a backend
	 * @return it, as its page shows it
	 * @throws ApiProblem {@code 404 not-found} for a backend that doesn't exist, and for any while storage backends
	 *         are off, as none is listed then
	 */
	static BackendDetail backend(StorageAdministration port, long id) {
		return run("Can't read storage backend #" + id, () -> port.backend(id)).orElseThrow(() -> noBackend(id));
	}

	/**
	 * What a route asks before it acts on a backend, without counting the backends' files: those a route needs it
	 * reads once, after.
	 *
	 * @param port the storage port
	 * @param id a backend
	 * @throws ApiProblem {@code 404 not-found} as {@link #backend} answers it
	 */
	static void existing(StorageAdministration port, long id) {
		if (!run("Can't read storage backend #" + id, () -> port.exists(id)))
			throw noBackend(id);
	}

	private static ApiProblem noBackend(long id) {
		return ApiProblem.notFound("There's no storage backend %d.".formatted(id));
	}
}
