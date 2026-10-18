package com.logicaldoc.healdan.parser;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import javax.swing.text.Document;
import javax.swing.text.rtf.RTFEditorKit;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.communication.EMail;
import com.logicaldoc.core.communication.EMailAttachment;
import com.logicaldoc.core.communication.MailUtil;
import com.logicaldoc.core.parser.AbstractParser;
import com.logicaldoc.core.parser.ParseParameters;
import com.logicaldoc.healdan.email.EmailFields;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeUtility;

/**
 * Reads an email ({@code eml}, {@code msg}): the From, To, CC, Subject and
 * date headers, the body (an HTML body is stripped to text) and the attachment
 * file names. The attachments' contents are not indexed. A file it can't read
 * yields no text and no exception.
 */
public class EmailParser extends AbstractParser {

	private static final Logger logger = LoggerFactory.getLogger(EmailParser.class);

	@Override
	protected void internalParse(InputStream is, ParseParameters parameters, StringBuilder output) {
		File temp = null;
		try {
			String extension = FilenameUtils.getExtension(StringUtils.defaultString(parameters.getFileName()))
					.toLowerCase(Locale.ROOT);
			// EmailFields and CE's msg reader want a file
			temp = Files.createTempFile("healdan-parse", "." + extension).toFile();
			Files.copy(is, temp.toPath(), StandardCopyOption.REPLACE_EXISTING);

			StringBuilder text = new StringBuilder();
			EmailFields.extract(temp, extension).ifPresent(f -> appendHeaders(text, f));
			appendBody(text, temp, extension);
			output.append(ParsedText.clean(text.toString()));
		} catch (Throwable t) {
			logger.warn("Cannot extract the text of {}: {}", parameters.getFileName(), t.toString());
		} finally {
			if (temp != null)
				temp.delete();
		}
	}

	private void appendHeaders(StringBuilder text, EmailFields f) {
		line(text, "From", f.from());
		line(text, "To", f.to());
		line(text, "Cc", f.cc());
		line(text, "Subject", f.subject());
		if (f.sent() != null)
			line(text, "Date", date(f.sent()));
		if (f.received() != null && !f.received().equals(f.sent()))
			line(text, "Received", date(f.received()));
	}

	private void appendBody(StringBuilder text, File file, String extension) {
		try {
			EMail mail;
			Set<String> names = new LinkedHashSet<>();
			if ("msg".equals(extension))
				mail = MailUtil.msgToMail(file, false);
			else {
				try (InputStream in = new FileInputStream(file)) {
					mail = MailUtil.messageToMail(in, false);
				}
				// CE lists an .eml's attachments only when it also loads their bytes, which we don't need
				try (InputStream in = new FileInputStream(file)) {
					collectNames(MailUtil.readMime(in), names);
				}
			}

			String body = mail.getMessageText();
			if (StringUtils.isNotBlank(body) && !MailUtil.NO_BODY.equals(body)) {
				if (body.startsWith("{\\rtf")) // CE prefers the RTF body of a .msg
					body = rtfToText(body);
				else if (mail.isHtml() || StringUtils.containsIgnoreCase(body, "<html"))
					body = Jsoup.parse(body).wholeText();
				text.append('\n').append(body).append('\n');
			}
			for (EMailAttachment attachment : mail.getAttachments().values())
				names.add(attachment.getFileName());
			for (String name : names)
				line(text, "Attachment", name);
		} catch (Throwable t) {
			logger.warn("Cannot read the body of {}: {}", file.getName(), t.toString());
		}
	}

	private static String rtfToText(String rtf) throws Exception {
		RTFEditorKit kit = new RTFEditorKit();
		Document document = kit.createDefaultDocument();
		kit.read(new ByteArrayInputStream(rtf.getBytes(StandardCharsets.ISO_8859_1)), document, 0);
		return document.getText(0, document.getLength());
	}

	/** The file names of the attachments, at any depth of the MIME tree */
	private void collectNames(Part part, Set<String> names) throws Exception {
		if (part == null)
			return;
		String name = part.getFileName();
		if (StringUtils.isNotBlank(name))
			names.add(MimeUtility.decodeText(name).trim());
		if (part.isMimeType("multipart/*") && part.getContent() instanceof Multipart multipart)
			for (int i = 0; i < multipart.getCount(); i++)
				collectNames(multipart.getBodyPart(i), names);
	}

	private static void line(StringBuilder text, String label, String value) {
		if (StringUtils.isNotBlank(value))
			text.append(label).append(": ").append(value).append('\n');
	}

	private static String date(Date date) {
		return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(date);
	}
}
