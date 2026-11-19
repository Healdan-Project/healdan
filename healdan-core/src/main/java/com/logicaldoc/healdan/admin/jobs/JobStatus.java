// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.jobs;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import org.springframework.stereotype.Component;

import com.logicaldoc.util.spring.Context;

/**
 * What each of Healdan's background jobs last did (Admin app spec section
 * 9.5): its last start and end, how it ended, its error and its next run, as
 * the job reports them. A job calls {@link #started} before a run, then
 * {@link #finished} or {@link #failed} after it, from its own thread, and
 * {@link #scheduled} when it knows its first run before making it.
 * <p>
 * It is in memory only, so it starts again with the server, and is safe from
 * any thread: each job's state is an immutable {@link Report}, replaced as a
 * whole. Reporting never throws, so it can't change what a job does: a
 * failure inside is ignored, the report lost.
 */
@Component
public class JobStatus {

	/** The most characters an error keeps */
	public static final int ERROR_LIMIT = 1000;

	/** What reports go to when the application context can't be reached, which nothing reads */
	private static final JobStatus DETACHED = new JobStatus();

	private final Map<String, Report> reports = new ConcurrentHashMap<>();

	/**
	 * @return the bean, or, where there's none to be had, as in a unit test with
	 *         no application context, an instance nothing reads; never throws
	 */
	public static JobStatus get() {
		try {
			JobStatus bean = Context.get(JobStatus.class);
			return bean != null ? bean : DETACHED;
		} catch (RuntimeException e) {
			return DETACHED;
		}
	}

	/**
	 * A run of a job begins now. Its last end and result stay those of the run
	 * before, until this one ends.
	 *
	 * @param name the job's name
	 */
	public void started(String name) {
		update(name, report -> new Report(Instant.now(), report.lastEnd(), report.result(), report.error(),
				report.nextRun(), true));
	}

	/**
	 * The job's run ended now, as it should.
	 *
	 * @param name the job's name
	 * @param next when it runs next, null if it doesn't or that isn't known
	 */
	public void finished(String name, Instant next) {
		update(name, report -> new Report(report.lastStart(), Instant.now(), JobView.SUCCEEDED, null, next, false));
	}

	/**
	 * The job's run failed now. The error is the exception's class and message,
	 * never its stack or its causes: a job whose messages can hold what it
	 * never logs gives its text with {@link #failed(String, String, Instant)}
	 * instead.
	 *
	 * @param name the job's name
	 * @param cause what it failed with
	 * @param next when it runs next, null if it doesn't or that isn't known
	 */
	public void failed(String name, Throwable cause, Instant next) {
		String error = null;
		try {
			error = describe(cause);
		} catch (RuntimeException e) {
			// A message that can't be read: the failure is reported without it
		}
		failed(name, error, next);
	}

	/**
	 * The job's run failed now, with the error the job gives: what it would log
	 * of the failure at warn.
	 *
	 * @param name the job's name
	 * @param error the error, cut to {@link #ERROR_LIMIT} characters; null for
	 *        none
	 * @param next when it runs next, null if it doesn't or that isn't known
	 */
	public void failed(String name, String error, Instant next) {
		update(name, report -> new Report(report.lastStart(), Instant.now(), JobView.FAILED, capped(error), next,
				false));
	}

	/**
	 * The job will next run at a time, though it hasn't run since the server
	 * started, or a run's report didn't say: the rest of its state stays.
	 *
	 * @param name the job's name
	 * @param next when it runs next
	 */
	public void scheduled(String name, Instant next) {
		update(name, report -> new Report(report.lastStart(), report.lastEnd(), report.result(), report.error(), next,
				report.running()));
	}

	/**
	 * @param name the job's name
	 * @return what it reported, an empty report if it reported nothing
	 */
	Report report(String name) {
		Report report = name != null ? reports.get(name) : null;
		return report != null ? report : Report.NONE;
	}

	private void update(String name, UnaryOperator<Report> change) {
		if (name == null)
			return;
		try {
			reports.compute(name, (key, report) -> change.apply(report != null ? report : Report.NONE));
		} catch (RuntimeException e) {
			// Reporting never changes what the job does
		}
	}

	/** @return the exception's class, with its message if it has one; null for none */
	private static String describe(Throwable cause) {
		if (cause == null)
			return null;
		String type = cause.getClass().getSimpleName();
		if (type.isEmpty())
			type = cause.getClass().getName();
		String message = cause.getMessage();
		return message == null || message.isBlank() ? type : type + ": " + message;
	}

	/**
	 * @return the text cut to {@link #ERROR_LIMIT} characters, never between
	 *         the two halves of a character beyond the Basic Multilingual Plane;
	 *         null for null
	 */
	static String capped(String text) {
		if (text == null || text.length() <= ERROR_LIMIT)
			return text;
		int end = Character.isHighSurrogate(text.charAt(ERROR_LIMIT - 1)) ? ERROR_LIMIT - 1 : ERROR_LIMIT;
		return text.substring(0, end);
	}

	/**
	 * A job's state, as it last reported it
	 *
	 * @param lastStart when its last run began, null if none has since the
	 *        server started
	 * @param lastEnd when its last run that ended did, null if none
	 * @param result {@link JobView#SUCCEEDED} or {@link JobView#FAILED}, of the
	 *        last run that ended; null if none
	 * @param error what that run failed with, null if it didn't
	 * @param nextRun when it runs next, null if it doesn't or that isn't known
	 * @param running whether a run has begun and not ended
	 */
	record Report(Instant lastStart, Instant lastEnd, String result, String error, Instant nextRun, boolean running) {

		/** Nothing reported */
		static final Report NONE = new Report(null, null, null, null, null, false);
	}
}
