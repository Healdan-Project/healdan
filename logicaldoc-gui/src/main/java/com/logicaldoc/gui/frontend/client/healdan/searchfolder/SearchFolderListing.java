package com.logicaldoc.gui.frontend.client.healdan.searchfolder;

import java.util.List;

import com.logicaldoc.gui.common.client.DefaultAsyncCallback;
import com.logicaldoc.gui.common.client.beans.GUIFolder;
import com.logicaldoc.gui.common.client.data.DocumentsDS;
import com.logicaldoc.gui.frontend.client.document.grid.DocumentsGrid;
import com.logicaldoc.gui.frontend.client.healdan.HealdanGuiConstants;
import com.logicaldoc.gui.frontend.client.healdan.services.HealdanSearchFolderService;

/**
 * Lists a search folder's documents in the documents grid: the server runs
 * the folder's rule, and the grid loads the documents by id (spec 8.3)
 */
public final class SearchFolderListing {

	private SearchFolderListing() {
	}

	/**
	 * Loads the folder's documents into the grid, if it is a search folder
	 *
	 * @param grid the documents grid
	 * @param folder the folder being opened
	 *
	 * @return true if the folder is a search folder, so the grid's ordinary
	 *         listing must not run
	 */
	public static boolean load(DocumentsGrid grid, GUIFolder folder) {
		if (folder == null || folder.getType() != HealdanGuiConstants.FOLDER_TYPE_SEARCH)
			return false;

		/*
		 * The grid now belongs to the search folder, as updateData does for any
		 * other folder, so a sort never refetches the previous folder's documents
		 */
		grid.loadGridLayout(folder);
		HealdanSearchFolderService.Instance.get().evaluate(folder.getId(), new DefaultAsyncCallback<>() {
			@Override
			public void handleSuccess(List<Long> ids) {
				StringBuilder joined = new StringBuilder();
				for (Long id : ids) {
					if (joined.length() > 0)
						joined.append(",");
					joined.append(id);
				}
				// An empty docIds would list nothing at all, as wanted, but the servlet needs a value
				grid.fetchNewData(new DocumentsDS(ids.isEmpty() ? "empty" : joined.toString()));
				/*
				 * Every id is in the grid already, so the cursor shows one page: with
				 * more, a sort would refetch the folder's own (empty) listing
				 */
				if (grid.getGridCursor() != null)
					grid.getGridCursor()
							.setTotalRecords(Math.min(ids.size(), grid.getGridCursor().getPageSize()));
			}
		});
		grid.setCanDrag(false);
		return true;
	}
}
