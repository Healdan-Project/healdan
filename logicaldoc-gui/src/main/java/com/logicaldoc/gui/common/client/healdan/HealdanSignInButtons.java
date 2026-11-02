package com.logicaldoc.gui.common.client.healdan;

import java.util.ArrayList;
import java.util.List;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.core.client.JsArray;
import com.google.gwt.core.client.JsonUtils;
import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;
import com.google.gwt.http.client.URL;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.user.client.Window;
import com.smartgwt.client.types.Cursor;
import com.smartgwt.client.util.JSOHelper;
import com.smartgwt.client.widgets.Canvas;
import com.smartgwt.client.widgets.HTMLFlow;
import com.smartgwt.client.widgets.IButton;
import com.smartgwt.client.widgets.layout.LayoutSpacer;
import com.smartgwt.client.widgets.layout.VLayout;

/**
 * The sign-in providers' buttons on the sign-in page (M4 spec section 5.1).
 * {@code LoginPanel} hands its panels to {@link #install}, which asks the server
 * for its providers. With none, the page stays the password form. With some, it
 * gets one button per provider, the password form goes behind a link, and a
 * refusal that sent the person back here (spec section 5.3) shows its message.
 * <p>
 * It lives in the common module because the login module can't see the
 * frontend's Healdan package. English only, like the rest of Healdan's GUI
 * text. The labels come from the server and are shown as text, never as HTML.
 */
public final class HealdanSignInButtons {

	/**
	 * The width of the page's form, {@code LoginPanel.FORM_WIDTH}, which the login
	 * module keeps to itself
	 */
	private static final int FORM_WIDTH = 280;

	/** Where the server lists its providers, from the page's address */
	private static final String SERVER_PATH = "api/v1/server";

	/** Where a provider's sign-in starts, from the page's address */
	private static final String SIGN_IN_PATH = "api/signin/";

	/** The page's parameter that carries a refusal's code */
	private static final String REFUSAL_PARAMETER = "signin";

	private static final String DEFAULT_PROVIDER_LABEL = "Sign In";

	private static final String DEFAULT_PASSWORD_LABEL = "Sign In with a Password";

	private static final String ASK = "Ask an administrator.";

	private static final int TIMEOUT_MILLIS = 10000;

	/** Where the buttons go in the page's panel: after the logo and its spacer */
	private static final int BUTTONS_POSITION = 2;

	private HealdanSignInButtons() {
	}

	/**
	 * Asks the server for its providers. With none, leaves the page as it is. With
	 * some, puts one button per provider above the credentials, folds the
	 * credentials behind the password link, and shows a refusal's message.
	 *
	 * @param inputsPanel the page's panel holding the logo, the credentials and the
	 *        licensing
	 * @param credentialsPanel the page's panel holding the user name, the password
	 *        and the rest of the password form
	 */
	public static void install(VLayout inputsPanel, VLayout credentialsPanel) {
		RequestBuilder request = new RequestBuilder(RequestBuilder.GET, GWT.getHostPageBaseURL() + SERVER_PATH);
		request.setHeader("Accept", "application/json");
		request.setHeader("Cache-Control", "no-cache");
		request.setTimeoutMillis(TIMEOUT_MILLIS);
		try {
			request.sendRequest(null, new RequestCallback() {

				@Override
				public void onResponseReceived(Request request, Response response) {
					if (response.getStatusCode() == Response.SC_OK)
						show(inputsPanel, credentialsPanel, response.getText());
				}

				@Override
				public void onError(Request request, Throwable exception) {
					// No answer: the page stays the password form
				}
			});
		} catch (RequestException e) {
			// The request didn't go: the page stays the password form
		}
	}

	/**
	 * The message for a refusal's code, as the sign-in page words it. It doesn't
	 * name the provider: the page shows which button was clicked, and the
	 * server's done page and the helper word the refusals the same way.
	 *
	 * @param code the code the page came back with ({@code ?signin=}), if any
	 *
	 * @return the message, or null for no code or one the page doesn't know
	 */
	static String refusal(String code) {
		if (code == null)
			return null;

		switch (code) {
		case "no-group":
			return "Your account doesn't give you access to Healdan. " + ASK;
		case "email-taken":
			return "Healdan can't tell which account is yours. " + ASK;
		case "username-taken":
			return "Healdan already has an account with your username. " + ASK;
		case "account":
			return "This account can't sign in now. " + ASK;
		case "provider":
			return "The sign-in provider couldn't sign you in. Try again, or ask an administrator.";
		case "expired":
			return "That sign-in took too long or was already used. Start again.";
		default:
			return null;
		}
	}

	private static void show(VLayout inputsPanel, VLayout credentialsPanel, String json) {
		List<Provider> providers = new ArrayList<>();
		String passwordLabel;
		try {
			passwordLabel = read(json, providers);
		} catch (RuntimeException e) {
			// An answer that isn't what the server sends: the page stays the password form
			return;
		}

		if (providers.isEmpty())
			return;

		// Everything is built before the page changes, so nothing half-done can hide the password form
		VLayout panel = new VLayout();
		panel.setWidth(FORM_WIDTH);
		panel.setMargin(0);
		panel.setMembersMargin(10);

		String message = refusal(Window.Location.getParameter(REFUSAL_PARAMETER));
		HTMLFlow notice = message != null ? notice(message) : null;
		if (notice != null)
			panel.addMember(notice);

		for (Provider provider : providers)
			panel.addMember(button(provider));

		HTMLFlow link = passwordLink(passwordLabel);
		link.addClickHandler(event -> showPasswordForm(panel, credentialsPanel, link));
		link.addKeyPressHandler(event -> {
			String key = event.getKeyName();
			if ("Enter".equalsIgnoreCase(key) || "Space".equalsIgnoreCase(key))
				showPasswordForm(panel, credentialsPanel, link);
		});
		panel.addMember(link);

		inputsPanel.addMember(panel, BUTTONS_POSITION);
		credentialsPanel.setVisible(false);
		if (notice != null)
			fitWhenFontsLoad(notice);
	}

	/**
	 * Measures the canvas again once the page's web fonts have loaded. The
	 * server's answer can come before the page's font does, and a notice drawn
	 * then is measured in the browser's narrower fallback font: in the page's
	 * font it wraps onto a line it wasn't given, under the first button.
	 */
	private static native void fitWhenFontsLoad(Canvas canvas) /*-{
		var fonts = $doc.fonts;
		if (fonts && fonts.ready)
			fonts.ready.then($entry(function() {
				canvas.@com.smartgwt.client.widgets.Canvas::adjustForContent(Z)(true);
			}));
	}-*/;

	/**
	 * Reads the providers and the password link's label from the server's answer.
	 *
	 * @param json what {@code GET /api/v1/server} answered
	 * @param providers where the providers go, in the order of their buttons
	 *
	 * @return the password link's label
	 */
	private static String read(String json, List<Provider> providers) {
		// Despite its name, GWT 2.12's safeEval is JSON.parse: it never evaluates the text
		JavaScriptObject server = JsonUtils.safeEval(json);
		if (server == null)
			return DEFAULT_PASSWORD_LABEL;

		JavaScriptObject list = JSOHelper.getAttributeAsJavaScriptObject(server, "providers");
		if (list != null) {
			JsArray<JavaScriptObject> items = list.cast();
			for (int i = 0; i < items.length(); i++) {
				JavaScriptObject item = items.get(i);
				String id = item != null ? JSOHelper.getAttribute(item, "id") : null;
				if (isBlank(id))
					continue;
				String label = JSOHelper.getAttribute(item, "label");
				providers.add(new Provider(id, isBlank(label) ? DEFAULT_PROVIDER_LABEL : label));
			}
		}

		String passwordLabel = JSOHelper.getAttribute(server, "passwordLabel");
		return isBlank(passwordLabel) ? DEFAULT_PASSWORD_LABEL : passwordLabel;
	}

	private static boolean isBlank(String text) {
		return text == null || text.trim().isEmpty();
	}

	/**
	 * A refusal's message, above the buttons
	 */
	private static HTMLFlow notice(String message) {
		HTMLFlow notice = new HTMLFlow("<div role='alert' style='color: #b00020; font-size: 14px; line-height: 20px; "
				+ "text-align: center'>" + SafeHtmlUtils.htmlEscape(message) + "</div>");
		notice.setWidth100();
		return notice;
	}

	/**
	 * A provider's button, which leaves this page for the provider's sign-in
	 */
	private static IButton button(Provider provider) {
		IButton button = new IButton(SafeHtmlUtils.htmlEscape(provider.label));
		button.setBaseStyle("btn");
		button.setWidth(FORM_WIDTH);
		button.setHeight(34);
		button.addClickHandler(event -> Window.Location
				.assign(GWT.getHostPageBaseURL() + SIGN_IN_PATH + URL.encodePathSegment(provider.id)));
		return button;
	}

	/**
	 * The link that shows the password form. It can take the keyboard's focus, as
	 * the page's other links are anchors and this one isn't.
	 */
	private static HTMLFlow passwordLink(String label) {
		HTMLFlow link = new HTMLFlow("<div style='text-align: center; font-size: 13px; color: #0b5dab; "
				+ "text-decoration: underline'>" + SafeHtmlUtils.htmlEscape(label) + "</div>");
		link.setWidth100();
		link.setCursor(Cursor.HAND);
		link.setCanFocus(true);
		return link;
	}

	private static void showPasswordForm(VLayout panel, VLayout credentialsPanel, HTMLFlow link) {
		credentialsPanel.setVisible(true);
		// The link kept the last button off the form. In its place a spacer does, with the panel's margin above it.
		LayoutSpacer gap = new LayoutSpacer();
		gap.setHeight(1);
		panel.replaceMember(link, gap);
	}

	/**
	 * A provider as the server lists it
	 */
	private static final class Provider {

		private final String id;

		private final String label;

		private Provider(String id, String label) {
			this.id = id;
			this.label = label;
		}
	}
}
