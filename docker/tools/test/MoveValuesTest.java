// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * How the move carries one value from MariaDB to a PostgreSQL column, and refuses what PostgreSQL can't hold as it
 * is, naming the place and never the value (spec section 9.3, step 4; the plan's Review Focus 2).
 */
public class MoveValuesTest {

    private static final MoveValues.Where WHERE = new MoveValues.Where("ld_user", "ld_id=42", "ld_avatar");

    private static final MoveValues.Target TEXT = new MoveValues.Target("text", 0);

    private static final MoveValues.Target VARCHAR_4000 = new MoveValues.Target("character varying", 4000);

    private static final MoveValues.Target INTEGER = new MoveValues.Target("integer", 0);

    private static final MoveValues.Target SMALLINT = new MoveValues.Target("smallint", 0);

    private static final MoveValues.Target BIGINT = new MoveValues.Target("bigint", 0);

    private static final MoveValues.Target DOUBLE = new MoveValues.Target("double precision", 0);

    private static final MoveValues.Target REAL = new MoveValues.Target("real", 0);

    private static final MoveValues.Target NUMERIC = new MoveValues.Target("numeric", 0);

    private static final MoveValues.Target TIMESTAMP = new MoveValues.Target("timestamp without time zone", 0);

    /** U+1F389, one character that Java holds as two chars */
    private static final String EMOJI = "\uD83C\uDF89";

    public static void main(String[] args) {
        refusesOrReplacesANul();
        limitsTextByCharacters();
        refusesWhatIsNotText();
        carriesWholeNumbersInRange();
        carriesFloatingPointExactly();
        carriesExactDecimals();
        carriesDatesAndTimes();
        readsMariaDbsDateTimes();
        refusesMariaDbsZeroDates();
        refusesWhatIsNoDateAndTime();
        carriesNullToTheTypesItKnows();
        refusesTypesItDoesNotKnow();
        Check.done("MoveValuesTest");
    }

    private static void refusesOrReplacesANul() {
        List<MoveValues.Where> replaced = new ArrayList<>();
        MoveValues.Unstorable refused = refused(
                () -> MoveValues.carry("a\u0000b", TEXT, WHERE, MoveValues.Nul.REFUSE, replaced), "a NUL, refused");
        namesThePlace(refused, "a NUL, refused");
        lacks(refused, "a NUL, refused", "a\u0000b", "\u0000", "a b");
        Check.eq(List.of(), replaced, "a refused NUL isn't recorded as replaced");
        Check.eq(refused == null ? null : WHERE + ": " + refused.why(), refused == null ? null : refused.getMessage(),
                "the reason alone is the message without the place");
        Check.isTrue(refused == null || !refused.why().contains("ld_user"), "the reason alone names no place");

        // The brief's value is two letters that any sentence holds, so the message is checked for these words too.
        MoveValues.Unstorable words = refused(() -> MoveValues.carry("Quarterly\u0000Report", VARCHAR_4000, WHERE,
                MoveValues.Nul.REFUSE, replaced), "a NUL in words, refused");
        lacks(words, "a NUL in words, refused", "Quarterly", "Report");

        Check.eq("a b", carried(() -> MoveValues.carry("a\u0000b", TEXT, WHERE, MoveValues.Nul.REPLACE, replaced),
                "a NUL, replaced"), "a NUL replaced with a space");
        Check.eq(List.of(WHERE), replaced, "the replacement's place is recorded");

        MoveValues.Where other = new MoveValues.Where("hd_email", "hd_docid=7", "hd_messageid");
        Check.eq(" x ", carried(() -> MoveValues.carry("\u0000x\u0000", VARCHAR_4000, other, MoveValues.Nul.REPLACE,
                replaced), "two NULs, replaced"), "every NUL in the value replaced");
        Check.eq(List.of(WHERE, other), replaced, "a value's place is recorded once, however many NULs it held");

        Check.eq("plain", carried(() -> MoveValues.carry("plain", TEXT, WHERE, MoveValues.Nul.REPLACE, replaced),
                "text without a NUL"), "text without a NUL is carried as it is");
        Check.eq(List.of(WHERE, other), replaced, "text without a NUL isn't recorded");

        refused(() -> MoveValues.carry("a\u0000b", TEXT, WHERE, null, replaced),
                "a NUL with no choice made: only REPLACE replaces");
        Check.eq(List.of(WHERE, other), replaced, "nor recorded");
    }

    private static void limitsTextByCharacters() {
        List<MoveValues.Where> replaced = new ArrayList<>();
        String tooLong = "x".repeat(4001);
        MoveValues.Unstorable refused = refused(
                () -> MoveValues.carry(tooLong, VARCHAR_4000, WHERE, MoveValues.Nul.REFUSE, replaced),
                "4,001 characters into character varying(4000)");
        namesThePlace(refused, "too long");
        Check.isTrue(refused != null && refused.getMessage().contains("4000") && refused.getMessage().contains("4001"),
                "the refusal gives the column's limit and the value's length: " + message(refused));
        lacks(refused, "too long", "xxxx");
        Check.eq(tooLong, carried(() -> MoveValues.carry(tooLong, TEXT, WHERE, MoveValues.Nul.REFUSE, replaced),
                "4,001 characters into text"), "text has no limit");
        String full = "x".repeat(4000);
        Check.eq(full, carried(() -> MoveValues.carry(full, VARCHAR_4000, WHERE, MoveValues.Nul.REFUSE, replaced),
                "4,000 characters into character varying(4000)"), "a value the column's length exactly");
        Check.eq("", carried(() -> MoveValues.carry("", VARCHAR_4000, WHERE, MoveValues.Nul.REFUSE, replaced),
                "the empty string"), "the empty string is carried as it is");
        Check.eq(full, carried(() -> MoveValues.carry(full, new MoveValues.Target("character varying", 0), WHERE,
                MoveValues.Nul.REFUSE, replaced), "character varying without a length"),
                "character varying without a length has no limit");

        String emoji = EMOJI.repeat(4000);
        Check.eq(emoji, carried(() -> MoveValues.carry(emoji, VARCHAR_4000, WHERE, MoveValues.Nul.REFUSE, replaced),
                "4,000 emoji, 8,000 chars"), "an emoji counts as one character, as PostgreSQL counts it");
        refused(() -> MoveValues.carry(emoji + EMOJI, VARCHAR_4000, WHERE, MoveValues.Nul.REFUSE, replaced),
                "4,001 emoji into character varying(4000)");

        MoveValues.Unstorable nulAndLong = refused(() -> MoveValues.carry(tooLong.substring(1) + "\u0000\u0000",
                VARCHAR_4000, WHERE, MoveValues.Nul.REFUSE, replaced), "a NUL in a value too long");
        Check.isTrue(nulAndLong != null && nulAndLong.getMessage().contains("NUL")
                && nulAndLong.getMessage().contains("4000"), "both reasons given: " + message(nulAndLong));
        refused(() -> MoveValues.carry(tooLong.substring(1) + "\u0000\u0000", VARCHAR_4000, WHERE,
                MoveValues.Nul.REPLACE, replaced), "a NUL replaced in a value too long");
        Check.eq(List.of(), replaced, "a value refused for its length isn't recorded as replaced");

        MoveValues.Target char1 = new MoveValues.Target("character", 1);
        Check.eq("F", carried(() -> MoveValues.carry("F", char1, WHERE, MoveValues.Nul.REFUSE, replaced),
                "a letter into character(1)"), "character(1)");
        Check.eq(" ", carried(() -> MoveValues.carry("", char1, WHERE, MoveValues.Nul.REFUSE, replaced),
                "the empty string into character(1)"),
                "character(n) pads with spaces, as PostgreSQL stores and returns it (MariaDB stores CHAR padded too)");
        Check.eq("ab ", carried(() -> MoveValues.carry("ab", new MoveValues.Target("character", 3), WHERE,
                MoveValues.Nul.REFUSE, replaced), "two letters into character(3)"), "padded to three");
        Check.eq(EMOJI + " ", carried(() -> MoveValues.carry(EMOJI, new MoveValues.Target("character", 2), WHERE,
                MoveValues.Nul.REFUSE, replaced), "an emoji into character(2)"), "padded by characters, not chars");
        refused(() -> MoveValues.carry("FD", char1, WHERE, MoveValues.Nul.REFUSE, replaced),
                "two letters into character(1)");
        refused(() -> MoveValues.carry("F ", char1, WHERE, MoveValues.Nul.REFUSE, replaced),
                "a trailing space beyond the limit, which PostgreSQL would drop quietly");

        MoveValues.Unstorable lone = refused(() -> MoveValues.carry("Draft\uD83C", TEXT, WHERE,
                MoveValues.Nul.REFUSE, replaced), "half an emoji");
        lacks(lone, "half an emoji", "Draft");
        refused(() -> MoveValues.carry("\uDF89Draft", TEXT, WHERE, MoveValues.Nul.REFUSE, replaced),
                "the other half of an emoji, first");
        Check.eq("x", carried(() -> MoveValues.carry("x", new MoveValues.Target("TEXT", 0), WHERE,
                MoveValues.Nul.REFUSE, replaced), "a type in capitals"), "the type's name whatever its case");
    }

    private static void refusesWhatIsNotText() {
        List<MoveValues.Where> replaced = new ArrayList<>();
        MoveValues.Unstorable number = refused(() -> MoveValues.carry(987654321L, TEXT, WHERE, MoveValues.Nul.REFUSE,
                replaced), "a number into text");
        namesThePlace(number, "a number into text");
        Check.isTrue(number != null && number.getMessage().contains("java.lang.Long"),
                "the refusal names the class: " + message(number));
        lacks(number, "a number into text", "987654321");

        MoveValues.Unstorable blob = refused(() -> MoveValues.carry(new byte[] { 1, 2 }, VARCHAR_4000, WHERE,
                MoveValues.Nul.REFUSE, replaced), "a blob into character varying");
        Check.isTrue(blob != null && blob.getMessage().contains("byte[]"), "names byte[]: " + message(blob));

        MoveValues.Unstorable bool = refused(() -> MoveValues.carry(Boolean.TRUE, INTEGER, WHERE,
                MoveValues.Nul.REFUSE, replaced), "a Boolean, as MariaDB's driver reads tinyint(1)");
        Check.isTrue(bool != null && bool.getMessage().contains("java.lang.Boolean"),
                "names java.lang.Boolean: " + message(bool));
        lacks(bool, "a Boolean", "true");

        MoveValues.Unstorable textAsNumber = refused(() -> MoveValues.carry("42", INTEGER, WHERE,
                MoveValues.Nul.REFUSE, replaced), "text into integer");
        Check.isTrue(textAsNumber != null && textAsNumber.getMessage().contains("java.lang.String"),
                "names java.lang.String: " + message(textAsNumber));
        refused(() -> MoveValues.carry("1.5", DOUBLE, WHERE, MoveValues.Nul.REFUSE, replaced),
                "text into double precision");
        refused(() -> MoveValues.carry("1.5", NUMERIC, WHERE, MoveValues.Nul.REFUSE, replaced), "text into numeric");
        refused(() -> MoveValues.carry('x', TEXT, WHERE, MoveValues.Nul.REFUSE, replaced), "a Character into text");
    }

    private static void carriesWholeNumbersInRange() {
        MoveValues.Unstorable big = refused(() -> carry(3_000_000_000L, INTEGER), "3,000,000,000 into integer");
        namesThePlace(big, "beyond integer's range");
        lacks(big, "beyond integer's range", "3000000000", "3,000,000,000");
        Object fortyTwo = carried(() -> carry(42L, INTEGER), "42 into integer");
        Check.isTrue(fortyTwo instanceof Number n && n.longValue() == 42, "a number equal to 42: " + fortyTwo);
        Check.eq(Integer.valueOf(42), fortyTwo, "an Integer, as pgjdbc binds and reads integer");

        Check.eq(Integer.MAX_VALUE, carried(() -> carry((long) Integer.MAX_VALUE, INTEGER), "integer's highest"),
                "integer's highest");
        Check.eq(Integer.MIN_VALUE, carried(() -> carry((long) Integer.MIN_VALUE, INTEGER), "integer's lowest"),
                "integer's lowest");
        refused(() -> carry(Integer.MAX_VALUE + 1L, INTEGER), "one above integer's range");
        refused(() -> carry(Integer.MIN_VALUE - 1L, INTEGER), "one below integer's range");

        Check.eq((short) 32767, carried(() -> carry(32767, SMALLINT), "smallint's highest"), "a Short for smallint");
        Check.eq((short) -32768, carried(() -> carry(-32768, SMALLINT), "smallint's lowest"), "smallint's lowest");
        refused(() -> carry(32768, SMALLINT), "one above smallint's range");
        refused(() -> carry(-32769, SMALLINT), "one below smallint's range");

        Check.eq(Long.MIN_VALUE, carried(() -> carry(Long.MIN_VALUE, BIGINT), "bigint's lowest"), "a Long for bigint");
        Check.eq(Long.MAX_VALUE, carried(() -> carry(BigInteger.valueOf(Long.MAX_VALUE), BIGINT),
                "bigint's highest, as a BigInteger"), "an unsigned bigint within range");
        refused(() -> carry(BigInteger.ONE.shiftLeft(63), BIGINT), "an unsigned bigint beyond bigint's range");
        refused(() -> carry(BigInteger.ONE.shiftLeft(63).negate().subtract(BigInteger.ONE), BIGINT),
                "below bigint's range");
        Check.eq(-7L, carried(() -> carry(-7, BIGINT), "a negative Integer into bigint"), "a negative number");
        Check.eq(0L, carried(() -> carry(0, BIGINT), "zero"), "zero");

        Object[] widths = { (byte) 5, (short) 5, 5, 5L, BigInteger.valueOf(5) };
        for (Object width : widths)
            Check.eq(Integer.valueOf(5), carried(() -> carry(width, INTEGER), "a " + width.getClass().getName()),
                    "whatever the width it arrives in, a " + width.getClass().getName());

        Check.eq(42, carried(() -> carry(new BigDecimal("42.00"), INTEGER), "a whole BigDecimal"),
                "a BigDecimal with no fraction");
        Check.eq(1000L, carried(() -> carry(new BigDecimal("1E+3"), BIGINT), "a BigDecimal with an exponent"),
                "a BigDecimal with an exponent");
        refused(() -> carry(new BigDecimal("42.5"), INTEGER), "a fraction into integer");
        refused(() -> carry(new BigDecimal("1E+400"), BIGINT), "a BigDecimal beyond bigint's range");
        MoveValues.Unstorable floating = refused(() -> carry(42.0, INTEGER), "a Double into integer");
        Check.isTrue(floating != null && floating.getMessage().contains("java.lang.Double"),
                "names java.lang.Double: " + message(floating));
        refused(() -> carry(42.0f, BIGINT), "a Float into bigint");
    }

    private static void carriesFloatingPointExactly() {
        Check.eq(1.5, carried(() -> carry(1.5, DOUBLE), "a Double"), "a Double into double precision");
        Check.eq((double) 0.1f, carried(() -> carry(0.1f, DOUBLE), "a Float into double precision"),
                "a Float widened, which is exact");
        Check.eq(42.0, carried(() -> carry(42L, DOUBLE), "a whole number into double precision"), "42.0");
        Check.eq(1.5, carried(() -> carry(new BigDecimal("1.50"), DOUBLE), "an exact BigDecimal"), "1.5");
        refused(() -> carry((1L << 53) + 1, DOUBLE), "a whole number double precision can't hold exactly");
        refused(() -> carry(Long.MAX_VALUE, DOUBLE), "Long.MAX_VALUE, which rounds to 2^63");
        refused(() -> carry(new BigDecimal("0.1"), DOUBLE), "a decimal no double is equal to");
        refused(() -> carry(BigInteger.TEN.pow(400), DOUBLE), "a BigInteger beyond double's range");
        Check.isTrue(carried(() -> carry(Double.NaN, DOUBLE), "NaN") instanceof Double d && d.isNaN(), "NaN");
        Check.eq(Double.NEGATIVE_INFINITY, carried(() -> carry(Double.NEGATIVE_INFINITY, DOUBLE), "an infinity"),
                "an infinity");
        Check.eq(-0.0, carried(() -> carry(-0.0, DOUBLE), "negative zero"), "negative zero keeps its sign");

        Check.eq(0.1f, carried(() -> carry(0.1f, REAL), "a Float into real"), "a Float for real");
        Check.eq(0.5f, carried(() -> carry(0.5, REAL), "a Double a float holds exactly"), "0.5 as a Float");
        refused(() -> carry(0.1, REAL), "a Double no float is equal to");
        refused(() -> carry(1e300, REAL), "a Double beyond real's range");
        Check.isTrue(carried(() -> carry(Double.NaN, REAL), "NaN into real") instanceof Float f && f.isNaN(),
                "NaN as a Float");
        Check.eq(16777216f, carried(() -> carry(16777216, REAL), "2^24"), "2^24 into real");
        refused(() -> carry(16777217, REAL), "2^24 + 1, which real can't hold exactly");
        Check.eq(-0.0f, carried(() -> carry(-0.0, REAL), "negative zero into real"), "negative zero as a Float");
    }

    private static void carriesExactDecimals() {
        Check.eq(new BigDecimal("1.50"), carried(() -> carry(new BigDecimal("1.50"), NUMERIC), "a BigDecimal"),
                "a BigDecimal as it is");
        Check.eq(BigDecimal.valueOf(42), carried(() -> carry(42, NUMERIC), "an Integer into numeric"),
                "a whole number as a BigDecimal");
        Check.eq(new BigDecimal(BigInteger.TEN.pow(40)), carried(() -> carry(BigInteger.TEN.pow(40), NUMERIC),
                "a BigInteger into numeric"), "a BigInteger as a BigDecimal");
        MoveValues.Unstorable floating = refused(() -> carry(0.1, NUMERIC), "a Double into numeric");
        Check.isTrue(floating != null && floating.getMessage().contains("java.lang.Double"),
                "names java.lang.Double: " + message(floating));
        refused(() -> carry(0.5f, NUMERIC), "a Float into numeric");
    }

    private static void carriesDatesAndTimes() {
        Check.eq(LocalDateTime.of(2026, 11, 18, 10, 21, 44),
                carried(() -> carry("2026-11-18 10:21:44", TIMESTAMP), "a datetime's text"),
                "a timestamp target takes the wall-clock time");
        MoveValues.Unstorable object = refused(() -> carry(java.sql.Timestamp.valueOf("2026-11-18 10:21:44"),
                TIMESTAMP), "a date read with getObject by mistake");
        Check.isTrue(object != null && object.getMessage().contains("java.sql.Timestamp"),
                "names java.sql.Timestamp: " + message(object));
        lacks(object, "a Timestamp", "2026");
        refused(() -> carry(20261118L, TIMESTAMP), "a number into a timestamp");
        refused(() -> carry(LocalDateTime.of(2026, 11, 18, 10, 21, 44), TIMESTAMP),
                "a LocalDateTime, which the move never reads from MariaDB");
    }

    private static void readsMariaDbsDateTimes() {
        Check.eq(LocalDateTime.of(2026, 11, 18, 10, 21, 44), dateTime("2026-11-18 10:21:44"), "whole seconds");
        Check.eq(LocalDateTime.of(2026, 11, 18, 10, 21, 44, 123_000_000), dateTime("2026-11-18 10:21:44.123"),
                "milliseconds kept");
        Check.eq(LocalDateTime.of(2026, 11, 18, 10, 21, 44, 100_000_000), dateTime("2026-11-18 10:21:44.1"),
                "one digit");
        Check.eq(LocalDateTime.of(2026, 11, 18, 10, 21, 44, 123_456_000), dateTime("2026-11-18 10:21:44.123456"),
                "microseconds");
        Check.eq(LocalDateTime.of(2026, 11, 18, 10, 21, 44), dateTime("2026-11-18 10:21:44.000"),
                "a zero fraction");
        Check.eq(LocalDateTime.of(2024, 2, 29, 0, 0), dateTime("2024-02-29 00:00:00"), "a leap day");
        Check.eq(LocalDateTime.of(1, 1, 1, 0, 0), dateTime("0001-01-01 00:00:00"), "the first year");
        Check.eq(LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_000), dateTime("9999-12-31 23:59:59.999999"),
                "MariaDB's last");
    }

    private static void refusesMariaDbsZeroDates() {
        MoveValues.Unstorable zero = refused(() -> MoveValues.dateTime("0000-00-00 00:00:00", WHERE),
                "MariaDB's zero date");
        namesThePlace(zero, "the zero date");
        Check.isTrue(zero != null && zero.getMessage().contains("zero date"), "says so: " + message(zero));
        refused(() -> MoveValues.dateTime("0000-00-00 00:00:00.000", WHERE), "the zero date with a fraction");
        MoveValues.Unstorable month = refused(() -> MoveValues.dateTime("2026-00-00 00:00:00", WHERE),
                "a zero month and day");
        lacks(month, "a zero month and day", "2026");
        refused(() -> MoveValues.dateTime("2026-11-00 10:00:00", WHERE), "a zero day");
        refused(() -> MoveValues.dateTime("2026-00-18 10:00:00", WHERE), "a zero month");
        refused(() -> MoveValues.dateTime("0000-00-00 10:00:00", WHERE), "a zero date with a time");
        refused(() -> MoveValues.dateTime("0000-01-01 00:00:00", WHERE),
                "year 0, which PostgreSQL's calendar doesn't have");
    }

    private static void refusesWhatIsNoDateAndTime() {
        String[] notDateTimes = { "2026-02-30 10:00:00", "2026-04-31 10:00:00", "2026-13-01 10:00:00",
                "2026-11-18 24:00:00", "2026-11-18 10:60:00", "2026-11-18 10:21:60", "2026-11-18T10:21:44",
                "2026-11-18 10:21:44.1234567", "2026-11-18 10:21:44.", "2026-11-18", "10:21:44",
                " 2026-11-18 10:21:44", "2026-11-18 10:21:44 ", "2026-11-18 10:21:44+01:00", "-2026-11-18 10:21:44",
                "20261-11-18 10:21:44", "", "\u0662\u0660\u0662\u0666-11-18 10:21:44" };
        for (String text : notDateTimes) {
            MoveValues.Unstorable refused = refused(() -> MoveValues.dateTime(text, WHERE), "not a date: " + text);
            namesThePlace(refused, "not a date: " + text);
            if (!text.isBlank())
                lacks(refused, "not a date: " + text, text.trim());
        }
        refused(() -> MoveValues.dateTime(null, WHERE), "no text");
    }

    private static void carriesNullToTheTypesItKnows() {
        List<MoveValues.Where> replaced = new ArrayList<>();
        MoveValues.Target[] known = { TEXT, VARCHAR_4000, new MoveValues.Target("character", 1), INTEGER, SMALLINT,
                BIGINT, DOUBLE, REAL, NUMERIC, TIMESTAMP };
        for (MoveValues.Target target : known)
            for (MoveValues.Nul nul : MoveValues.Nul.values())
                Check.eq(null, carried(() -> MoveValues.carry(null, target, WHERE, nul, replaced),
                        "null into " + target.type()), "null into " + target.type() + " is null");
        Check.eq(List.of(), replaced, "null records nothing");
    }

    private static void refusesTypesItDoesNotKnow() {
        List<MoveValues.Where> replaced = new ArrayList<>();
        for (String type : new String[] { "date", "boolean", "bytea", "jsonb", "timestamp with time zone",
                "time without time zone", "USER-DEFINED" }) {
            MoveValues.Target target = new MoveValues.Target(type, 0);
            MoveValues.Unstorable refused = refused(() -> MoveValues.carry("x", target, WHERE, MoveValues.Nul.REFUSE,
                    replaced), "a value into " + type);
            namesThePlace(refused, "a value into " + type);
            Check.isTrue(refused != null && refused.getMessage().contains(type),
                    "names the type " + type + ": " + message(refused));
            refused(() -> MoveValues.carry(null, target, WHERE, MoveValues.Nul.REFUSE, replaced),
                    "null into " + type + ": a type the move doesn't know stops it, whatever the value");
        }
    }

    /** Carries into the target, refusing a NUL, with a list nobody reads */
    private static Object carry(Object value, MoveValues.Target target) throws MoveValues.Unstorable {
        return MoveValues.carry(value, target, WHERE, MoveValues.Nul.REFUSE, new ArrayList<>());
    }

    private static LocalDateTime dateTime(String text) {
        return (LocalDateTime) carried(() -> MoveValues.dateTime(text, WHERE), "the datetime " + text);
    }

    private interface Body {
        Object run() throws Exception;
    }

    /** @return what the body returned, or null after recording a failure when it threw */
    private static Object carried(Body body, String what) {
        try {
            return body.run();
        } catch (Exception e) {
            Check.fail(what + ": expected a value but threw " + e);
            return null;
        }
    }

    /** @return the refusal the body threw, or null after recording a failure when it threw none */
    private static MoveValues.Unstorable refused(Body body, String what) {
        try {
            Object result = body.run();
            Check.fail(what + ": expected Unstorable but got a "
                    + (result == null ? "null" : result.getClass().getTypeName()));
        } catch (MoveValues.Unstorable e) {
            return e;
        } catch (Exception e) {
            Check.fail(what + ": expected Unstorable but threw " + e);
        }
        return null;
    }

    private static void namesThePlace(MoveValues.Unstorable refused, String what) {
        if (refused == null)
            return;
        Check.eq(WHERE, refused.where(), what + ": the refusal's place");
        String message = refused.getMessage();
        Check.isTrue(message.contains("ld_user") && message.contains("ld_id=42") && message.contains("ld_avatar"),
                what + ": the message names the table, the key and the column: " + message);
    }

    private static void lacks(MoveValues.Unstorable refused, String what, String... fragments) {
        if (refused == null)
            return;
        for (String fragment : fragments)
            Check.isTrue(!refused.getMessage().contains(fragment),
                    what + ": the message holds nothing of the value, but has \"" + fragment + "\": "
                            + refused.getMessage());
    }

    private static String message(Exception e) {
        return e == null ? "(none)" : e.getMessage();
    }
}
