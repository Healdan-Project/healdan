// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.communication.EMail;

import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;

/**
 * An email's body made safe to show in a page of its own (M6 spec §9.4). The
 * server cleans it, never the app: a frame's page inherits the policy of the app
 * that shows it, so letting the sender's pictures in would have loosened the
 * app's own.
 * <p>
 * What is left is what jsoup's relaxed safelist keeps (no script, no event
 * handler, no form, no {@code object} or {@code embed}, no {@code iframe}, no
 * {@code base}, no meta refresh, no link that isn't {@code http}, {@code https}
 * or {@code mailto}), plus the styling emails depend on, scrubbed of anything
 * that fetches: a {@code style} attribute or element, and {@code class}, with
 * every {@code url()}, {@code @import} and escape taken out of the CSS. Links
 * open in a new window without an opener.
 * <p>
 * Pictures the message carries ({@code cid:}) are embedded as {@code data:}
 * addresses, up to {@link #INLINE_LIMIT} bytes in all; past that, or for one the
 * message doesn't carry, a placeholder stands in. Pictures from the internet
 * ({@code https:}) are removed unless asked for, so that the sender's trackers
 * don't learn that the message was opened. {@link #policy} is the page's
 * Content-Security-Policy, which blocks them as well, should the cleaning fail.
 * <p>
 * Nothing logged here names a subject, an address or a message's content.
 */
public final class EmailBody {

	private static final Logger log = LoggerFactory.getLogger(EmailBody.class);

	/** Bytes of inline pictures embedded in one page */
	public static final int INLINE_LIMIT = 2 * 1024 * 1024;

	/** The shortest policy that lets the page show: no script (sandbox), no fetch but the pictures asked for */
	private static final String POLICY = "sandbox allow-popups allow-popups-to-escape-sandbox; default-src 'none'; "
			+ "style-src 'unsafe-inline'; img-src data:%s; font-src data:; frame-ancestors 'self'";

	/** What stands in for a picture that can't be shown */
	static final String PLACEHOLDER = "[Picture not shown]";

	/** What a message with nothing to read shows */
	static final String NO_BODY = "(This message has no readable body.)";

	/** The page's own look: a white page, so a sender's colours read as meant in either theme */
	private static final String PAGE_STYLE = "body{margin:12px;background:#fff;color:#222;"
			+ "font:14px/1.45 system-ui,-apple-system,'Segoe UI',sans-serif;overflow-wrap:anywhere}"
			+ "img{max-width:100%;height:auto}pre{margin:0;white-space:pre-wrap;font:inherit}"
			+ "table{max-width:100%}.healdan-placeholder{color:#666;font-style:italic}";

	private static final int MAX_DEPTH = 32;

	private static final Pattern CSS_URL = Pattern.compile("(?i)url\\s*\\(\\s*(['\"]?)(.*?)\\1\\s*\\)");

	/** Rules that fetch, which go whole */
	private static final Pattern CSS_FETCH = Pattern.compile("(?is)@import[^;{}]*;?|@charset[^;{}]*;?|@font-face\\s*\\{[^}]*\\}");

	/** What can't be told from a script or a fetch, so the whole CSS goes */
	private static final Pattern CSS_DANGER = Pattern.compile("(?i)expression\\s*\\(|javascript:|-moz-binding"
			+ "|behavior\\s*:|image-set\\s*\\(|\\bsrc\\s*\\(");

	private static final Pattern IMAGE_TYPE = Pattern.compile("image/[a-z0-9][a-z0-9.+-]*");

	private EmailBody() {
	}

	/**
	 * @param pictures true when pictures from the internet are allowed
	 * @return the page's Content-Security-Policy
	 */
	public static String policy(boolean pictures) {
		return POLICY.formatted(pictures ? " https:" : "");
	}

	/**
	 * @param message an {@code eml}
	 * @param pictures true to keep pictures from the internet
	 * @return the cleaned page: the message's HTML body, or its text escaped in
	 *         a {@code pre}
	 */
	public static String clean(MimeMessage message, boolean pictures) {
		Bodies bodies = new Bodies();
		try {
			bodies.visit(message, 0);
			if (bodies.html != null)
				return page(html(text(bodies.html)), bodies.images, pictures);
			if (bodies.plain != null)
				return page(plain(text(bodies.plain)), bodies.images, pictures);
		} catch (IOException | MessagingException | RuntimeException e) {
			// The class only: a parser's message can hold addresses
			log.warn("Cannot read an email's body: {}", e.getClass().getName());
			log.debug("Cannot read an email's body", e);
		}
		return page(plain(NO_BODY), Map.of(), pictures);
	}

	/**
	 * @param mail an {@code msg} read by CE's {@code MailUtil.msgToMail}. CE's
	 *        reader keeps no Content-IDs, so a {@code cid:} picture in its HTML
	 *        shows as a placeholder
	 * @param pictures true to keep pictures from the internet
	 * @return the cleaned page, as {@link #clean(MimeMessage, boolean)}
	 */
	public static String clean(EMail mail, boolean pictures) {
		String text = mail.getMessageText();
		if (text == null || text.isBlank())
			return page(plain(NO_BODY), Map.of(), pictures);
		return page(mail.isHtml() ? html(text) : plain(text), Map.of(), pictures);
	}

	/** The body parts of a message and the pictures it carries, found in one walk */
	private static final class Bodies {

		jakarta.mail.Part html;

		jakarta.mail.Part plain;

		/** Pictures by Content-ID: lower case, without brackets */
		final Map<String, jakarta.mail.Part> images = new HashMap<>();

		void visit(jakarta.mail.Part part, int depth) throws MessagingException, IOException {
			if (depth > MAX_DEPTH)
				throw new MessagingException("Nested too deeply");
			if (part.isMimeType("multipart/signed")) {
				// The content is the first part, and the signature the second
				Multipart signed = multipart(part);
				if (signed.getCount() > 0)
					visit(signed.getBodyPart(0), depth + 1);
			} else if (part.isMimeType("multipart/*")) {
				Multipart multipart = multipart(part);
				for (int i = 0; i < multipart.getCount(); i++)
					visit(multipart.getBodyPart(i), depth + 1);
			} else if (part.isMimeType("message/rfc822")) {
				// An attached message isn't this one's body
				return;
			} else {
				String id = contentId(part);
				if (id != null && part.isMimeType("image/*"))
					images.putIfAbsent(id, part);
				else if (!attachment(part) && part.isMimeType("text/html") && html == null)
					html = part;
				else if (!attachment(part) && part.isMimeType("text/plain") && plain == null)
					plain = part;
			}
		}

		private static boolean attachment(jakarta.mail.Part part) throws MessagingException {
			return jakarta.mail.Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition());
		}

		private static Multipart multipart(jakarta.mail.Part part) throws MessagingException, IOException {
			if (part.getContent() instanceof Multipart multipart)
				return multipart;
			throw new MessagingException("Not a multipart");
		}
	}

	/** A text part's text; its bytes read as Latin-1 when the JVM lacks its charset */
	private static String text(jakarta.mail.Part part) throws MessagingException, IOException {
		try {
			if (part.getContent() instanceof String text)
				return text;
		} catch (UnsupportedEncodingException e) {
			// Falls through to the bytes
		}
		try (InputStream in = part.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		}
	}

	private static String contentId(jakarta.mail.Part part) throws MessagingException {
		String[] header = part.getHeader("Content-ID");
		if (header == null || header.length == 0 || header[0] == null)
			return null;
		return id(header[0]);
	}

	/** A Content-ID or a {@code cid:} reference in lower case, without brackets or percent escapes */
	private static String id(String raw) {
		String id = raw.strip();
		try {
			id = URLDecoder.decode(id.replace("+", "%2B"), StandardCharsets.UTF_8).strip();
		} catch (IllegalArgumentException e) {
			// Kept as it was
		}
		if (id.startsWith("<") && id.endsWith(">"))
			id = id.substring(1, id.length() - 1).strip();
		return id.isEmpty() ? null : id.toLowerCase(Locale.ROOT);
	}

	/** A page from a part of the body, not yet cleaned: HTML, or the escaped text in a {@code pre} */
	private record Content(String markup, boolean html) {
	}

	private static Content html(String markup) {
		return new Content(markup, true);
	}

	private static Content plain(String text) {
		return new Content(text, false);
	}

	/** Pictures a page embeds, and how many bytes it has used */
	private static final class Embedding {

		final Map<String, jakarta.mail.Part> images;

		final boolean pictures;

		int used;

		Embedding(Map<String, jakarta.mail.Part> images, boolean pictures) {
			this.images = images;
			this.pictures = pictures;
		}

		/** @return the picture as a data: address, null when the message doesn't carry it or it is over the limit */
		String embed(String reference) {
			jakarta.mail.Part part = images.get(id(reference));
			if (part == null)
				return null;
			try {
				String type = part.getContentType();
				int semicolon = type.indexOf(';');
				type = (semicolon < 0 ? type : type.substring(0, semicolon)).strip().toLowerCase(Locale.ROOT);
				if (!IMAGE_TYPE.matcher(type).matches())
					return null;
				byte[] bytes;
				try (InputStream in = part.getInputStream()) {
					bytes = in.readNBytes(INLINE_LIMIT - used + 1);
				}
				if (bytes.length > INLINE_LIMIT - used)
					return null;
				used += bytes.length;
				return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(bytes);
			} catch (MessagingException | IOException | RuntimeException e) {
				log.debug("Cannot embed a picture", e);
				return null;
			}
		}
	}

	private static String page(Content content, Map<String, jakarta.mail.Part> images, boolean pictures) {
		Element body;
		if (content.html()) {
			body = clean(content.markup(), new Embedding(images, pictures));
		} else {
			body = new Element("body");
			body.appendElement("pre").text(content.markup());
		}
		Document page = Document.createShell("");
		page.outputSettings().prettyPrint(false);
		page.head().appendElement("meta").attr("charset", "UTF-8");
		page.head().appendElement("style").appendText(PAGE_STYLE);
		page.body().replaceWith(body);
		return "<!DOCTYPE html>\n" + page.html();
	}

	private static Element clean(String markup, Embedding embedding) {
		Document dirty = Jsoup.parse(markup);
		// The cleaner reads the body alone, and a newsletter's styles are in its head
		for (Element style : dirty.head().select("style"))
			dirty.body().prependChild(style);
		Document cleaned = new Cleaner(safelist()).clean(dirty);
		Element body = cleaned.body();

		for (Element style : body.select("style")) {
			String css = scrub(style.data().isEmpty() ? style.text() : style.data(), embedding.pictures);
			style.empty().appendChild(new DataNode(css));
		}
		for (Element styled : body.select("[style]"))
			styled.attr("style", scrub(styled.attr("style"), embedding.pictures));

		for (Element link : body.select("a[href]")) {
			link.attr("target", "_blank");
			link.attr("rel", "noopener noreferrer");
		}
		for (Element picture : body.select("img"))
			picture(picture, embedding);
		return body;
	}

	/** Relaxed, with the styling and the markup emails depend on, and the picture addresses {@link #picture} reads */
	private static Safelist safelist() {
		Safelist safelist = Safelist.relaxed();
		safelist.addTags("style", "font", "center", "hr", "s", "del", "ins", "abbr", "address", "big", "mark");
		safelist.addAttributes(":all", "style", "class", "dir", "lang", "align", "valign", "bgcolor");
		safelist.addAttributes("table", "border", "cellpadding", "cellspacing", "height");
		safelist.addAttributes("td", "height", "nowrap");
		safelist.addAttributes("th", "height", "nowrap");
		safelist.addAttributes("font", "face", "size", "color");
		safelist.addAttributes("img", "border", "hspace", "vspace");
		safelist.addProtocols("img", "src", "cid", "data");
		return safelist;
	}

	/**
	 * The picture's source: a {@code cid:} picture embedded or replaced by a
	 * placeholder, a {@code data:} picture kept, an internet picture kept for
	 * {@code pictures} and its address removed otherwise
	 */
	private static void picture(Element picture, Embedding embedding) {
		String src = picture.attr("src").strip();
		String lower = src.toLowerCase(Locale.ROOT);
		if (lower.startsWith("cid:")) {
			String data = embedding.embed(src.substring(4));
			if (data != null)
				picture.attr("src", data);
			else
				placeholder(picture);
		} else if (lower.startsWith("data:image/")) {
			picture.attr("src", src);
		} else if (lower.startsWith("https://") && embedding.pictures) {
			picture.attr("src", src);
		} else {
			// Internet pictures not asked for, http: pictures, and anything else
			picture.removeAttr("src");
		}
	}

	private static void placeholder(Element picture) {
		Element span = new Element("span").addClass("healdan-placeholder");
		String alt = picture.attr("alt").strip();
		span.text(alt.isEmpty() ? PLACEHOLDER : PLACEHOLDER.substring(0, PLACEHOLDER.length() - 1) + ": " + alt + "]");
		picture.replaceWith(span);
	}

	/**
	 * CSS with nothing that fetches: CSS with an escape is dropped whole,
	 * comments are removed (so no keyword hides in them), {@code @import} and
	 * {@code @font-face} are removed and CSS with {@code expression()} and the
	 * like is dropped whole, and a {@code url()} is kept only for a
	 * {@code data:} picture or, for {@code pictures}, an {@code https:} one
	 */
	static String scrub(String css, boolean pictures) {
		// An escape could spell any keyword, so CSS with one goes whole; comments could split one
		if (css.indexOf('\\') >= 0)
			return "";
		String plain = CSS_FETCH.matcher(css.replaceAll("(?s)/\\*.*?\\*/", "")).replaceAll("");
		if (CSS_DANGER.matcher(plain).find())
			return "";
		Matcher url = CSS_URL.matcher(plain);
		StringBuilder out = new StringBuilder();
		while (url.find()) {
			String target = url.group(2).strip().toLowerCase(Locale.ROOT);
			boolean keep = target.startsWith("data:image/") || (pictures && target.startsWith("https://"));
			url.appendReplacement(out, Matcher.quoteReplacement(keep ? url.group() : "none"));
		}
		url.appendTail(out);
		// No "<": the text sits in a style element, which only a closing tag can end
		return out.toString().replace("<", "");
	}
}
