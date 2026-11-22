// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.logs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.logicaldoc.healdan.admin.UrlCredentials;
import com.logicaldoc.healdan.admin.logs.LogChain.Part;
import com.logicaldoc.healdan.util.Texts;

/**
 * Reads entries out of a log's files (Admin app spec section 8.8, ruling P5-R27) a chunk at a time, so neither a
 * file nor a line is ever held whole: of each line it keeps only its first {@link #HEAD_BYTES} bytes, more than
 * {@link LogFiles#MAX_CHARS} characters, and looks through the rest. A read looks through at most its budget, and
 * keeps at most {@link LogFiles#MAX_LINES} entries.
 * <p>
 * A line starts an entry when it starts as the server's logging writes one: its time, level and source, then
 * {@code " -- "} and the message ({@link #ENTRY}). Every other line continues the entry before it, but for the lines
 * at the start of what was read, or of a file, which are each an entry of their own: log4j writes each event whole to
 * one file, so an entry never spans two. A read going back stops at an entry's start, so an entry's lines come
 * together.
 * <p>
 * Going forward it reads one file, up to the size it had when it was opened, and leaves a last line not yet ended.
 * Going back it goes on into the next older file at the start of one.
 */
final class LogReader {

	/** The most bytes of a line it keeps: a character takes at most four */
	static final int HEAD_BYTES = 4 * LogFiles.MAX_CHARS;

	/** How much of a file it reads at once */
	private static final int CHUNK = 64 * 1024;

	/**
	 * A line that starts an entry, as the shipped pattern, {@code %d{dd.MM.yyyy HH:mm:ss,SSS} %-5p (%C:%4L) -- %m%n},
	 * writes one
	 */
	private static final Pattern ENTRY = Pattern.compile(
			"(\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}:\\d{2},\\d{3}) (TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\\s*\\(([^)]*)\\) -- (.*)");

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss,SSS", Locale.ROOT)
			.withResolverStyle(ResolverStyle.STRICT);

	private final LogChain chain;

	private final LogQuery query;

	private final long budget;

	/**
	 * The most lines a read looks at, however short: with the bytes, what bounds what a read holds, as a line costs
	 * more than its bytes
	 */
	static final int MAX_LINES_LOOKED = 50_000;

	/** The bytes looked through so far */
	private long scanned;

	/** The lines looked at so far */
	private int linesLooked;

	/** Decodes the lines' bytes as UTF-8, putting U+FFFD for what isn't */
	private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
			.onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);

	/**
	 * @param chain the log's files
	 * @param query what to keep
	 * @param budget the most bytes a read looks through
	 */
	LogReader(LogChain chain, LogQuery query, long budget) {
		this.chain = chain;
		this.query = query;
		this.budget = budget;
	}

	/**
	 * A line of a file.
	 *
	 * @param start where it starts
	 * @param end just past its line feed, or where it was cut short
	 * @param text its first {@link #HEAD_BYTES} bytes, decoded, without its line feed or a carriage return before it
	 */
	record Line(long start, long end, String text) {
	}

	/** An entry being read: its first line, and the continuation lines after it as they're found */
	private static final class Entry {

		private final long start;

		private final Instant time;

		private final String level;

		private final String source;

		private final String message;

		/** Whether it's a line that starts no entry, an entry of its own */
		private final boolean alone;

		private final List<String> continuation = new ArrayList<>();

		private Entry(long start, Instant time, String level, String source, String message, boolean alone) {
			this.start = start;
			this.time = time;
			this.level = level;
			this.source = source;
			this.message = message;
			this.alone = alone;
		}

		/** @return the entry the line starts; null if it starts none */
		static Entry starting(Line line) {
			Matcher matcher = ENTRY.matcher(line.text());
			if (!matcher.matches())
				return null;
			try {
				Instant time = LocalDateTime.parse(matcher.group(1), TIME).atZone(ZoneId.systemDefault()).toInstant();
				return new Entry(line.start(), time, matcher.group(2), matcher.group(3).replace(" ", ""),
						shown(matcher.group(4)), false);
			} catch (DateTimeParseException e) {
				// Not a time the log writes, such as 31.02
				return null;
			}
		}

		/** @return a line that starts no entry, as an entry of its own */
		static Entry alone(Line line) {
			return new Entry(line.start(), null, null, null, shown(line.text()), true);
		}

		void add(Line line) {
			continuation.add(shown(line.text()));
		}

		/** @return whether the query keeps it */
		boolean kept(LogQuery query) {
			if (!query.level().keeps(level))
				return false;
			String text = query.text();
			if (text == null || message.toLowerCase(Locale.ROOT).contains(text)
					|| (source != null && source.toLowerCase(Locale.ROOT).contains(text)))
				return true;
			for (String line : continuation)
				if (line.toLowerCase(Locale.ROOT).contains(text))
					return true;
			return false;
		}

		LogLine line(LogChain chain, int part) {
			return new LogLine(chain.position(part, start), time, level, source, message, continuation);
		}
	}

	/**
	 * @return a line's text as it's answered: any URL's credentials taken out, then cut to
	 *         {@link LogFiles#MAX_CHARS} characters
	 */
	static String shown(String text) {
		return Texts.cut(UrlCredentials.cut(text), LogFiles.MAX_CHARS);
	}

	/**
	 * Reads forward in one file from an offset: the oldest entries that start at it or after it. When it stops at
	 * {@link LogFiles#MAX_LINES} entries, which it knows only once it has looked at the next entry's first line, the
	 * answer's {@code after} is that line's start; otherwise just past the last whole line it read.
	 *
	 * @param part the file's index in the chain
	 * @param from the offset, at most the file's size
	 * @param rolled whether the {@code after} asked for named a file that rolled over, so this is the current one's
	 * @return the entries, oldest first
	 * @throws IOException if the file can't be read
	 */
	LogLines forward(int part, long from, boolean rolled) throws IOException {
		Part file = chain.parts().get(part);
		ForwardLines lines = new ForwardLines(file.channel(), from, file.size());
		List<LogLine> kept = new ArrayList<>();
		Entry entry = null;
		long after = from;
		boolean capped = false;
		for (Line line = lines.peek(); line != null; line = lines.peek()) {
			Entry starting = Entry.starting(line);
			if (starting != null || entry == null || entry.alone) {
				// This line begins another entry, so the one before it has ended
				if (entry != null && keep(entry, kept, part) && kept.size() == LogFiles.MAX_LINES) {
					capped = true;
					break;
				}
				entry = starting != null ? starting : Entry.alone(line);
			} else {
				entry.add(line);
			}
			lines.consume();
			after = line.end();
		}
		if (!capped && entry != null)
			keep(entry, kept, part);
		return new LogLines(kept, chain.position(part, from), chain.position(part, after),
				part == 0 && from == 0, !capped && lines.ended(), rolled);
	}

	/**
	 * Reads back from an offset in a file: the newest entries that start before it, going on into the next older
	 * file at the start of one. The answer's {@code before} is the start of the earliest entry it looked at, kept or
	 * not; the continuation lines above that it looked at aren't given, as the next read gives them with their
	 * entry. When it finds no entry's start at all, the lines it looked at are a line each, so a read always moves
	 * back.
	 *
	 * @param part the file's index in the chain
	 * @param from the offset, at most the file's size; at the end of the current file, a last line not yet ended is
	 *        left out
	 * @return the entries, oldest first
	 * @throws IOException if a file can't be read
	 */
	LogLines back(int part, long from) throws IOException {
		Deque<LogLine> kept = new ArrayDeque<>();
		int lookedAt = 0;
		LogPosition earliest = null;
		LogPosition after = null;
		// The lines read since the last entry's start, the newest first, and the part they're in
		List<Line> pending = new ArrayList<>();
		int pendingPart = part;

		for (int index = part; index >= 0; index--) {
			Part file = chain.parts().get(index);
			long end = index == part ? from : file.size();
			BackwardLines lines = new BackwardLines(file.channel(), end, file.current() && end == file.size());
			if (after == null)
				after = chain.position(index, lines.end());
			pending.clear();
			pendingPart = index;

			boolean stopped = false;
			for (Line line = lines.next(); line != null; line = lines.next()) {
				Entry starting = Entry.starting(line);
				if (starting != null) {
					for (int i = pending.size() - 1; i >= 0; i--)
						starting.add(pending.get(i));
					pending.clear();
					lookedAt++;
					earliest = chain.position(index, line.start());
					if (starting.kept(query)) {
						kept.addFirst(starting.line(chain, index));
						if (kept.size() == LogFiles.MAX_LINES) {
							stopped = true;
							break;
						}
					}
				} else {
					pending.add(line);
					if (lookedAt == 0 && pending.size() == LogFiles.MAX_LINES) {
						stopped = true;
						break;
					}
				}
				if (spent()) {
					stopped = true;
					break;
				}
			}
			if (!stopped && lines.cut()) {
				// The budget ran out inside a line. When it's the read's first, that much of it, so that a read always
				// moves back; otherwise the next read starts with it
				if (lookedAt == 0 && pending.isEmpty())
					pending.add(lines.fragment());
				stopped = true;
			}
			if (stopped)
				break;

			// The start of the file: what's above its first entry is a line each, the lowest first
			boolean capped = false;
			for (int i = 0; i < pending.size(); i++) {
				Line line = pending.get(i);
				lookedAt++;
				earliest = chain.position(index, line.start());
				Entry alone = Entry.alone(line);
				if (alone.kept(query)) {
					kept.addFirst(alone.line(chain, index));
					if (kept.size() == LogFiles.MAX_LINES) {
						// The lines above it are the next read's
						capped = i < pending.size() - 1;
						break;
					}
				}
			}
			pending.clear();
			if (capped)
				break;
			earliest = chain.position(index, 0);
			if (kept.size() == LogFiles.MAX_LINES || spent())
				break;
		}
		if (lookedAt == 0 && !pending.isEmpty()) {
			// No entry's start at all
			for (Line line : pending) {
				earliest = chain.position(pendingPart, line.start());
				Entry alone = Entry.alone(line);
				if (alone.kept(query))
					kept.addFirst(alone.line(chain, pendingPart));
			}
		}
		if (earliest == null)
			earliest = after;
		Part start = chain.parts().get(part);
		return new LogLines(new ArrayList<>(kept), earliest, after, earliest.equals(chain.position(0, 0)),
				part == chain.current() && from == start.size(), false);
	}

	/** @return whether the read has looked through all it may, its bytes or its lines */
	private boolean spent() {
		return scanned >= budget || linesLooked >= MAX_LINES_LOOKED;
	}

	/** @return whether it kept the entry, which it adds to the end of the list */
	private boolean keep(Entry entry, List<LogLine> kept, int part) {
		if (!entry.kept(query))
			return false;
		kept.add(entry.line(chain, part));
		return true;
	}

	/** @return a line's kept bytes, decoded, without a carriage return at the end */
	private String decode(byte[] bytes, int length) {
		int end = length > 0 && bytes[length - 1] == '\r' ? length - 1 : length;
		try {
			return decoder.decode(ByteBuffer.wrap(bytes, 0, end)).toString();
		} catch (CharacterCodingException e) {
			throw new IllegalStateException("A decoder that replaces refused", e);
		}
	}

	/** Reads into a buffer from a place in a file, as much as fits or the file has */
	private static void fill(FileChannel channel, ByteBuffer buffer, long at) throws IOException {
		while (buffer.hasRemaining()) {
			if (channel.read(buffer, at + buffer.position()) < 0)
				break;
		}
		buffer.flip();
	}

	/** A file's whole lines from an offset forward, up to its size when it was opened */
	private final class ForwardLines {

		private final FileChannel channel;

		private final long limit;

		private final long start;

		private final ByteBuffer buffer = ByteBuffer.allocate(CHUNK);

		/** The kept bytes of the line being read, for every line in turn */
		private final byte[] head = new byte[HEAD_BYTES];

		/** Where the buffer's first byte is in the file */
		private long bufferStart;

		/** The start of the next line */
		private long position;

		private Line peeked;

		private boolean ended;

		ForwardLines(FileChannel channel, long from, long limit) {
			this.channel = channel;
			this.limit = channel != null ? limit : 0;
			this.start = from;
			this.position = from;
			this.bufferStart = from;
			buffer.limit(0);
		}

		/** @return whether it got to the end of the file as it was, but for a last line not yet ended */
		boolean ended() {
			return ended;
		}

		/** Takes the line {@link #peek} gave as read */
		void consume() {
			position = peeked.end();
			peeked = null;
		}

		/**
		 * @return the next whole line, until {@link #consume} takes it; null at the end, before a last line not yet
		 *         ended, or once the budget is spent; but when the budget is spent inside the read's first line, that
		 *         much of it, so a read always moves on
		 */
		Line peek() throws IOException {
			if (peeked != null)
				return peeked;
			if (position >= limit) {
				ended = true;
				return null;
			}
			if (spent())
				return null;
			int kept = 0;
			long at = position;
			while (true) {
				if (at >= limit) {
					ended = true;
					return null;
				}
				if (at < bufferStart || at >= bufferStart + buffer.limit()) {
					buffer.clear();
					buffer.limit((int) Math.min(CHUNK, limit - at));
					fill(channel, buffer, at);
					bufferStart = at;
				}
				int from = (int) (at - bufferStart);
				int stop = buffer.limit();
				int feed = -1;
				for (int i = from; i < stop; i++)
					if (buffer.get(i) == '\n') {
						feed = i;
						break;
					}
				int upto = feed >= 0 ? feed : stop;
				int take = Math.min(upto - from, HEAD_BYTES - kept);
				if (take > 0) {
					buffer.get(from, head, kept, take);
					kept += take;
				}
				long step = (long) upto - from + (feed >= 0 ? 1 : 0);
				scanned += step;
				at += step;
				if (feed >= 0) {
					linesLooked++;
					peeked = new Line(position, at, decode(head, kept));
					return peeked;
				}
				if (scanned >= budget) {
					if (position > start)
						return null;
					peeked = new Line(position, at, decode(head, kept));
					return peeked;
				}
			}
		}
	}

	/**
	 * A file's lines from an offset back to its start. With {@code dropLast}, the bytes after the last line feed
	 * before the offset are a line not yet ended, and are left out.
	 */
	private final class BackwardLines {

		private final FileChannel channel;

		private final ByteBuffer buffer = ByteBuffer.allocate(CHUNK);

		/** Where the buffer's first byte is in the file */
		private long bufferStart;

		/** The end of the next line it gives, the start of the last one it gave */
		private long lineEnd;

		/** The earliest byte it has looked at */
		private long looked;

		private final long end;

		private boolean cut;

		private Line fragment;

		BackwardLines(FileChannel channel, long from, boolean dropLast) throws IOException {
			this.channel = channel;
			this.lineEnd = channel != null ? from : 0;
			this.looked = lineEnd;
			this.bufferStart = lineEnd;
			buffer.limit(0);
			if (dropLast && lineEnd > 0) {
				// A line being written: looked through whatever its length, so that the read ends at a whole line
				long feed = feedBefore(lineEnd, false);
				lineEnd = feed + 1;
			}
			this.end = lineEnd;
		}

		/** @return where the lines it gives end */
		long end() {
			return end;
		}

		/** @return whether the budget ran out inside a line, which {@link #fragment()} then holds as far as it got */
		boolean cut() {
			return cut;
		}

		Line fragment() {
			return fragment;
		}

		/**
		 * @return the next line back; null at the start of the file, or when the budget runs out inside a line
		 */
		Line next() throws IOException {
			if (lineEnd <= 0 || cut)
				return null;
			// The line's own line feed is at lineEnd - 1: the one before ends the line before
			long feed = feedBefore(lineEnd - 1, true);
			if (feed == -2) {
				cut = true;
				fragment = line(looked, lineEnd);
				return null;
			}
			Line line = line(feed + 1, lineEnd);
			lineEnd = feed + 1;
			linesLooked++;
			return line;
		}

		/**
		 * Looks back from just before an offset for a line feed, counting each byte it looks at once.
		 *
		 * @param before the offset, which it doesn't look at
		 * @param budgeted whether to stop when the budget runs out
		 * @return the line feed's offset; -1 when there's none before the start of the file; -2 when the budget
		 *         ran out first
		 */
		private long feedBefore(long before, boolean budgeted) throws IOException {
			long at = before - 1;
			while (at >= 0) {
				if (at < bufferStart || at >= bufferStart + buffer.limit()) {
					long start = Math.max(0, at + 1 - CHUNK);
					buffer.clear();
					buffer.limit((int) (at + 1 - start));
					fill(channel, buffer, start);
					bufferStart = start;
				}
				for (int i = (int) (at - bufferStart); i >= 0; i--, at--) {
					if (at < looked) {
						scanned++;
						looked = at;
					}
					if (buffer.get(i) == '\n')
						return at;
					if (budgeted && scanned >= budget && at > 0)
						return -2;
				}
			}
			return -1;
		}

		/** @return the line from a start to an end, of which it reads the first {@link #HEAD_BYTES} */
		private Line line(long start, long end) throws IOException {
			long stop = end > start && byteAt(end - 1) == '\n' ? end - 1 : end;
			ByteBuffer head = ByteBuffer.allocate((int) Math.min(stop - start, HEAD_BYTES));
			fill(channel, head, start);
			return new Line(start, end, decode(head.array(), head.limit()));
		}

		private byte byteAt(long at) throws IOException {
			if (at >= bufferStart && at < bufferStart + buffer.limit())
				return buffer.get((int) (at - bufferStart));
			ByteBuffer one = ByteBuffer.allocate(1);
			fill(channel, one, at);
			return one.limit() == 1 ? one.get(0) : 0;
		}
	}
}
