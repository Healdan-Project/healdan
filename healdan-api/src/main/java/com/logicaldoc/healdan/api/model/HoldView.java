package com.logicaldoc.healdan.api.model;

import java.util.Date;

/**
 * Who has a document checked out or locked.
 *
 * @param kind {@code checkout} or {@code lock}
 * @param by the user who holds it
 * @param since when they took it, or null if the history doesn't say
 */
public record HoldView(String kind, UserRef by, Date since) {
}
