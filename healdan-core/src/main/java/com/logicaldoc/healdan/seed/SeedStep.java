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
	 * Does the step's work, as the system user.
	 * 
	 * @throws Exception if the work fails; startup then fails
	 */
	void run() throws Exception;
}
