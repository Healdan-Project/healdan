package com.logicaldoc.healdan.signin;

/**
 * One row of a provider's group map: a person who has the value in the
 * provider's groups claim is put in the Healdan group (M4, spec section 8).
 * 
 * @param value a value of the provider's groups claim
 * @param groupId the id of the Healdan group
 */
public record GroupMapping(String value, long groupId) {
}
