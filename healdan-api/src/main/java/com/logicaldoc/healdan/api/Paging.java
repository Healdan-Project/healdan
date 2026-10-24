package com.logicaldoc.healdan.api;

import java.util.List;

import com.logicaldoc.healdan.api.model.Page;

/**
 * A list request's {@code offset} and {@code limit} (spec section 4.5).
 */
public record Paging(int offset, int limit) {

	public static final int DEFAULT_LIMIT = 50;

	public static final int MAX_LIMIT = 200;

	/**
	 * @param offset the {@code offset} query parameter, 0 when missing
	 * @param limit the {@code limit} query parameter, 50 when missing
	 * @return the paging
	 * @throws ApiProblem {@code 400 bad-request} for a negative offset or a
	 *         limit outside 1 to 200
	 */
	public static Paging of(Integer offset, Integer limit) {
		int o = offset != null ? offset : 0;
		int l = limit != null ? limit : DEFAULT_LIMIT;
		if (o < 0)
			throw ApiProblem.badRequest("The offset can't be negative.");
		if (l < 1 || l > MAX_LIMIT)
			throw ApiProblem.badRequest("The limit must be from 1 to %d.".formatted(MAX_LIMIT));
		return new Paging(o, l);
	}

	/**
	 * @param all every item, in order
	 * @return this page of them, with the total
	 */
	public <T> Page<T> slice(List<T> all) {
		int from = Math.min(offset, all.size());
		int to = (int) Math.min((long) offset + limit, all.size());
		return new Page<>(List.copyOf(all.subList(from, to)), offset, limit, (long) all.size());
	}
}
