// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Bookmark;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.security.authorization.PermissionException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.api.model.DocumentView;
import com.logicaldoc.healdan.api.model.FavoriteRename;
import com.logicaldoc.healdan.api.model.FavoriteView;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.healdan.api.model.FolderView;
import com.logicaldoc.healdan.api.model.HoldView;
import com.logicaldoc.healdan.api.model.NewFavorite;
import com.logicaldoc.healdan.api.model.Page;
import com.logicaldoc.healdan.api.model.UserView;
import com.logicaldoc.healdan.listing.DocumentAuthors;
import com.logicaldoc.healdan.listing.DocumentAuthors.Author;
import com.logicaldoc.healdan.navigation.Favorites;
import com.logicaldoc.healdan.navigation.MyMatters;
import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.healdan.util.BasicPlane;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * {@code GET /v1/me}: who the token signs in as (spec section 6.1), and
 * {@code GET /v1/me/checkouts}: the documents the caller has checked out
 * (section 6.4), and the caller's Favorites under {@code /v1/me/favorites}
 * (section 9.3; their names, M6 spec section 6.1), and My Matters' adding and
 * removing under
 * {@code /v1/me/matters} (M6 spec section 9.1).
 */
@Component
@Path("/v1/me")
@Produces(MediaType.APPLICATION_JSON)
public class MeResource {

	@GET
	public UserView me() {
		return ApiViews.user(Caller.current().user());
	}

	/**
	 * @return the documents the caller has checked out and can still read,
	 *         the latest check-out first
	 */
	@GET
	@Path("checkouts")
	public Page<DocumentView> checkouts() {
		User caller = Caller.current().user();
		try {
			// The partial documents the DAO returns are only good for their ids
			List<Document> documents = new ArrayList<>();
			for (Document partial : DocumentDAO.get().findByLockUserAndStatus(caller.getId(),
					DocumentStatus.CHECKEDOUT)) {
				Document document = DocumentDAO.get().findById(partial.getId());
				if (AccessChecks.readableDocument(document, caller).isPresent() && ApiViews.shows(document))
					documents.add(document);
			}
			// The Authors in one query, not one a row
			Map<Long, Author> authors = ApiViews.authors(documents);
			List<DocumentView> views = new ArrayList<>();
			for (Document document : documents)
				views.add(ApiViews.document(document, caller, authors));
			views.sort(Comparator.comparing(MeResource::since, Comparator.nullsLast(Comparator.reverseOrder()))
					.thenComparing(Comparator.comparingLong(DocumentView::docNumber).reversed()));
			return Page.whole(views);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't list the check-outs", e);
		}
	}

	private static Date since(DocumentView view) {
		HoldView hold = view.hold();
		return hold == null ? null : hold.since();
	}

	/** The longest name a favorite keeps ({@code ld_bookmark.ld_title}) */
	static final int MAX_FAVORITE_NAME = 255;

	/**
	 * @return the caller's bookmarks other than My Matters, folders first and
	 *         then by name, leaving out what the caller can't read or the API
	 *         doesn't show
	 */
	@GET
	@Path("favorites")
	public Page<FavoriteView> favorites() {
		User caller = Caller.current().user();
		try {
			List<Bookmark> bookmarks = Favorites.get().list(caller.getId());
			// The documents' Authors in one query, not one a favorite
			Map<Long, Author> authors = DocumentAuthors.get().of(bookmarks.stream()
					.filter(b -> b.getType() != Bookmark.Type.FOLDER).map(Bookmark::getTargetId).toList());
			List<FavoriteView> views = new ArrayList<>();
			for (Bookmark bookmark : bookmarks) {
				FavoriteView view = view(bookmark, caller, authors);
				if (view != null)
					views.add(view);
			}
			return Page.whole(views);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't list the favorites", e);
		}
	}

	/**
	 * Adds a folder or a document to the caller's Favorites; one already
	 * there stays as it is.
	 *
	 * @param body exactly one of {@code folderId} and {@code docNumber}
	 * @return {@code 201} with the favorite
	 */
	@POST
	@Path("favorites")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response addFavorite(NewFavorite body) {
		// As M2 answers a body that doesn't say what it needs to: 400
		if (body == null || (body.folderId() == null) == (body.docNumber() == null))
			throw ApiProblem.badRequest("Say what to add with folderId or docNumber, one of them.");
		User caller = Caller.current().user();
		try {
			Bookmark bookmark;
			if (body.folderId() != null) {
				Folder folder = AccessChecks.readableFolder(body.folderId(), caller)
						.orElseThrow(() -> ApiProblem.notFound("No folder " + body.folderId()));
				FolderView view = ApiViews.folder(folder, caller)
						.orElseThrow(() -> ApiProblem.notFound("No folder " + body.folderId()));
				if ("matter".equals(view.kind()))
					throw ApiProblem.badRequest("A matter goes in My Matters, not in Favorites.");
				bookmark = Favorites.get().addFolder(caller.getId(), folder.getId());
			} else {
				Document document = DocumentResource.readable(body.docNumber(), caller);
				bookmark = Favorites.get().addDocument(caller.getId(), document.getId());
			}
			FavoriteView view = view(bookmark, caller,
					bookmark.getType() == Bookmark.Type.FOLDER ? Map.of()
							: DocumentAuthors.get().of(List.of(bookmark.getTargetId())));
			if (view == null)
				throw new IllegalStateException("The new favorite can't be shown");
			return Response.status(Response.Status.CREATED).entity(view).build();
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't add the favorite", e);
		}
	}

	/**
	 * Removes one of the caller's Favorites.
	 *
	 * @param id the bookmark's id
	 * @return {@code 204}, or {@code 404} if the caller has no such favorite
	 */
	@DELETE
	@Path("favorites/{id: [0-9]+}")
	public Response removeFavorite(@PathParam("id") long id) {
		User caller = Caller.current().user();
		try {
			// Through the list, so another user's bookmark and a matter's answer like none
			if (Favorites.get().list(caller.getId()).stream().noneMatch(b -> b.getId() == id))
				throw ApiProblem.notFound("You have no favorite with this id.");
			Favorites.get().remove(caller.getId(), id);
			return Response.noContent().build();
		} catch (PermissionException e) {
			throw ApiProblem.notFound("You have no favorite with this id.");
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't remove the favorite", e);
		}
	}

	/**
	 * Gives one of the caller's Favorites a name of its own, or puts back its
	 * item's (M6 spec section 6.1, changed after M6: the owner's dogfooding
	 * punch list, 2026-11-14). Only the caller sees it, and CE's Bookmarks
	 * section shows the same name.
	 *
	 * @param id the bookmark's id
	 * @param body the name; null or blank for the folder's or document's own
	 * @return the favorite, renamed; {@code 404} if the caller has no such
	 *         favorite, or can no longer read its item; {@code 422 validation}
	 *         for a name over 255 characters, or with a control character or
	 *         one the database can't store
	 */
	@PATCH
	@Path("favorites/{id: [0-9]+}")
	@Consumes(MediaType.APPLICATION_JSON)
	public FavoriteView renameFavorite(@PathParam("id") long id, FavoriteRename body) {
		// A missing name is a mistake, not a request for the item's own: that is null
		if (body == null || body.name() == null || !(body.name().isTextual() || body.name().isNull()))
			throw ApiProblem.badRequest("Say what to call the favorite with name, or send null for its own name.");
		String requested = body.name().isNull() ? null : body.name().textValue();
		User caller = Caller.current().user();
		try {
			// Through the list, so another user's bookmark and a matter's answer like none, as a remove does
			Bookmark bookmark = Favorites.get().list(caller.getId()).stream().filter(b -> b.getId() == id).findFirst()
					.orElseThrow(() -> ApiProblem.notFound("You have no favorite with this id."));
			Map<Long, Author> authors = bookmark.getType() == Bookmark.Type.FOLDER ? Map.of()
					: DocumentAuthors.get().of(List.of(bookmark.getTargetId()));
			// What the list leaves out can't be renamed either
			if (view(bookmark, caller, authors) == null)
				throw ApiProblem.notFound("You have no favorite with this id.");
			Bookmark renamed = Favorites.get().rename(caller.getId(), id, favoriteName(requested));
			FavoriteView view = view(renamed, caller, authors);
			if (view == null)
				throw ApiProblem.notFound("You have no favorite with this id.");
			return view;
		} catch (PermissionException e) {
			throw ApiProblem.notFound("You have no favorite with this id.");
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't rename the favorite", e);
		}
	}

	/**
	 * @param requested the name sent
	 * @return the name stripped of the spaces around it; null when there's
	 *         nothing else, for the item's own name
	 * @throws ApiProblem {@code 422 validation} for the field {@code name}:
	 *         {@code invalid} for a control character or one the database
	 *         can't store, such as an emoji, and {@code too-long} over 255
	 *         characters
	 */
	static String favoriteName(String requested) {
		String name = StringUtils.strip(requested);
		if (StringUtils.isEmpty(name))
			return null;
		// A label, not a file name: a / or a \ is fine, as in "Smith / Jones"
		List<FieldError> errors = new ArrayList<>();
		if (name.chars().anyMatch(Character::isISOControl))
			errors.add(new FieldError("name", "invalid", "A name can't contain control characters."));
		// CE's tables hold only the Basic Multilingual Plane, and strict MariaDB refuses the rest at the write
		else if (!BasicPlane.holds(name))
			errors.add(new FieldError("name", "invalid",
					"A name can't contain emoji or other characters the database can't store."));
		if (name.length() > MAX_FAVORITE_NAME)
			errors.add(new FieldError("name", Uploads.TOO_LONG,
					"A name can't be longer than %d characters.".formatted(MAX_FAVORITE_NAME)));
		if (!errors.isEmpty())
			throw ApiProblem.validation(errors);
		return name;
	}

	/**
	 * Adds a matter to the caller's My Matters; one already there stays as it
	 * is.
	 *
	 * @param folderId the matter's workspace folder
	 * @return {@code 204}, or {@code 404} if the folder isn't a matter the
	 *         caller can read
	 */
	@PUT
	@Path("matters/{folderId: [0-9]+}")
	public Response addMatter(@PathParam("folderId") long folderId) {
		User caller = Caller.current().user();
		try {
			// A folder that is no matter, one the caller can't read and one that isn't there answer alike
			Optional<Folder> folder = AccessChecks.readableFolder(folderId, caller);
			Optional<FolderView> view = folder.isPresent() ? ApiViews.folder(folder.get(), caller) : Optional.empty();
			if (view.isEmpty() || !"matter".equals(view.get().kind()))
				throw ApiProblem.notFound("No matter " + folderId);
			MyMatters.get().add(caller.getId(), folderId);
			return Response.noContent().build();
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't add the matter", e);
		}
	}

	/**
	 * Removes a matter from the caller's My Matters.
	 *
	 * @param folderId the matter's workspace folder
	 * @return {@code 204}, also when it wasn't there
	 */
	@DELETE
	@Path("matters/{folderId: [0-9]+}")
	public Response removeMatter(@PathParam("folderId") long folderId) {
		try {
			MyMatters.get().remove(Caller.current().user().getId(), folderId);
			return Response.noContent().build();
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't remove the matter", e);
		}
	}

	/**
	 * @param authors the Authors of the favorites' documents, by id, read at once
	 * @return the bookmark's view, or null if its target can't be read or isn't shown
	 */
	private static FavoriteView view(Bookmark bookmark, User caller, Map<Long, Author> authors)
			throws PersistenceException {
		if (bookmark.getType() == Bookmark.Type.FOLDER) {
			Optional<Folder> folder = AccessChecks.readableFolder(bookmark.getTargetId(), caller);
			if (folder.isEmpty())
				return null;
			return ApiViews.folder(folder.get(), caller)
					.map(v -> new FavoriteView(bookmark.getId(), "folder", name(bookmark, v.name()), v)).orElse(null);
		}
		Optional<Document> document = AccessChecks.readableDocument(DocumentDAO.get().findById(bookmark.getTargetId()),
				caller);
		if (document.isEmpty() || !ApiViews.shows(document.get()))
			return null;
		DocumentView item = ApiViews.document(document.get(), caller, authors);
		return new FavoriteView(bookmark.getId(), "document", name(bookmark, item.name()), item);
	}

	/**
	 * @return the bookmark's title, or the item's name when the title is blank,
	 *         as the Classic Interface can leave it
	 */
	private static String name(Bookmark bookmark, String itemName) {
		return StringUtils.isBlank(bookmark.getTitle()) ? itemName : bookmark.getTitle();
	}

}
