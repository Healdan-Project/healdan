package com.logicaldoc.healdan.util;

import java.util.Collection;

import org.hibernate.Cache;
import org.hibernate.SessionFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.document.AbstractDocument;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.folder.Folder;

/**
 * Evicts rows from Hibernate's second-level cache after Healdan changed them
 * with JDBC, so that the DAOs see the change.
 */
@Component
public class CacheEvictor {

	private final SessionFactory sessionFactory;

	@Autowired
	public CacheEvictor(@Qualifier("sessionFactory") SessionFactory sessionFactory) {
		this.sessionFactory = sessionFactory;
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

	private void evict(Class<?> entity, Collection<Long> ids) {
		Cache cache = sessionFactory.getCache();
		if (cache == null || ids == null || ids.isEmpty())
			return;

		for (Long id : ids)
			cache.evictEntityData(entity, id);

		String prefix = entity.getName() + ".";
		String parentPrefix = entity == Document.class ? AbstractDocument.class.getName() + "." : prefix;
		((SessionFactoryImplementor) sessionFactory).getMappingMetamodel().forEachCollectionDescriptor(collection -> {
			String role = collection.getRole();
			if (role.startsWith(prefix) || role.startsWith(parentPrefix))
				for (Long id : ids)
					cache.evictCollectionData(role, id);
		});
	}
}
