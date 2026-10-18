package com.logicaldoc.healdan.navigation;

import java.util.Date;

/**
 * A line of My Matters or Recent Matters in the left pane (spec 8.2).
 *
 * @param matterId the matter
 * @param folderId the matter's workspace folder, which a click opens in the
 *        Browser tree
 * @param display how the matter reads, such as {@code 998.1 – Healdan Demo}
 * @param lastChange for Recent Matters, the user's latest change in the
 *        matter; null in My Matters
 */
public record MatterEntry(long matterId, long folderId, String display, Date lastChange) {
}
