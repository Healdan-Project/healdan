package com.logicaldoc.healdan.api.model;

import java.util.Date;

/**
 * Who has a document checked out or locked.
 *
 * @param kind {@code checkout} or {@code lock}
 * @param by the user who holds it
 * @param since when they took it, or null if the history doesn't say
 * @param device the computer that holds the check-out, for the user who
 *        holds it alone; null for anyone else, and when no device holds it
 */
public record HoldView(String kind, UserRef by, Date since, DeviceView device) {
}
