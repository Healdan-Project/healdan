package com.logicaldoc.webservice;

import java.util.Collection;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.user.User;

/**
 * Healdan: the permission checks CE's web services lacked where the call's
 * signature declares no {@code PermissionException} (M1 plan, "Follow-ups
 * found while closing the gaps", R78 and R79; principle 4). They refuse with a
 * {@link WebserviceException}, as CE's own group check does. The calls that
 * declare a {@code PermissionException} use CE's
 * {@link AbstractService#checkDocumentPermission} or core's
 * {@code HealdanTreeChecks} instead.
 * <p>
 * A document's permissions are its effective ones: the entries of its own ACL
 * for the user's groups, else its folder's. Admins keep CE's bypass, which the
 * DAO gives them.
 */
public final class HealdanWsChecks {

    private HealdanWsChecks() {
    }

    /**
     * Refuses unless the user has every one of the permissions on every
     * document that exists; missing documents are left to the call, as CE
     * leaves them
     *
     * @param user the user
     * @param docIds the documents, each checked as it is, a shortcut on itself
     * @param permissions the permissions needed
     *
     * @throws PersistenceException error at data layer
     * @throws WebserviceException the refusal
     */
    public static void documents(User user, Collection<Long> docIds, Permission... permissions)
            throws PersistenceException, WebserviceException {
        DocumentDAO dao = DocumentDAO.get();
        for (Long docId : docIds) {
            if (docId == null || dao.findById(docId) == null)
                continue;
            for (Permission permission : permissions)
                if (!dao.isPermissionAllowed(permission, docId, user.getId()))
                    throw new WebserviceException("User %s doesn't have permission %s on document %d"
                            .formatted(user.getUsername(), permission.name(), docId));
        }
    }
}
