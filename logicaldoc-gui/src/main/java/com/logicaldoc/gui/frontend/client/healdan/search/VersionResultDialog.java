package com.logicaldoc.gui.frontend.client.healdan.search;

import java.util.List;

import com.google.gwt.i18n.client.DateTimeFormat;
import com.logicaldoc.gui.common.client.DefaultAsyncCallback;
import com.logicaldoc.gui.common.client.beans.GUIVersion;
import com.logicaldoc.gui.common.client.preview.PreviewPopup;
import com.logicaldoc.gui.common.client.util.Util;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIDocNumberResult;
import com.logicaldoc.gui.frontend.client.services.DocumentService;
import com.smartgwt.client.types.HeaderControls;
import com.smartgwt.client.widgets.Button;
import com.smartgwt.client.widgets.Label;
import com.smartgwt.client.widgets.Window;
import com.smartgwt.client.widgets.layout.HLayout;

/**
 * What a doc # lookup of a version shows (spec 6.5): "#n version v", its date
 * and its user, with Preview and Download
 */
public class VersionResultDialog extends Window {

	private static final DateTimeFormat DATE = DateTimeFormat.getFormat("yyyy-MM-dd HH:mm");

	private final GUIDocNumberResult result;

	/**
	 * Loads the version's date and user, then opens the dialog
	 *
	 * @param result the lookup result; must have a docId and a versionId
	 */
	public static void open(GUIDocNumberResult result) {
		DocumentService.Instance.get().getVersionsById(result.getVersionId(), result.getVersionId(),
				new DefaultAsyncCallback<>() {
					@Override
					public void handleSuccess(List<GUIVersion> versions) {
						GUIVersion version = versions == null || versions.isEmpty() ? null : versions.get(0);
						new VersionResultDialog(result, version).show();
					}
				});
	}

	private VersionResultDialog(GUIDocNumberResult result, GUIVersion version) {
		this.result = result;

		setHeaderControls(HeaderControls.HEADER_LABEL, HeaderControls.CLOSE_BUTTON);
		setTitle("#" + result.getDocNumber() + " version " + result.getVersion());
		setIsModal(true);
		setShowModalMask(true);
		centerInPage();
		setAutoSize(true);
		setWidth(340);
		setPadding(10);

		String when = version != null && version.getVersionDate() != null ? DATE.format(version.getVersionDate())
				: "";
		String who = version != null && version.getUsername() != null ? version.getUsername() : "";

		Label details = new Label(when + (when.isEmpty() || who.isEmpty() ? "" : " &middot; ") + who);
		details.setHeight(22);

		Button preview = new Button("Preview");
		preview.addClickHandler(event -> preview(version));
		Button download = new Button("Download");
		download.addClickHandler(event -> download());

		HLayout buttons = new HLayout(8);
		buttons.setHeight(30);
		buttons.setMembers(preview, download);

		addItem(details);
		addItem(buttons);
	}

	private void preview(GUIVersion loaded) {
		GUIVersion version = new GUIVersion();
		if (loaded != null) {
			version.setFileName(loaded.getFileName());
			version.setFileSize(loaded.getFileSize());
			version.setType(loaded.getType());
		}
		// The preview takes the document's id from the id, as VersionsPanel does
		version.setId(result.getDocId());
		version.setDocId(result.getDocId());
		version.setVersion(result.getVersion());
		version.setFileVersion(result.getFileVersion());
		new PreviewPopup(version).show();
	}

	private void download() {
		Util.download(Util.downloadURL(result.getDocId(), result.getFileVersion()));
	}
}
