package com.logicaldoc.healdan.rules;

import java.util.Map;
import java.util.Set;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentListener;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.healdan.matter.MatterLocator;

/**
 * Applies the filing rule, and the shortcut rule, to every document that comes
 * into a folder: a new document (upload, API, WebDAV, CMIS, copy, import), a
 * move, a shortcut, or any store that changes the folder (a restore is checked
 * by {@link HealdanHooksImpl} before CE writes anything). Ordinary
 * stores of a document that stays where it is are never vetoed, wherever it
 * is (plan, D3). CE creates it by reflection, hence the public no-arg
 * constructor.
 */
public class RulesDocumentListener implements DocumentListener {

	/**
	 * The events that bring a document into a folder. Not RESTORED: CE's
	 * unarchive stores a document where it is with that event, and
	 * {@code DocumentDAO.restore} is checked by
	 * {@code HealdanHooks.beforeDocumentRestore} before it writes anything.
	 */
	private static final Set<String> ARRIVALS = Set.of(DocumentEvent.MOVED.toString(),
			DocumentEvent.SHORTCUT_STORED.toString());

	public RulesDocumentListener() {
		// Instantiated by CE
	}

	@Override
	public void beforeStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		if (!arrives(document, transaction))
			return;

		Folder folder = document.getFolder();
		try (MatterLocator.Remembering remembering = MatterLocator.remember(dictionary)) {
			Vetoes.check(Vetoes.ids(folder != null ? folder.getId() : null), Vetoes.ids(document.getId()), () -> {
				FilingRules rules = FilingRules.get();
				if (document.getDocRef() != null)
					rules.checkShortcut(folder);
				rules.checkDocumentTarget(folder);
			});
		}
	}

	/**
	 * @return true if the store brings the document into its folder
	 */
	private static boolean arrives(Document document, DocumentHistory transaction) {
		if (document.getId() == 0L)
			return true;
		if (transaction != null && ARRIVALS.contains(transaction.getEvent()))
			return true;
		Long stored = StoredRows.get().documentFolder(document.getId());
		return stored == null || document.getFolder() == null || stored.longValue() != document.getFolder().getId();
	}

	@Override
	public void afterStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		StoredRows.get().stored(document);
	}

	@Override
	public void afterFileStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to check
	}

	@Override
	public void beforeCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// A check-in doesn't move the document
	}

	@Override
	public void afterCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to check
	}

	@Override
	public void afterSaveHistory(Document document, DocumentHistory event, Map<String, Object> dictionary) {
		// Nothing to check
	}
}
