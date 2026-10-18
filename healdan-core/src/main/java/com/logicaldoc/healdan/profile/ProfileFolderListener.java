package com.logicaldoc.healdan.profile;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.folder.FolderListener;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.metadata.Template;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.profile.Propagation.Place;
import com.logicaldoc.healdan.profile.Propagation.Value;
import com.logicaldoc.healdan.rules.StoredRows;
import com.logicaldoc.healdan.rules.StoredRows.FolderRow;
import com.logicaldoc.healdan.rules.Vetoes;

/**
 * Stamps the Healdan profile on every folder inside a matter or the templates
 * (spec §6.6, §6.7): the template, locked; the effective values inherited from
 * the folder above; client and matter from the matter. Then passes a changed
 * value down to the folders and documents below ({@link Propagation}), and
 * re-stamps the whole subtree of a folder that moved.
 * <p>
 * The local marks change only on a metadata edit, a store with the event
 * {@code CHANGED} that CE's folder save, the web services' folder update and
 * CMIS use (plan, R20); a create makes local the values that differ from the
 * inherited ones. Every other store (a rename, a permission change, a move)
 * only re-stamps the values that aren't local. A Subclass the user supplies in
 * a create or an edit must belong to the Class, or the store is refused
 * (folder stores are top-level, so that is safe); one inherited is cleared. CE
 * creates it by reflection, hence the public no-arg constructor.
 */
public class ProfileFolderListener implements FolderListener {

	/** Key in the dictionary of a new folder's local attributes */
	static final String LOCALS = "healdan.folderLocals";

	/** Key in the dictionary of what a stored folder held before the store */
	static final String BEFORE = "healdan.folderBefore";

	/**
	 * A stored folder before the store
	 *
	 * @param values its values, as the database held them
	 * @param locals its local marks
	 * @param moved true if the store brings it under another parent
	 * @param edit true if the store is a metadata edit
	 * @param exact false if a query may have flushed the store's changes before
	 *        the values were read
	 */
	private record Before(Map<String, Value> values, Set<String> locals, boolean moved, boolean edit,
			boolean exact) {
	}

	public ProfileFolderListener() {
		// Instantiated by CE
	}

	@Override
	public void beforeStore(Folder folder, FolderHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		try (MatterLocator.Remembering remembering = MatterLocator.remember(dictionary)) {
			stamp(folder, transaction, dictionary);
		}
	}

	private static void stamp(Folder folder, FolderHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		if (folder.getDeleted() != 0 || folder.getFoldRef() != null || folder.getType() == Folder.TYPE_ALIAS)
			return;
		Propagation propagation = Propagation.get();
		Place place = propagation.place(folder.getParentId(), folder.getId());
		if (place == null)
			return;

		Inheritance inheritance = Inheritance.get();
		Template template = ProfileSchema.get().template();
		folder.setTemplate(template);
		folder.setTemplateLocked(true);
		inheritance.addMissingAttributes(folder);

		long parentId = folder.getParentId();
		if (folder.getId() == 0L) {
			Set<String> locals = new HashSet<>();
			Vetoes.check(Vetoes.ids(parentId), List.of(),
					() -> locals.addAll(inheritance.applyOnCreate(folder, parentId)));
			inheritance.stampFixed(folder, place.fixed());
			dictionary.put(LOCALS, locals);
			return;
		}

		long id = folder.getId();
		String event = transaction != null ? transaction.getEvent() : null;
		FolderRow stored = StoredRows.get().folder(id);
		boolean moved = stored == null || stored.parentId() != parentId || stored.deleted() != 0
				|| FolderEvent.MOVED.toString().equals(event) || FolderEvent.RESTORED.toString().equals(event);
		boolean edit = !moved && FolderEvent.CHANGED.toString().equals(event);
		Map<String, Value> values = propagation.stamped(id);
		dictionary.put(BEFORE, new Before(values, LocalValueDAO.get().locals(LocalValueDAO.FOLDER, id), moved, edit,
				!StoredRows.get().inSession(folder)));

		if (edit) {
			// A Class and Subclass the edit leaves as they were are never refused
			boolean unchanged = same(values, folder, HealdanConstants.ATTR_DOCCLASS)
					&& same(values, folder, HealdanConstants.ATTR_DOCSUBCLASS);
			Vetoes.check(Vetoes.ids(id, parentId), List.of(),
					() -> inheritance.applyOnUpdate(folder, LocalValueDAO.FOLDER, id, parentId, !unchanged));
		} else {
			inheritance.restampFolder(folder, parentId, moved);
		}
		inheritance.stampFixed(folder, place.fixed());
	}

	private static boolean same(Map<String, Value> stored, Folder folder, String name) {
		Attribute attribute = folder.getAttribute(name);
		String value = attribute != null ? attribute.getStringValue() : null;
		return Value.text(value).same(stored.getOrDefault(name, Value.BLANK));
	}

	@Override
	public void afterStore(Folder folder, FolderHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		if (dictionary.remove(LOCALS) instanceof Set<?> locals) {
			if (folder.getId() != 0L)
				for (Object attribute : locals)
					LocalValueDAO.get().setLocal(LocalValueDAO.FOLDER, folder.getId(), attribute.toString(), true);
			return;
		}
		if (!(dictionary.remove(BEFORE) instanceof Before before))
			return;

		Propagation propagation = Propagation.get();
		if (before.moved()) {
			propagation.restampSubtree(folder.getId());
			return;
		}

		/*
		 * What passes down: the values the store changed and the marks it
		 * changed. When the values may have been read after the store's changes
		 * were flushed, an edit passes down every local value too; propagation
		 * writes only what differs
		 */
		Map<String, Value> now = propagation.stamped(folder.getId());
		Set<String> localsNow = LocalValueDAO.get().locals(LocalValueDAO.FOLDER, folder.getId());
		Set<String> changed = new HashSet<>();
		for (String name : ProfileSchema.get().inheritable()) {
			Value value = now.getOrDefault(name, Value.BLANK);
			if (!value.same(before.values().getOrDefault(name, Value.BLANK))
					|| before.locals().contains(name) != localsNow.contains(name))
				changed.add(name);
		}
		if (before.edit() && !before.exact()) {
			changed.addAll(before.locals());
			changed.addAll(localsNow);
		}
		propagation.propagate(folder.getId(), changed);
	}
}
