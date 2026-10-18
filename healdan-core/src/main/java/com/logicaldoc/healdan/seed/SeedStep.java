package com.logicaldoc.healdan.seed;

/**
 * One step of Healdan's first-start setup. A step's name must be one of those
 * in {@link SeedRunner#ORDER}.
 */
public interface SeedStep {

	/**
	 * @return the step name; the marker {@code seed.<name>} records that it ran
	 */
	String name();

	/**
	 * @return false to leave the step for a later start: the runner then neither
	 *         runs it nor writes its marker
	 */
	default boolean enabled() {
		return true;
	}

	/**
	 * Does the step's work, as the system user.
	 * 
	 * @throws Exception if the work fails; startup then fails
	 */
	void run() throws Exception;
}
