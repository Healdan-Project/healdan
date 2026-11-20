// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * The move's checksum, worked out the same way from either database's rows, in an order that depends on neither's
 * sorting (spec section 9.3, step 7).
 */
public class MoveChecksumTest {

    public static void main(String[] args) {
        wholeNumbersWhateverTheirWidth();
        kindsNeverMeet();
        floatingPointWhateverTheWidth();
        decimalsByValue();
        timesWithoutTrailingZeros();
        refusesWhatItDoesNotKnow();
        rowsKeepTheirValuesApart();
        sumsIgnoreTheOrderOfRows();
        Check.done("MoveChecksumTest");
    }

    private static void wholeNumbersWhateverTheirWidth() {
        Check.eq(MoveChecksum.canonical(1L), MoveChecksum.canonical(1), "1L and 1");
        Check.isTrue(!MoveChecksum.canonical(1L).equals(MoveChecksum.canonical("1")), "1 and \"1\"");
        Check.isTrue(!MoveChecksum.canonical(1L).equals(MoveChecksum.canonical(null)), "1 and null");
        Check.isTrue(!MoveChecksum.canonical(null).equals(MoveChecksum.canonical("")), "null and the empty string");
        for (Object width : new Object[] { (byte) -5, (short) -5, -5, -5L, BigInteger.valueOf(-5) })
            Check.eq(MoveChecksum.canonical(-5L), MoveChecksum.canonical(width),
                    "-5 as a " + width.getClass().getName());
        Check.isTrue(!MoveChecksum.canonical(-5).equals(MoveChecksum.canonical(5)), "-5 and 5");
        Check.eq(MoveChecksum.canonical(BigInteger.valueOf(Long.MIN_VALUE)), MoveChecksum.canonical(Long.MIN_VALUE),
                "Long.MIN_VALUE as a Long and as a BigInteger");
        Check.eq(MoveChecksum.canonical(Integer.valueOf(Short.MAX_VALUE)), MoveChecksum.canonical(Short.MAX_VALUE),
                "a smallint carried as a Short and read back as an Integer");
        Check.isTrue(!MoveChecksum.canonical(Long.MAX_VALUE).equals(MoveChecksum.canonical(Long.MAX_VALUE - 1)),
                "neighbours at the top of the range");
    }

    private static void kindsNeverMeet() {
        Object[] values = { null, "", "1", 1, 1L, 1.0, 1.0f, new BigDecimal("1"), LocalDateTime.of(2026, 1, 1, 0, 0),
                0, 0.0, BigDecimal.ZERO, "n" };
        for (Object x : values)
            for (Object y : values)
                if (!sameKind(x, y))
                    Check.isTrue(!MoveChecksum.canonical(x).equals(MoveChecksum.canonical(y)),
                            "different kinds never meet: " + describe(x) + " and " + describe(y));
        for (Object value : values)
            Check.isTrue(!MoveChecksum.canonical(MoveChecksum.canonical(value)).equals(MoveChecksum.canonical(value)),
                    "text spelling a value's canonical form isn't that value: " + describe(value));
    }

    private static void floatingPointWhateverTheWidth() {
        Check.eq(MoveChecksum.canonical(0.5), MoveChecksum.canonical(0.5f), "0.5 as a Double and as a Float");
        Check.eq(MoveChecksum.canonical((double) 0.1f), MoveChecksum.canonical(0.1f),
                "a Float and the Double equal to it, as a real reads back");
        Check.isTrue(!MoveChecksum.canonical(0.1).equals(MoveChecksum.canonical(0.1f)),
                "0.1 and the float nearest it are different numbers");
        Check.eq(MoveChecksum.canonical(Double.NaN), MoveChecksum.canonical(Float.NaN), "NaN");
        Check.eq(MoveChecksum.canonical(Double.POSITIVE_INFINITY), MoveChecksum.canonical(Float.POSITIVE_INFINITY),
                "infinity");
        Check.isTrue(!MoveChecksum.canonical(Double.POSITIVE_INFINITY)
                .equals(MoveChecksum.canonical(Double.NEGATIVE_INFINITY)), "the two infinities");
        Check.isTrue(!MoveChecksum.canonical(-0.0).equals(MoveChecksum.canonical(0.0)),
                "negative zero isn't zero: PostgreSQL keeps its sign, so a change shows");
        Check.isTrue(!MoveChecksum.canonical(1.0).equals(MoveChecksum.canonical(1L)), "1.0 and 1");
    }

    private static void decimalsByValue() {
        Check.eq(MoveChecksum.canonical(new BigDecimal("1.5")), MoveChecksum.canonical(new BigDecimal("1.50")),
                "1.50 and 1.5");
        Check.eq(MoveChecksum.canonical(new BigDecimal("1000")), MoveChecksum.canonical(new BigDecimal("1E+3")),
                "1000 and 1E+3");
        Check.eq(MoveChecksum.canonical(BigDecimal.ZERO), MoveChecksum.canonical(new BigDecimal("0.000")),
                "0 and 0.000");
        Check.eq(MoveChecksum.canonical(new BigDecimal("-2.5")), MoveChecksum.canonical(new BigDecimal("-25E-1")),
                "a negative decimal with an exponent");
        Check.isTrue(!MoveChecksum.canonical(new BigDecimal("1.5")).equals(MoveChecksum.canonical(new BigDecimal("15"))),
                "1.5 and 15");
        Check.isTrue(!MoveChecksum.canonical(new BigDecimal("1.5")).equals(MoveChecksum.canonical(1.5)),
                "an exact decimal and a double");
    }

    private static void timesWithoutTrailingZeros() {
        Check.eq(MoveChecksum.canonical(LocalDateTime.of(2026, 11, 18, 10, 21, 44)),
                MoveChecksum.canonical(LocalDateTime.parse("2026-11-18T10:21:44.000")), "10:21:44 and 10:21:44.000");
        Check.isTrue(MoveChecksum.canonical(LocalDateTime.of(2026, 11, 18, 10, 21, 44, 123_000_000))
                .endsWith("2026-11-18T10:21:44.123"), "ISO, without the fraction's trailing zeros: "
                        + MoveChecksum.canonical(LocalDateTime.of(2026, 11, 18, 10, 21, 44, 123_000_000)));
        Check.isTrue(MoveChecksum.canonical(LocalDateTime.of(2026, 11, 18, 10, 21)).endsWith("T10:21:00"),
                "seconds always written: " + MoveChecksum.canonical(LocalDateTime.of(2026, 11, 18, 10, 21)));
        Check.isTrue(!MoveChecksum.canonical(LocalDateTime.of(2026, 11, 18, 10, 21, 44, 123_000_000))
                .equals(MoveChecksum.canonical(LocalDateTime.of(2026, 11, 18, 10, 21, 44, 123_000_001))),
                "a nanosecond apart");
        Check.isTrue(!MoveChecksum.canonical(LocalDateTime.of(2026, 11, 18, 10, 21, 44))
                .equals(MoveChecksum.canonical("2026-11-18T10:21:44")), "a time and text that spells it");
    }

    private static void refusesWhatItDoesNotKnow() {
        Check.throwsWith("java.lang.Boolean", () -> MoveChecksum.canonical(Boolean.TRUE), "a Boolean");
        Check.throwsWith("byte[]", () -> MoveChecksum.canonical(new byte[] { 1 }), "a blob");
        Check.throwsWith("java.sql.Timestamp",
                () -> MoveChecksum.canonical(java.sql.Timestamp.valueOf("2026-11-18 10:21:44")), "a Timestamp");
        Check.throwsWith("java.lang.Character", () -> MoveChecksum.canonical('x'), "a Character");
        try {
            MoveChecksum.canonical(Boolean.TRUE);
        } catch (IllegalArgumentException e) {
            Check.isTrue(!e.getMessage().contains("true"), "the refusal holds no value: " + e.getMessage());
        }
    }

    private static void rowsKeepTheirValuesApart() {
        Check.isTrue(!Arrays.equals(MoveChecksum.row(List.of("a", "bc")), MoveChecksum.row(List.of("ab", "c"))),
                "[a, bc] and [ab, c]");
        Check.isTrue(!Arrays.equals(MoveChecksum.row(Arrays.asList("a", null)),
                MoveChecksum.row(Arrays.asList(null, "a"))), "[a, null] and [null, a]");
        Check.isTrue(!Arrays.equals(MoveChecksum.row(Arrays.asList((Object) null)), MoveChecksum.row(List.of(""))),
                "[null] and [\"\"]");
        Check.isTrue(!Arrays.equals(MoveChecksum.row(List.of()), MoveChecksum.row(List.of(""))), "[] and [\"\"]");
        Check.isTrue(!Arrays.equals(MoveChecksum.row(List.of("\uD800")), MoveChecksum.row(List.of("?"))),
                "a lone surrogate and the character an encoder would put for it");
        Check.isTrue(!Arrays.equals(MoveChecksum.row(List.of("\u0100")), MoveChecksum.row(List.of("\u0001\u0000"))),
                "one char and two whose bytes would run together");
        Check.isTrue(Arrays.equals(MoveChecksum.row(List.of(1L, "x", 0.5f)), MoveChecksum.row(List.of(1, "x", 0.5))),
                "a row carried and the same row read back in other widths");
        Check.eq(32, MoveChecksum.row(List.of("x")).length, "SHA-256's length");
    }

    private static void sumsIgnoreTheOrderOfRows() {
        byte[] a = MoveChecksum.row(List.of(1L, "Alpha"));
        byte[] b = MoveChecksum.row(List.of(2L, "Beta"));
        byte[] c = MoveChecksum.row(Arrays.asList(3L, null));
        Check.eq(sum(a, b, c).hex(), sum(c, a, b).hex(), "A, B, C and C, A, B");
        Check.isTrue(!sum(a, b, c).hex().equals(sum(a, b).hex()), "A, B, C and A, B");
        Check.eq(3L, sum(a, b, c).rows(), "rows()");
        Check.isTrue(!sum(a, a).hex().equals(sum(a).hex()) && !sum(a, a).hex().equals(sum().hex()),
                "a row twice counts twice, and two copies don't cancel out");
        Check.eq("0".repeat(64), sum().hex(), "an empty table's sum");
        Check.eq(0L, sum().rows(), "an empty table's rows");
        Check.eq(64, sum(a).hex().length(), "64 hex digits");
        byte[] ones = new byte[32];
        Arrays.fill(ones, (byte) 0xff);
        Check.eq("f".repeat(63) + "e", sum(ones, ones).hex(), "the sum wraps around at 2^256");
        Check.throwsWith("32", () -> new MoveChecksum.Sum().add(new byte[31]), "a digest of the wrong length");
    }

    private static MoveChecksum.Sum sum(byte[]... rows) {
        MoveChecksum.Sum sum = new MoveChecksum.Sum();
        for (byte[] row : rows)
            sum.add(row);
        return sum;
    }

    /** Whether two values are of one kind: text, a whole number, floating point, an exact decimal or a time */
    private static boolean sameKind(Object x, Object y) {
        return kind(x).equals(kind(y));
    }

    private static String kind(Object value) {
        if (value == null)
            return "null";
        if (value instanceof Integer || value instanceof Long)
            return "whole";
        if (value instanceof Double || value instanceof Float)
            return "floating";
        return value.getClass().getName();
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName() + " " + value;
    }
}
