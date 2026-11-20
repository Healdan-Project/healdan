// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.logicaldoc.healdan.util.StorableText;

/**
 * How the move carries one value from MariaDB to a PostgreSQL column (spec section 9.3, step 4): exactly, as a Java
 * value that comes back from the column unchanged, or not at all. A value PostgreSQL can't hold as it is (a NUL in
 * text, MariaDB's zero date, a number beyond the column's range, text longer than the column allows) is Unstorable,
 * which names the table, the row's key and the column, and never anything of the value. So is a value of a Java class
 * or for a column type the move doesn't expect: the move stops rather than guess at a conversion. The one change it
 * makes, a space for a NUL, it makes only when asked to, and records where.
 */
final class MoveValues {

    /** Where a value is: for messages and reports, which never hold the value itself */
    record Where(String table, String key, String column) {
        @Override
        public String toString() {
            return "table " + table + ", row " + key + ", column " + column;
        }
    }

    /** A value PostgreSQL can't hold as it is */
    static final class Unstorable extends Exception {

        private static final long serialVersionUID = 1L;

        private final Where where;

        private final String why;

        /** @param why the reason, which never holds anything of the value */
        Unstorable(Where where, String why) {
            super(where + ": " + why);
            this.where = where;
            this.why = why;
        }

        Where where() {
            return where;
        }

        /** The reason alone, without the place */
        String why() {
            return why;
        }
    }

    /** What to do with a NUL in text: REFUSE it, or REPLACE it with a space and record where */
    enum Nul {
        REFUSE, REPLACE
    }

    /** The target column: PostgreSQL's data_type from information_schema.columns, and character_maximum_length or 0 */
    record Target(String type, int maxLength) {
    }

    /** The kinds of column the move carries, by data_type */
    private enum Kind {
        TEXT, WHOLE, FLOATING, DECIMAL, TIMESTAMP
    }

    /**
     * MariaDB's text form of a datetime or timestamp: a four-digit year, and from none to six digits of fraction. ASCII
     * digits only, without leading or trailing space
     */
    private static final Pattern DATE_TIME = Pattern
            .compile("([0-9]{4})-([0-9]{2})-([0-9]{2}) ([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.([0-9]{1,6}))?");

    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);

    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private MoveValues() {
    }

    /**
     * What it returns for each target type: for text, character varying and character, a String, which for
     * character(n) is padded with spaces to n characters, as PostgreSQL stores and returns it; for smallint, integer
     * and bigint, a Short, an Integer and a Long; for double precision and real, a Double and a Float; for numeric, a
     * BigDecimal; for timestamp without time zone, a LocalDateTime, the wall-clock time MariaDB's text gives. A target
     * type it doesn't know is Unstorable whatever the value, null included, so a column of a new type stops the move.
     *
     * @param value as read from MariaDB: a String, a Number or null. Dates and times are read as text
     * @param replaced gains the place of every NUL replaced
     * @return what to bind for the target column
     */
    static Object carry(Object value, Target target, Where where, Nul nul, List<Where> replaced) throws Unstorable {
        String type = target.type() == null ? "" : target.type().toLowerCase(Locale.ROOT);
        Kind kind = switch (type) {
        case "text", "character varying", "character" -> Kind.TEXT;
        case "smallint", "integer", "bigint" -> Kind.WHOLE;
        case "double precision", "real" -> Kind.FLOATING;
        case "numeric" -> Kind.DECIMAL;
        case "timestamp without time zone" -> Kind.TIMESTAMP;
        default -> throw new Unstorable(where, "the column's type is " + target.type()
                + ", which the move doesn't carry values into");
        };
        if (value == null)
            return null;
        return switch (kind) {
        case TEXT -> text(value, type, target.maxLength(), where, nul, replaced);
        case WHOLE -> whole(value, type, where);
        case FLOATING -> floating(value, type, where);
        case DECIMAL -> decimal(value, where);
        case TIMESTAMP -> {
            if (!(value instanceof String text))
                throw new Unstorable(where, wrongClass(value, type, "whose values the move reads from MariaDB as"
                        + " text"));
            yield dateTime(text, where);
        }
        };
    }

    /** MariaDB's text form of a datetime or timestamp, with or without fractions, as a wall-clock time */
    static LocalDateTime dateTime(String text, Where where) throws Unstorable {
        Matcher m = text == null ? null : DATE_TIME.matcher(text);
        if (m == null || !m.matches())
            throw new Unstorable(where, "the value isn't a date and time in MariaDB's form");
        int year = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        int day = Integer.parseInt(m.group(3));
        int hour = Integer.parseInt(m.group(4));
        int minute = Integer.parseInt(m.group(5));
        int second = Integer.parseInt(m.group(6));
        String fraction = m.group(7) == null ? "" : m.group(7);
        int nanos = fraction.isEmpty() ? 0 : Integer.parseInt((fraction + "00000000").substring(0, 9));
        if (year == 0 && month == 0 && day == 0)
            throw new Unstorable(where, "the value is MariaDB's zero date, which PostgreSQL can't hold");
        if (month == 0 || day == 0)
            throw new Unstorable(where, "the value is a date with a zero month or day, which PostgreSQL can't hold");
        if (year == 0)
            throw new Unstorable(where, "the value is a date in the year 0, which PostgreSQL's calendar doesn't have");
        try {
            return LocalDateTime.of(year, month, day, hour, minute, second, nanos);
        } catch (DateTimeException e) {
            throw new Unstorable(where, "the value isn't a date and time on the calendar");
        }
    }

    private static String text(Object value, String type, int maxLength, Where where, Nul nul, List<Where> replaced)
            throws Unstorable {
        if (!(value instanceof String text))
            throw new Unstorable(where, wrongClass(value, type, "which takes text"));
        if (StorableText.hasLoneSurrogate(text))
            throw new Unstorable(where, "the text holds an unpaired UTF-16 surrogate, which isn't a character, and"
                    + " which PostgreSQL can't hold");
        boolean hasNul = text.indexOf('\u0000') >= 0;
        int length = text.codePointCount(0, text.length());
        // Only REPLACE replaces: any other choice, null included, refuses
        boolean refuseNul = hasNul && nul != Nul.REPLACE;
        boolean tooLong = maxLength > 0 && length > maxLength;
        if (refuseNul || tooLong) {
            List<String> why = new ArrayList<>(2);
            if (refuseNul)
                why.add("the text holds a NUL character, which PostgreSQL's text types can't hold");
            if (tooLong)
                why.add("the text is " + length + " characters long, and the column (" + type + "(" + maxLength
                        + ")) takes " + maxLength);
            throw new Unstorable(where, String.join("; and ", why));
        }
        String carried = hasNul ? text.replace('\u0000', ' ') : text;
        if (type.equals("character") && maxLength > 0 && length < maxLength)
            carried = carried + " ".repeat(maxLength - length);
        if (hasNul)
            replaced.add(where);
        return carried;
    }

    /** A Short, an Integer or a Long for smallint, integer or bigint, equal to the value */
    private static Object whole(Object value, String type, Where where) throws Unstorable {
        long n;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            n = ((Number) value).longValue();
        } else if (value instanceof BigInteger b) {
            if (b.compareTo(LONG_MIN) < 0 || b.compareTo(LONG_MAX) > 0)
                throw outOfRange(type, where);
            n = b.longValue();
        } else if (value instanceof BigDecimal d) {
            // A magnitude beyond a long's 19 digits is out of range, whatever its scale: checked before any
            // conversion, which for an exponent such as 1E+400 would build the whole number
            if (d.signum() != 0 && d.precision() - d.scale() > 19)
                throw outOfRange(type, where);
            BigInteger b;
            try {
                b = d.toBigIntegerExact();
            } catch (ArithmeticException e) {
                throw new Unstorable(where, "the value is a number with a fraction, and the column (" + type
                        + ") takes whole numbers");
            }
            if (b.compareTo(LONG_MIN) < 0 || b.compareTo(LONG_MAX) > 0)
                throw outOfRange(type, where);
            n = b.longValue();
        } else {
            throw new Unstorable(where, wrongClass(value, type, "which takes whole numbers"));
        }
        return switch (type) {
        case "smallint" -> {
            if (n < Short.MIN_VALUE || n > Short.MAX_VALUE)
                throw outOfRange(type, where);
            yield (short) n;
        }
        case "integer" -> {
            if (n < Integer.MIN_VALUE || n > Integer.MAX_VALUE)
                throw outOfRange(type, where);
            yield (int) n;
        }
        default -> n;
        };
    }

    private static Unstorable outOfRange(String type, Where where) {
        String range = switch (type) {
        case "smallint" -> Short.MIN_VALUE + " to " + Short.MAX_VALUE;
        case "integer" -> Integer.MIN_VALUE + " to " + Integer.MAX_VALUE;
        default -> Long.MIN_VALUE + " to " + Long.MAX_VALUE;
        };
        return new Unstorable(where, "the value is a whole number beyond the range the column (" + type + ") takes, "
                + range);
    }

    /** A Double for double precision, a Float for real, equal to the value exactly */
    private static Object floating(Object value, String type, Where where) throws Unstorable {
        boolean real = type.equals("real");
        // Never a conditional expression of a Float and a Double, which Java would unbox and widen to a double
        if (value instanceof Float f) {
            if (real)
                return f;
            return Double.valueOf(f.doubleValue()); // a float widened to a double is exact
        }
        if (value instanceof Double d) {
            if (!real)
                return d;
            float f = d.floatValue();
            if (d.isNaN() || f == d.doubleValue())
                return f;
            throw notExact(type, where);
        }
        BigDecimal exact;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
            exact = BigDecimal.valueOf(((Number) value).longValue());
        else if (value instanceof BigInteger b)
            exact = new BigDecimal(b);
        else if (value instanceof BigDecimal d)
            exact = d;
        else
            throw new Unstorable(where, wrongClass(value, type, "which takes numbers"));
        double d = real ? exact.floatValue() : exact.doubleValue();
        if (Double.isInfinite(d) || new BigDecimal(d).compareTo(exact) != 0)
            throw notExact(type, where);
        if (real)
            return Float.valueOf((float) d);
        return Double.valueOf(d);
    }

    private static Unstorable notExact(String type, Where where) {
        return new Unstorable(where, "the value is a number the column (" + type + ") can't hold exactly");
    }

    /** A BigDecimal for numeric, equal to the value */
    private static BigDecimal decimal(Object value, Where where) throws Unstorable {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
            return BigDecimal.valueOf(((Number) value).longValue());
        if (value instanceof BigInteger b)
            return new BigDecimal(b);
        if (value instanceof BigDecimal d)
            return d;
        if (value instanceof Float || value instanceof Double)
            throw new Unstorable(where, wrongClass(value, "numeric", "which holds exact decimals, and the move doesn't"
                    + " choose one for a floating-point number"));
        throw new Unstorable(where, wrongClass(value, "numeric", "which takes numbers"));
    }

    /** The reason for a value of a class the column's kind doesn't take: names the class, never the value */
    private static String wrongClass(Object value, String type, String takes) {
        return "the value is a " + value.getClass().getTypeName() + ", and the column is " + type + ", " + takes;
    }
}
