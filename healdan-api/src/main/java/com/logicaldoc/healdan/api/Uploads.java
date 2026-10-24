package com.logicaldoc.healdan.api;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang.StringUtils;
import org.apache.cxf.attachment.AttachmentDataSource;
import org.apache.cxf.jaxrs.ext.multipart.Attachment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.util.spring.Context;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;

/**
 * Files uploaded in a multipart request, and the rules the old UI's upload
 * applies to them (spec correction 12): at most {@code <tenant>.upload.maxsize}
 * MB, and no extension listed in {@code <tenant>.upload.disallow}. Both are
 * read on every request, so they follow the settings. {@link UploadFilter}
 * bounds what CXF reads, and lets go of CXF's copies of the parts.
 */
public final class Uploads {

	/** CE's default when {@code upload.maxsize} isn't set, as in its UploadServlet */
	private static final long DEFAULT_MAX_MEGABYTES = 10L;

	private static final long MEGABYTE = 1024L * 1024L;

	private static final String CONTENT_DISPOSITION = "Content-Disposition";

	private static final Logger log = LoggerFactory.getLogger(Uploads.class);

	private Uploads() {
	}

	/**
	 * Copies a part's content to a temporary file. The caller deletes it.
	 *
	 * @param file the part
	 * @return the copy
	 * @throws IOException if the part can't be read or the copy written; no
	 *         file is left behind then
	 */
	public static File toTempFile(Attachment file) throws IOException {
		File temp = File.createTempFile("healdan-upload-", ".tmp");
		try (InputStream in = file.getDataHandler().getInputStream()) {
			Files.copy(in, temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
			return temp;
		} catch (IOException | RuntimeException e) {
			Files.deleteIfExists(temp.toPath());
			throw e;
		}
	}

	/**
	 * Lets go of a part CXF read. CXF keeps a part over 1 MB in a temporary
	 * file, and deletes it once the part's content has been read and closed;
	 * this closes it, read or not.
	 *
	 * @param part the part's content
	 */
	static void release(DataHandler part) {
		DataSource source = part.getDataSource();
		if (source instanceof AttachmentDataSource cached)
			cached.release();
		try {
			InputStream in = source.getInputStream();
			if (in != null)
				in.close();
		} catch (IOException | RuntimeException e) {
			log.warn("Can't let go of an upload part", e);
		}
	}

	/**
	 * @param file the part
	 * @return the file name its {@code Content-Disposition} gives: RFC 5987's
	 *         {@code filename*} when it has one that can be read, otherwise
	 *         {@code filename}; null when it gives none
	 */
	public static String fileName(Attachment file) {
		String header = file.getHeader(CONTENT_DISPOSITION);
		if (header == null)
			return null;
		String plain = null;
		String extended = null;
		for (String[] parameter : parameters(header)) {
			if ("filename*".equals(parameter[0]) && extended == null)
				extended = decodeExtended(parameter[1]);
			else if ("filename".equals(parameter[0]) && plain == null)
				plain = utf8IfItIs(parameter[1]);
		}
		return StringUtils.defaultIfEmpty(extended, StringUtils.defaultIfEmpty(plain, null));
	}

	/**
	 * Checks the name a new document is filed under.
	 *
	 * @param name the name
	 * @param field the request field it came from, for the errors
	 * @param errors where to add what's wrong: {@code required} when there's
	 *        no name; {@code invalid} when it has a {@code /}, a {@code \} or a
	 *        control character; {@code extension} when it doesn't end with an
	 *        extension; {@code disallowed} when its extension is listed in
	 *        {@code upload.disallow}, in any case
	 */
	public static void checkName(String name, String field, List<FieldError> errors) {
		if (StringUtils.isBlank(name)) {
			errors.add(new FieldError(field, "required", "Give the document a name, or send the file with one."));
			return;
		}
		if (name.chars().anyMatch(c -> c == '/' || c == '\\' || Character.isISOControl(c)))
			errors.add(new FieldError(field, "invalid", "A name can't contain /, \\ or control characters."));
		String extension = extension(name);
		if (extension.isEmpty())
			errors.add(new FieldError(field, "extension", "The name must end with an extension, such as .docx."));
		else if (disallowed().contains(extension.toLowerCase(Locale.ROOT)))
			errors.add(new FieldError(field, "disallowed",
					"Files ending in .%s can't be uploaded.".formatted(extension.toLowerCase(Locale.ROOT))));
	}

	/**
	 * @param name a file name
	 * @return what follows its last dot, as it is written; empty when there's
	 *         no dot, nothing before it or nothing after it
	 */
	public static String extension(String name) {
		int dot = name.lastIndexOf('.');
		return dot <= 0 ? "" : name.substring(dot + 1);
	}

	/**
	 * @param file an uploaded file
	 * @param field the request field it came from, for the error
	 * @return the error for an empty file, which CE can't store; null if it
	 *         isn't empty
	 */
	public static FieldError emptyError(File file, String field) {
		return file.length() == 0L ? new FieldError(field, "empty", "The file is empty.") : null;
	}

	/**
	 * @return the largest file the API takes now, in bytes
	 */
	public static long maxBytes() {
		return maxMegabytes() * MEGABYTE;
	}

	/**
	 * @return the problem for a file over the limit
	 */
	public static ApiProblem tooLarge() {
		return new ApiProblem(413, "too-large",
				"The file is larger than the upload limit of %d MB.".formatted(maxMegabytes()));
	}

	private static long maxMegabytes() {
		return Context.get().getConfig().getLong(Tenant.DEFAULT_NAME + ".upload.maxsize", DEFAULT_MAX_MEGABYTES);
	}

	/**
	 * @return the extensions listed in {@code upload.disallow}, in lower case,
	 *         as the old UI's upload reads them
	 */
	private static List<String> disallowed() {
		String setting = Context.get().getConfig().getString(Tenant.DEFAULT_NAME + ".upload.disallow");
		if (StringUtils.isBlank(setting))
			return List.of();
		return Arrays.asList(setting.toLowerCase(Locale.ROOT).replace(" ", "").split(","));
	}

	/**
	 * @return a header's parameters after its first value, as name (in lower
	 *         case) and value, with quoted values unquoted
	 */
	private static List<String[]> parameters(String header) {
		List<String[]> parameters = new ArrayList<>();
		int i = header.indexOf(';');
		while (i >= 0 && i < header.length()) {
			int equals = header.indexOf('=', i + 1);
			if (equals < 0)
				break;
			int next = header.indexOf(';', i + 1);
			if (next >= 0 && next < equals) {
				// A parameter without a value
				i = next;
				continue;
			}
			String name = header.substring(i + 1, equals).trim().toLowerCase(Locale.ROOT);
			StringBuilder value = new StringBuilder();
			int j = equals + 1;
			while (j < header.length() && header.charAt(j) == ' ')
				j++;
			if (j < header.length() && header.charAt(j) == '"') {
				// A quoted string, in which a backslash escapes the next character
				for (j++; j < header.length() && header.charAt(j) != '"'; j++) {
					if (header.charAt(j) == '\\' && j + 1 < header.length())
						j++;
					value.append(header.charAt(j));
				}
				j = header.indexOf(';', j);
			} else {
				int end = header.indexOf(';', j);
				value.append(header.substring(j, end < 0 ? header.length() : end).trim());
				j = end;
			}
			parameters.add(new String[] { name, value.toString() });
			i = j;
		}
		return parameters;
	}

	/**
	 * @param value an RFC 5987 value, {@code charset'language'percent-encoded}
	 * @return the text, or null if it can't be read
	 */
	private static String decodeExtended(String value) {
		int first = value.indexOf('\'');
		int second = first < 0 ? -1 : value.indexOf('\'', first + 1);
		if (second < 0)
			return null;
		String charset = value.substring(0, first).trim();
		if (!"UTF-8".equalsIgnoreCase(charset) && !"ISO-8859-1".equalsIgnoreCase(charset))
			return null;
		String encoded = value.substring(second + 1);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		for (int i = 0; i < encoded.length(); i++) {
			char c = encoded.charAt(i);
			if (c == '%') {
				if (i + 2 >= encoded.length())
					return null;
				int high = hexDigit(encoded.charAt(i + 1));
				int low = hexDigit(encoded.charAt(i + 2));
				if (high < 0 || low < 0)
					return null;
				bytes.write(high * 16 + low);
				i += 2;
			} else if (c < 0x80) {
				bytes.write(c);
			} else {
				return null;
			}
		}
		return decode(bytes.toByteArray(), Charset.forName(charset));
	}

	/**
	 * Browsers send a file name that isn't ASCII as UTF-8 in a plain
	 * {@code filename}, and the part's header may have been read as ISO-8859-1.
	 *
	 * @return the name read as UTF-8 if its characters are bytes that make
	 *         UTF-8 with something beyond ASCII; otherwise the name as it is
	 */
	private static String utf8IfItIs(String name) {
		if (name.chars().allMatch(c -> c < 0x80) || name.chars().anyMatch(c -> c > 0xff))
			return name;
		String utf8 = decode(name.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
		return utf8 != null ? utf8 : name;
	}

	private static int hexDigit(char c) {
		return c < 0x80 ? Character.digit(c, 16) : -1;
	}

	private static String decode(byte[] bytes, Charset charset) {
		try {
			return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException e) {
			return null;
		}
	}
}
