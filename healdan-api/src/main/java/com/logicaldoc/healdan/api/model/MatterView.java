package com.logicaldoc.healdan.api.model;

import java.util.Date;

/**
 * A matter, as the API shows it (spec section 6.2)
 *
 * @param type always {@code matter}
 * @param id the matter's id
 * @param number the matter number within its client
 * @param display the matter as people read it, such as {@code 998.1 - Demo}
 * @param name the matter's name
 * @param description its description, or null
 * @param client its client
 * @param store the Store it is in
 * @param folderId the matter's workspace folder
 * @param lastChange the caller's latest change in it; null where not known
 */
public record MatterView(String type, long id, long number, String display, String name, String description,
		ClientRef client, StoreRef store, long folderId, Date lastChange) {
}
