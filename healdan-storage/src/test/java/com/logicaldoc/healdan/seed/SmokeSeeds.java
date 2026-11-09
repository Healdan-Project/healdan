// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.seed;

import java.util.List;

import com.logicaldoc.util.spring.Context;

/**
 * Lets the storage tests run the smoke fixture and the seed runner, whose seams are package-private in
 * {@code healdan-core}.
 */
public final class SmokeSeeds {

	private SmokeSeeds() {
	}

	/** @return the smoke fixture's step, switched on */
	public static SeedStep fixture() {
		return new SmokeFixtureSeed(() -> "true");
	}

	/** Runs the steps whose markers are missing, in {@code SeedRunner}'s order, as a start does */
	public static void runPending(SeedStep... steps) {
		Context.get(SeedRunner.class).runPending(List.of(steps));
	}
}
