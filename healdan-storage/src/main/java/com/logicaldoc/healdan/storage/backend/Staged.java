// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.backend;

import java.io.IOException;

/** A copy the mover wrote and verified, which no row points at yet */
public interface Staged {

	/** Local: moved into place. S3: already there. */
	void commit() throws IOException;

	/** Local: deleted. S3: the object deleted (call 20). */
	void discard() throws IOException;
}
