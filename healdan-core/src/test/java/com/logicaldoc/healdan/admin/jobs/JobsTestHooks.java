// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.jobs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.logicaldoc.core.task.Task;
import com.logicaldoc.core.task.TaskManager;
import com.logicaldoc.util.spring.Context;

/**
 * What other packages' and modules' tests change in the background jobs, through {@link Jobs}' seam: a task of their
 * own, such as a {@link HeldTask}, listed after CE's, and put back with {@link #reset}.
 */
public final class JobsTestHooks {

	private JobsTestHooks() {
	}

	/**
	 * Lists a task after CE's, as one of CE's tasks, until {@link #reset}.
	 *
	 * @param task the task
	 */
	public static void list(Task task) {
		TaskManager manager = Context.get(TaskManager.class);
		Context.get(Jobs.class).tasks = () -> {
			Collection<Task> tasks = new ArrayList<>(manager.getTasks());
			tasks.add(task);
			return List.copyOf(tasks);
		};
	}

	/**
	 * Lists CE's tasks with this one in place of CE's of the same name, such as a {@link HeldTask} for the Indexer.
	 *
	 * @param task the task
	 */
	public static void instead(Task task) {
		TaskManager manager = Context.get(TaskManager.class);
		Context.get(Jobs.class).tasks = () -> manager.getTasks().stream()
				.map(each -> each.getName().equals(task.getName()) ? task : each).toList();
	}

	/**
	 * Lists CE's tasks alone again.
	 */
	public static void reset() {
		TaskManager manager = Context.get(TaskManager.class);
		Context.get(Jobs.class).tasks = manager::getTasks;
	}
}
