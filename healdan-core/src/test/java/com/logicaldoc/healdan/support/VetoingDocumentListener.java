package com.logicaldoc.healdan.support;

import java.util.List;
import java.util.Map;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentListener;

/**
 * A document listener that refuses every save while
 * {@link VetoSwitch#DOCUMENT_BEFORE_STORE} is on, as a Healdan rule would.
 * CE creates it by reflection, from the test plugin's extension.
 */
public class VetoingDocumentListener implements DocumentListener {

	@Override
	public void beforeStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		VetoSwitch.check(VetoSwitch.DOCUMENT_BEFORE_STORE, List.of(), VetoSwitch.ids(document.getId()));
	}

	@Override
	public void afterStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to check
	}

	@Override
	public void afterFileStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to check
	}

	@Override
	public void beforeCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to check
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
