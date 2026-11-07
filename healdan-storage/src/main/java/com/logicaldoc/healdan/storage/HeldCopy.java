// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.util.Date;

/**
 * One row of {@code hd_storage_held}: a deleted or replaced copy kept until its purge date, so that a delete can be
 * undone.
 *
 * @param backendId the backend that holds it
 * @param location the object key, or the path under the backend's {@code .held/} folder
 * @param docId the document it belonged to
 * @param resource its resource
 * @param held when it was held
 * @param purge when it may be deleted for good
 */
public record HeldCopy(long backendId, String location, long docId, String resource, Date held, Date purge) {
}
