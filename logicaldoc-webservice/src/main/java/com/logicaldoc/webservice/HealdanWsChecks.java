package com.logicaldoc.webservice;

import java.util.Collection;

import com.logicaldoc.core.HealdanTreeChecks;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.authorization.PermissionException;
import com.logicaldoc.core.security.user.User;

/**
 * Healdan: the permission checks CE's web services lacked where the call's
 * signature declares no {@code PermissionException} (M1 plan, "Follow-ups
 * found while closing the gaps", R78 and R79; principle 4). They run core's
 * {@link HealdanTreeChecks}, which the GUI's RPCs share, and refuse with a
 * {@link WebserviceException}, as CE's own group check does. The calls that
 * declare a {@code PermissionException} use CE's
 * {@link AbstractService#checkDocumentPermission} or {@code HealdanTreeChecks}
 * directly.
 */
public final class HealdanWsChecks {

    private HealdanWsChecks() {
    }

    /**
     * Refuses unless the user has every one of the permissions on every
     * document that exists; missing documents are left to the call, as CE
     * leaves them ({@link HealdanTreeChecks#existingDocuments})
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
        try {
            HealdanTreeChecks.existingDocuments(user, docIds, permissions);
        } catch (PermissionException e) {
            throw new WebserviceException(e.getMessage());
        }
    }
}
