package com.logicaldoc.healdan.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.hibernate.Cache;
import org.hibernate.SessionFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.logicaldoc.core.document.AbstractDocument;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.util.spring.Context;

/**
 * Evicts rows from Hibernate's second-level cache after Healdan changed them
 * with JDBC, so that the DAOs see the change.
 * <p>
 * It also cleans up after a veto. The cache isn't transactional (Infinispan,
 * transaction mode NONE): a row CE writes with JDBC and then loads, as document
 * and folder restore do, is cached at load time, and a rollback doesn't take
 * it out. Every Healdan veto therefore calls {@link #evictFoldersOnRollback}
 * or {@link #evictDocumentsOnRollback} with the ids it refuses (plan, D3).
 */
@Component
public class CacheEvictor {

	private final SessionFactory sessionFactory;

	/** Each entity's collection roles, fixed once the session factory exists */
	private final Map<Class<?>, List<String>> collectionRoles = new ConcurrentHashMap<>();

	@Autowired
	public CacheEvictor(@Qualifier("sessionFactory") SessionFactory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

	/**
	 * @return the bean
	 */
	public static CacheEvictor get() {
		return Context.get(CacheEvictor.class);
	}

	/**
	 * Evicts folders and their collections (ACL, tags, attributes...).
	 * 
	 * @param ids the folder ids
	 */
	public void evictFolders(Collection<Long> ids) {
		evict(Folder.class, ids);
	}

	/**
	 * Evicts documents and their collections (ACL, tags, attributes...).
	 * 
	 * @param ids the document ids
	 */
	public void evictDocuments(Collection<Long> ids) {
		evict(Document.class, ids);
	}

	/**
	 * Evicts folders if the current transaction rolls back, e.g. because a
	 * Healdan rule is refusing the operation that loaded them. Outside a
	 * transaction it evicts them at once.
	 * 
	 * @param ids the folder ids
	 */
	public void evictFoldersOnRollback(Collection<Long> ids) {
		onRollback(Folder.class, ids);
	}

	/**
	 * Evicts documents if the current transaction rolls back, e.g. because a
	 * Healdan rule is refusing the operation that loaded them. Outside a
	 * transaction it evicts them at once.
	 * 
	 * @param ids the document ids
	 */
	public void evictDocumentsOnRollback(Collection<Long> ids) {
		onRollback(Document.class, ids);
	}

	/**
	 * Evicts folders once the current transaction ends, however it ends: for
	 * rows CE changes with JDBC later in the transaction, which an entity
	 * loaded meanwhile would hide. Outside a transaction it evicts them at
	 * once.
	 *
	 * @param ids the folder ids
	 */
	public void evictFoldersAfterCompletion(Collection<Long> ids) {
		afterCompletion(Folder.class, ids, true);
	}

	/**
	 * Evicts documents once the current transaction ends, however it ends (see
	 * {@link #evictFoldersAfterCompletion}). Outside a transaction it evicts
	 * them at once.
	 *
	 * @param ids the document ids
	 */
	public void evictDocumentsAfterCompletion(Collection<Long> ids) {
		afterCompletion(Document.class, ids, true);
	}

	private void onRollback(Class<?> entity, Collection<Long> ids) {
		afterCompletion(entity, ids, false);
	}

	private void afterCompletion(Class<?> entity, Collection<Long> ids, boolean alsoOnCommit) {
		if (ids == null || ids.isEmpty())
			return;
		List<Long> copy = List.copyOf(ids);
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			evict(entity, copy);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (alsoOnCommit || status != STATUS_COMMITTED)
					evict(entity, copy);
			}
		});
	}

	private void evict(Class<?> entity, Collection<Long> ids) {
		Cache cache = sessionFactory.getCache();
		if (cache == null || ids == null || ids.isEmpty())
			return;

		for (Long id : ids)
			cache.evictEntityData(entity, id);

		for (String role : collectionRoles.computeIfAbsent(entity, this::findCollectionRoles))
			for (Long id : ids)
				cache.evictCollectionData(role, id);
	}

	private List<String> findCollectionRoles(Class<?> entity) {
		String prefix = entity.getName() + ".";
		String parentPrefix = entity == Document.class ? AbstractDocument.class.getName() + "." : prefix;
		List<String> roles = new ArrayList<>();
		((SessionFactoryImplementor) sessionFactory).getMappingMetamodel().forEachCollectionDescriptor(collection -> {
			String role = collection.getRole();
			if (role.startsWith(prefix) || role.startsWith(parentPrefix))
				roles.add(role);
		});
		return List.copyOf(roles);
	}
}
