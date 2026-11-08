// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

/** Remembers what {@link FolderTreeListener} was told, for {@link FolderTreeListenerTest} */
@Component
public class RecordingTreeListener implements FolderTreeListener {

	/** One call: the folder, its parent and its documents */
	public record Call(long folderId, long parentId, List<Long> documentIds) {
	}

	static final List<Call> CALLS = Collections.synchronizedList(new ArrayList<>());

	@Override
	public void afterTreeRestored(long folderId, long parentId, List<Long> documentIds) {
		CALLS.add(new Call(folderId, parentId, List.copyOf(documentIds)));
	}
}
