// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.FieldRefusal;
import com.logicaldoc.healdan.admin.Page;
import com.logicaldoc.healdan.admin.index.ReindexJob.State;
import com.logicaldoc.healdan.admin.index.ReindexPreview.Match;
import com.logicaldoc.healdan.admin.index.ReindexPreview.Status;
import com.logicaldoc.healdan.admin.index.ReindexScope.Kind;

/**
 * Previewing, starting, listing and cancelling reindex jobs (Admin app spec sections 8.9 and 9.6; rulings P6-R2 to
 * P6-R9 and P6-R16): what each scope covers, what it leaves out, the refusals, the estimate and the records.
 */
public class ReindexServiceTest extends AbstractReindexTestCase {

	private Document a;

	private Document b;

	private Document c;

	private Document d;

	/** Pleadings' shortcut to a, a skipped, an archived and a busy document, and one in the Trash */
	private void layout() throws Exception {
		a = document(charterDocuments, "a.txt");
		b = document(pleadings, "b.txt");
		c = document(leaseDocuments, "c.txt");
		d = document(tribunalDocuments, "d.txt");
		fx.legacyShortcut(a, pleadings);
		status(document(pleadings, "skipped.txt"), IndexingStatus.SKIP);
		Document archived = document(pleadings, "archived.txt");
		jdbc.update("update ld_document set ld_status = ? where ld_id = ?", DocumentStatus.ARCHIVED.ordinal(),
				archived.getId());
		inTransaction(document(charterDocuments, "busy.txt"), "tx-busy");
		Document deleted = document(charterDocuments, "deleted.txt");
		DocumentDAO.get().delete(deleted.getId(), fx.documentHistory(admin));
	}

	@Test
	public void testEachScopeCoversItsLiveDocuments() throws Exception {
		layout();

		// Pleadings: b, and its shortcut, skipped and archived documents left out
		ReindexPreview folder = service.preview(ReindexScope.folder(pleadings.getId()), admin);
		assertEquals(1, folder.count());
		assertEquals(3, folder.skipped());
		assertEquals("Folder Pleadings in 1001.1 Charter Dispute", folder.name());
		assertNull(folder.documents());
		assertNull(folder.refusal());

		// The matter's tree: a and b; the busy document left out too, and the one in the Trash not counted at all
		ReindexPreview matter = service.preview(ReindexScope.matter(charter.getId()), admin);
		assertEquals(2, matter.count());
		assertEquals(4, matter.skipped());
		assertEquals("Matter 1001.1 Charter Dispute", matter.name());

		// The Store, its matters included
		ReindexPreview store = service.preview(ReindexScope.store(clientWork.getId()), admin);
		assertEquals(3, store.count());
		assertEquals(4, store.skipped());
		assertEquals("Store Client Work", store.name());

		ReindexPreview everything = service.preview(ReindexScope.everything(false), admin);
		assertEquals(4, everything.count());
		assertEquals(4, everything.skipped());
		assertEquals("Everything", everything.name());

		// Each number matched, left out or not found, in the order given, a repeat left out
		long skipped = numberOf("skipped.txt");
		ReindexPreview documents = service.preview(ReindexScope.documents(
				List.of(number(d), 999_999L, number(a), number(d), skipped, numberOf("deleted.txt"))), admin);
		assertEquals(2, documents.count());
		assertEquals(1, documents.skipped());
		assertEquals("3 Documents", documents.name());
		List<Match> matches = documents.documents();
		assertEquals(List.of(Status.FOUND, Status.NOT_FOUND, Status.FOUND, Status.LEFT_OUT, Status.NOT_FOUND),
				matches.stream().map(Match::status).toList());
		assertEquals(skipped, matches.get(3).docNumber());
		assertEquals("skipped.txt", matches.get(3).fileName());
		Match first = matches.get(0);
		assertEquals(number(d), first.docNumber());
		assertTrue(first.found());
		assertEquals(Long.valueOf(d.getId()), first.id());
		assertEquals("d.txt", first.fileName());
		assertEquals("1002.1", first.matter().number());
		assertEquals(tribunal.getId(), first.matter().id());
		assertEquals("Tribunal", first.matter().name());
		assertFalse(matches.get(1).found());
		assertEquals(999_999L, matches.get(1).docNumber());
		assertNull(matches.get(1).fileName());
		assertNull(matches.get(1).matter());
		assertEquals(Long.valueOf(a.getId()), matches.get(2).id());
		assertEquals("1 Document", service.preview(ReindexScope.documents(List.of(number(a))), admin).name());

		// The preview's count is the job's total, and what it left out its left-out
		ReindexJob job = service.start(ReindexScope.store(clientWork.getId()), admin);
		assertEquals(store.count(), job.total());
		assertEquals(store.skipped(), job.leftOut());
		assertEquals(Set.of(a.getId(), b.getId(), c.getId()), Set.copyOf(documentsOf(job)));
		assertEquals(store.name(), job.name());
		// Now a start would be refused, which the preview says
		assertEquals(ReindexService.ALREADY_REINDEXING,
				service.preview(ReindexScope.store(clientWork.getId()), admin).refusal());
	}

	@Test
	public void testATargetThatIsntLiveIsNotFound() throws Exception {
		Folder gone = fx.folder(pleadings, "Gone");
		FolderDAO.get().delete(gone.getId(), fx.folderHistory(admin));

		assertNull(service.preview(ReindexScope.folder(gone.getId()), admin));
		assertNull(service.preview(ReindexScope.folder(999_999L), admin));
		assertNull(service.start(ReindexScope.folder(gone.getId()), admin));
		assertNull(service.preview(ReindexScope.matter(999_999L), admin));
		// A matter's folder is a folder, not a Store
		assertNull(service.preview(ReindexScope.store(charter.getFolderId()), admin));
		assertNull(service.start(ReindexScope.store(999_999L), admin));
		// An alias is no folder of its own
		Folder alias = fx.legacyAlias(charterDocuments, "Alias", pleadings);
		assertNull(service.preview(ReindexScope.folder(alias.getId()), admin));
		assertEquals(List.of(), records());
	}

	@Test
	public void testTheScopesShape() throws Exception {
		// A member of another kind with a value, and a doc # below 0, are malformed
		assertThrows(IllegalArgumentException.class, () -> new ReindexScope(Kind.EVERYTHING, 1L, null, false));
		assertThrows(IllegalArgumentException.class, () -> new ReindexScope(Kind.MATTER, 1L, List.of(1L), false));
		assertThrows(IllegalArgumentException.class, () -> new ReindexScope(Kind.DOCUMENTS, 1L, List.of(1L), false));
		assertThrows(IllegalArgumentException.class, () -> ReindexScope.documents(List.of(4L, -1L)));
		assertThrows(NullPointerException.class, () -> new ReindexScope(null, null, null, false));

		// The member its kind needs, missing, and too many different doc #s, are the fields'
		for (ReindexScope scope : List.of(new ReindexScope(Kind.FOLDER, null, null, false),
				new ReindexScope(Kind.MATTER, null, null, false), new ReindexScope(Kind.STORE, null, null, false))) {
			FieldRefusal refusal = assertThrows(FieldRefusal.class, () -> service.preview(scope, admin));
			assertEquals("targetId " + FieldRefusal.REQUIRED,
					refusal.fields().get(0).name() + " " + refusal.fields().get(0).code());
		}
		for (List<Long> numbers : java.util.Arrays.<List<Long>>asList(null, List.of())) {
			FieldRefusal refusal = assertThrows(FieldRefusal.class,
					() -> service.start(new ReindexScope(Kind.DOCUMENTS, null, numbers, false), admin));
			assertEquals("docNumbers " + FieldRefusal.REQUIRED,
					refusal.fields().get(0).name() + " " + refusal.fields().get(0).code());
		}
		List<Long> many = new java.util.ArrayList<>(java.util.stream.LongStream.rangeClosed(1, 1001).boxed().toList());
		FieldRefusal tooMany = assertThrows(FieldRefusal.class,
				() -> service.preview(ReindexScope.documents(many), admin));
		assertEquals(ReindexService.TOO_MANY, tooMany.fields().get(0).code());
		assertEquals(ReindexService.TOO_MANY_DOCUMENTS, tooMany.fields().get(0).message());
		// Repeats are left out first
		many.set(1000, 1000L);
		ReindexPreview thousand = service.preview(ReindexScope.documents(many), admin);
		assertEquals(1000, thousand.documents().size());
		assertEquals("0 Documents", thousand.name());
		assertEquals(ReindexService.NOTHING_TO_REINDEX, thousand.refusal());
	}

	@Test
	public void testEmptyFirstOnlyForEverything() throws Exception {
		document(pleadings, "b.txt");
		for (ReindexScope scope : List.of(new ReindexScope(Kind.FOLDER, pleadings.getId(), null, true),
				new ReindexScope(Kind.MATTER, charter.getId(), null, true))) {
			FieldRefusal refusal = assertThrows(FieldRefusal.class, () -> service.start(scope, admin));
			assertEquals("emptyFirst", refusal.fields().get(0).name());
			assertEquals(FieldRefusal.INVALID, refusal.fields().get(0).code());
			assertThrows(FieldRefusal.class, () -> service.preview(scope, admin));
		}
		// The target's 404 comes first
		assertNull(service.start(new ReindexScope(Kind.FOLDER, 999_999L, null, true), admin));

		ReindexJob job = service.start(ReindexScope.everything(true), admin);
		assertTrue(job.emptyFirst());
		assertEquals(State.WAITING, job.state());
		// Only the start is recorded: the refusals changed nothing
		assertEquals(1, records().size());
		assertTrue(records().get(0), records().get(0).contains("\"emptyFirst\":{\"before\":null,\"after\":\"true\"}"));
	}

	@Test
	public void testStartRecordsAndWaitsItsTurn() throws Exception {
		Document b = document(pleadings, "b.txt");
		Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);

		ReindexJob job = service.start(ReindexScope.matter(charter.getId()), admin);

		assertEquals(State.WAITING, job.state());
		assertEquals(Kind.MATTER, job.kind());
		assertEquals(Long.valueOf(charter.getId()), job.targetId());
		assertEquals("Matter 1001.1 Charter Dispute", job.name());
		assertEquals(admin.getId(), job.startedById());
		assertEquals("admin", job.startedByUsername());
		assertEquals(1, job.total());
		assertEquals(0, job.done() + job.failed() + job.skipped());
		assertFalse(job.created().isBefore(before));
		assertNull(job.started());
		assertNull(job.ended());
		assertNull(job.stalledSince());
		assertTrue(job.active());
		assertEquals(List.of(b.getId()), documentsOf(job));
		assertFalse(job.cancelling());
		// About 1.75 seconds a document, with no job done yet
		assertEquals(Long.valueOf(2), job.estimateSecondsLeft());
		assertEquals(job, service.job(job.id(), admin));
		assertEquals(List.of(admin.getId() + " admin null SEARCH_INDEX reindex.started matter " + charter.getId()
				+ " Matter 1001.1 Charter Dispute {\"documents\":{\"before\":null,\"after\":\"1\"},"
				+ "\"emptyFirst\":{\"before\":null,\"after\":\"false\"},\"kind\":{\"before\":null,\"after\":\"matter\"}}"),
				records());

		// A folder's start aims at the folder, the Store's at its folder, and the others at nothing
		service.start(ReindexScope.folder(pleadings.getId()), admin);
		service.start(ReindexScope.store(clientWork.getId()), admin);
		service.start(ReindexScope.documents(List.of(number(b))), admin);
		service.start(ReindexScope.everything(false), admin);
		List<String> events = records();
		assertTrue(events.get(1), events.get(1).contains(" folder " + pleadings.getId() + " Folder Pleadings in"));
		assertTrue(events.get(2), events.get(2).contains(" store " + clientWork.getId() + " Store Client Work "));
		assertTrue(events.get(3), events.get(3).contains(" documents null 1 Document "));
		assertTrue(events.get(4), events.get(4).contains(" everything null Everything "));
	}

	@Test
	public void testTheStartsRefusals() throws Exception {
		// Nothing to try: an empty folder, or one whose only document is skipped
		Folder empty = fx.folder(pleadings, "Empty");
		HealdanRuleException nothing = assertThrows(HealdanRuleException.class,
				() -> service.start(ReindexScope.folder(empty.getId()), admin));
		assertEquals(ReindexService.NOTHING_TO_REINDEX, nothing.getMessage());
		status(document(empty, "skipped.txt"), IndexingStatus.SKIP);
		assertThrows(HealdanRuleException.class, () -> service.start(ReindexScope.folder(empty.getId()), admin));
		assertThrows(HealdanRuleException.class, () -> service.start(ReindexScope.documents(List.of(424242L)), admin));
		assertEquals(0L, (long) jdbc.queryForObject("select count(*) from hd_reindex_job", Long.class));

		// One of the same kind and target as a job that hasn't ended, but documents
		Document b = document(pleadings, "b.txt");
		service.start(ReindexScope.matter(charter.getId()), admin);
		HealdanRuleException again = assertThrows(HealdanRuleException.class,
				() -> service.start(ReindexScope.matter(charter.getId()), admin));
		assertEquals(ReindexService.ALREADY_REINDEXING, again.getMessage());
		service.start(ReindexScope.folder(pleadings.getId()), admin);
		service.start(ReindexScope.documents(List.of(number(b))), admin);
		service.start(ReindexScope.documents(List.of(number(b))), admin);
		service.start(ReindexScope.everything(false), admin);
		assertThrows(HealdanRuleException.class, () -> service.start(ReindexScope.everything(true), admin));
		assertEquals(5, records().size());
	}

	@Test
	public void testOnlyAnAdministrator() throws Exception {
		Document b = document(pleadings, "b.txt");
		ReindexJob job = service.start(ReindexScope.folder(pleadings.getId()), admin);
		User bob = fx.user("bob");

		for (ThrowingRunnable call : List.<ThrowingRunnable>of(
				() -> service.preview(ReindexScope.folder(pleadings.getId()), bob),
				() -> service.start(ReindexScope.documents(List.of(number(b))), bob),
				() -> service.cancel(job.id(), bob), () -> service.job(job.id(), bob),
				() -> service.jobs(0, 10, false, bob),
				// Before the target's 404
				() -> service.preview(ReindexScope.folder(999_999L), bob))) {
			HealdanRuleException refusal = assertThrows(HealdanRuleException.class, call::run);
			assertEquals(ReindexService.ONLY_ADMINS, refusal.getMessage());
		}
		assertEquals(State.WAITING, service.job(job.id(), admin).state());
		assertEquals(1, records().size());
	}

	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	@Test
	public void testCancelAWaitingJob() throws Exception {
		Document b = document(pleadings, "b.txt");
		status(b, IndexingStatus.INDEXED);
		ReindexJob job = service.start(ReindexScope.folder(pleadings.getId()), admin);
		long changes = IndexCounts.get().changes();

		ReindexJob cancelled = service.cancel(job.id(), admin);

		assertEquals(State.CANCELLED, cancelled.state());
		assertNotNull(cancelled.ended());
		assertFalse(cancelled.active());
		assertEquals(List.of(), documentsOf(job));
		assertEquals(IndexingStatus.INDEXED.ordinal(), indexed(b));
		assertTrue(IndexCounts.get().changes() > changes);
		List<String> events = records();
		assertEquals(admin.getId() + " admin null SEARCH_INDEX reindex.cancelled folder " + pleadings.getId()
				+ " Folder Pleadings in 1001.1 Charter Dispute null", events.get(1));
		assertNull(cancelled.estimateSecondsLeft());
		// Nothing to run
		assertFalse(worker.runOnce());

		// Once ended, it can't be cancelled; an unknown job is no job
		HealdanRuleException ended = assertThrows(HealdanRuleException.class, () -> service.cancel(job.id(), admin));
		assertEquals(ReindexService.ALREADY_ENDED, ended.getMessage());
		assertNull(service.cancel(999_999L, admin));
		assertNull(service.job(999_999L, admin));
		assertEquals(2, records().size());

		// The same target can be reindexed again
		assertEquals(State.WAITING, service.start(ReindexScope.folder(pleadings.getId()), admin).state());
	}

	@Test
	public void testTheJobsNewestFirst() throws Exception {
		Document b = document(pleadings, "b.txt");
		ReindexJob first = service.start(ReindexScope.folder(pleadings.getId()), admin);
		ReindexJob second = service.start(ReindexScope.documents(List.of(number(b))), admin);
		ReindexJob third = service.start(ReindexScope.matter(charter.getId()), admin);
		service.cancel(second.id(), admin);

		Page<ReindexJob> all = service.jobs(0, 2, false, admin);
		assertEquals(3, all.total());
		assertEquals(List.of(third.id(), second.id()), all.items().stream().map(ReindexJob::id).toList());
		assertEquals(List.of(first.id()),
				service.jobs(2, 2, false, admin).items().stream().map(ReindexJob::id).toList());

		Page<ReindexJob> active = service.jobs(0, 10, true, admin);
		assertEquals(2, active.total());
		assertEquals(List.of(third.id(), first.id()), active.items().stream().map(ReindexJob::id).toList());
		assertEquals("Admin Admin", all.items().get(0).startedByName());
	}

	@Test
	public void testTheEstimate() throws Exception {
		// With no job done yet, 1.75 seconds a document
		assertNull(service.estimateSeconds(0));
		assertEquals(Long.valueOf(721), service.estimateSeconds(412));
		assertEquals(Long.valueOf(2), service.estimateSeconds(1));

		// The most recent job done that tried 20 at least: 100 seconds over 40 documents tried
		Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		job("DONE", 30, 10, now.minusSeconds(1000), now.minusSeconds(900));
		// Too few to go by, and one that didn't end as done
		job("DONE", 10, 0, now.minusSeconds(100), now.minusSeconds(10));
		job("CANCELLED", 500, 0, now.minusSeconds(100), now.minusSeconds(99));
		assertEquals(Long.valueOf(1030), service.estimateSeconds(412));

		ReindexPreview preview = service.preview(ReindexScope.everything(false), admin);
		assertEquals(0, preview.count());
		assertNull(preview.estimateSeconds());
		assertEquals(ReindexService.NOTHING_TO_REINDEX, preview.refusal());
	}

	@Test
	public void testTheEstimateOfWhatsLeft() {
		Instant now = Instant.now();
		// Running, past 20 tried: its own rate, about 2 seconds over 30 tried, for the 20 left
		ReindexJob running = new ReindexJob(1, Kind.EVERYTHING, null, "Everything", 1, "admin", "Admin Admin",
				State.RUNNING, false, false, 50, 20, 10, 0, 0, now, now.minusSeconds(60), null, null, null);
		long left = ReindexService.withEstimate(running, 9.0).estimateSecondsLeft();
		assertTrue(String.valueOf(left), left >= 40 && left <= 41);
		// Before 20 tried, as a preview at the rate given, or 1.75 seconds each
		ReindexJob early = new ReindexJob(1, Kind.EVERYTHING, null, "Everything", 1, "admin", "Admin Admin",
				State.RUNNING, false, false, 50, 5, 0, 5, 0, now, now.minusSeconds(60), null, null, null);
		assertEquals(Long.valueOf(80), ReindexService.withEstimate(early, 2.0).estimateSecondsLeft());
		assertEquals(Long.valueOf(70), ReindexService.withEstimate(early, null).estimateSecondsLeft());
		// Waiting: its total
		ReindexJob waiting = new ReindexJob(1, Kind.EVERYTHING, null, "Everything", 1, "admin", "Admin Admin",
				State.WAITING, false, false, 4, 0, 0, 0, 0, now, null, null, null, null);
		assertEquals(Long.valueOf(7), ReindexService.withEstimate(waiting, null).estimateSecondsLeft());
		// None while stalled, with nothing left, or once ended
		ReindexJob stalled = new ReindexJob(1, Kind.EVERYTHING, null, "Everything", 1, "admin", "Admin Admin",
				State.RUNNING, false, false, 50, 5, 0, 5, 0, now, now.minusSeconds(60), null, now, null);
		assertNull(ReindexService.withEstimate(stalled, null).estimateSecondsLeft());
		ReindexJob finished = new ReindexJob(1, Kind.EVERYTHING, null, "Everything", 1, "admin", "Admin Admin",
				State.RUNNING, false, false, 50, 50, 0, 0, 0, now, now.minusSeconds(60), null, null, null);
		assertNull(ReindexService.withEstimate(finished, null).estimateSecondsLeft());
		ReindexJob done = new ReindexJob(1, Kind.EVERYTHING, null, "Everything", 1, "admin", "Admin Admin",
				State.DONE, false, false, 50, 40, 0, 0, 0, now, now.minusSeconds(60), now, null, 5L);
		assertNull(ReindexService.withEstimate(done, null).estimateSecondsLeft());
	}

	private void job(String state, long done, long failed, Instant started, Instant ended) {
		jdbc.update("insert into hd_reindex_job (hd_tenantid, hd_kind, hd_name, hd_userid, hd_username, hd_state,"
				+ " hd_cancel, hd_emptyfirst, hd_emptied, hd_total, hd_done, hd_failed, hd_skipped, hd_leftout,"
				+ " hd_position, hd_created, hd_started, hd_ended) values (1, 'EVERYTHING', 'Everything', 1, 'admin', ?,"
				+ " 0, 0, 0, ?, ?, ?, 0, 0, 0, ?, ?, ?)", state, done + failed, done, failed, Timestamp.from(started), Timestamp.from(started),
				Timestamp.from(ended));
	}

	private List<Long> documentsOf(ReindexJob job) {
		return jdbc.queryForList("select hd_docid from hd_reindex_doc where hd_jobid = ? order by hd_docid",
				Long.class, job.id());
	}
}
