// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import com.logicaldoc.core.store.FSStore;

/**
 * Healdan's {@code Store}, the one bean CE's {@code <bean id="store" class="${store}"/>} makes. For now it is CE's
 * file system store, unchanged. It is not a Spring component: only that bean definition makes it.
 */
public class HealdanStorage extends FSStore {
}
