package com.logicaldoc.healdan.parser;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;

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

import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;

/**
 * Reads an email ({@code eml}, {@code msg}): the From, To, CC, Subject and
 * date headers, the body (an HTML body is stripped to text) and the attachment
 * file names. The attachments' contents are not indexed. A file it can't read
 * yields no text and no exception.
 * <p>
 * One deliberate exception to "a failing parser appends nothing": when the
 * headers were read but the body fails, the headers and whatever part of the
 * body was gathered are still appended, because partial text finds more mail
 * than none. Only an {@link Exception} is swallowed; an {@link Error} such as
 * {@code OutOfMemoryError} propagates, and an interruption (the timeout CE puts
 * on a parser) is put back on the thread.
 */
public class EmailParser extends AbstractParser {

	private static final Logger logger = LoggerFactory.getLogger(EmailParser.class);

	@Override
	protected void internalParse(InputStream is, ParseParameters parameters, StringBuilder output) {
		try {
			String extension = FilenameUtils.getExtension(StringUtils.defaultString(parameters.getFileName()))
					.toLowerCase(Locale.ROOT);

			// The email is read once, for its headers, its body and its attachments' names
			StringBuilder text = new StringBuilder();
			if ("msg".equals(extension)) {
				EMail mail = MailUtil.msgToMail(is, false);
				appendHeaders(text, EmailFields.fromMsg(mail));
				appendBody(text, parameters.getFileName(), () -> mail, null);
			} else {
				MimeMessage message = MailUtil.readMime(is);
				if (message == null)
					throw new MessagingException("not a MIME message");
				appendHeaders(text, EmailFields.fromEml(message));
				appendBody(text, parameters.getFileName(), () -> MailUtil.messageToMail(message, false), message);
			}
			output.append(ParsedText.clean(text.toString()));
		} catch (Exception e) {
			Interrupts.keep(e);
			logger.warn("Cannot extract the text of {}: {}", parameters.getFileName(), e.toString());
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

	/**
	 * @param fileName the email's file name, for the log
	 * @param reader gives the email as CE reads it
	 * @param mime the {@code eml}'s MIME message; null for an {@code msg}
	 */
	private void appendBody(StringBuilder text, String fileName, Callable<EMail> reader, Part mime) {
		try {
			EMail mail = reader.call();
			Set<String> names = new LinkedHashSet<>();
			// CE lists an .eml's attachments only when it also loads their bytes, which we don't need
			collectNames(mime, names);

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
		} catch (Exception e) {
			Interrupts.keep(e);
			logger.warn("Cannot read the body of {}: {}", fileName, e.toString());
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
