// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.web.service;

import com.logicaldoc.gui.common.client.ServerException;

/**
 * Refuses the Import of the Templates and Attribute sets tabs. Its file is CE's
 * own objects written by Java's bean encoder, a format Healdan doesn't read
 * from uploads. Healdan will have an import of its own
 * (Healdan-Project/healdan#245); the export stays.
 *
 * @since Healdan
 */
public final class HealdanImports {

    /** What a refused import says */
    public static final String REFUSAL = "Healdan doesn't import templates or attribute sets.";

    private HealdanImports() {
    }

    /**
     * Refuses an import before it reads anything
     *
     * @throws ServerException always
     */
    public static void refuse() throws ServerException {
        throw new ServerException(REFUSAL);
    }
}
