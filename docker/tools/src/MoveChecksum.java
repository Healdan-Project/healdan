// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The move's comparison of both sides (spec section 9.3, step 7): a checksum over every copied value of every row,
 * worked out in Java from each database's values, so the same from either, and added up so that it doesn't depend on
 * the order either database returns rows in. The source's side sums the values as MoveValues carried them, and the
 * target's the values PostgreSQL gives back, so any value lost or changed on the way shows.
 */
final class MoveChecksum {

    private MoveChecksum() {
    }

    /**
     * The canonical form of one carried value: its kind and its value, the same from either database. One letter for
     * the kind, so that no two kinds meet, then the value: n for null; i and the decimal for a whole number, whatever
     * its width (Byte, Short, Integer, Long or BigInteger); e and the BigDecimal with its trailing zeros stripped, so
     * that 1.50 is 1.5; d and Double.toString of the double for floating point, so that a Float is the Double equal to
     * it; t and ISO's yyyy-MM-ddTHH:mm:ss for a LocalDateTime, with the fraction's trailing zeros stripped; s and the
     * text as it is for a String.
     *
     * @throws IllegalArgumentException for a value of any other class, which names the class and nothing of the value
     */
    static String canonical(Object value) {
        if (value == null)
            return "n";
        if (value instanceof String text)
            return "s" + text;
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte)
            return "i" + ((Number) value).longValue();
        if (value instanceof BigInteger whole)
            return "i" + whole;
        if (value instanceof BigDecimal decimal)
            return "e" + decimal.stripTrailingZeros();
        if (value instanceof Double || value instanceof Float)
            return "d" + ((Number) value).doubleValue();
        if (value instanceof LocalDateTime time)
            return "t" + iso(time);
        throw new IllegalArgumentException("the move's checksum has no form for a " + value.getClass().getTypeName());
    }

    /** ISO's form, always with seconds, and with the fraction only as long as it needs to be */
    private static String iso(LocalDateTime time) {
        StringBuilder b = new StringBuilder(32).append(DateTimeFormatter.ISO_LOCAL_DATE.format(time)).append('T');
        two(b, time.getHour()).append(':');
        two(b, time.getMinute()).append(':');
        two(b, time.getSecond());
        int nanos = time.getNano();
        if (nanos != 0) {
            String fraction = Integer.toString(1_000_000_000 + nanos).substring(1);
            int end = fraction.length();
            while (fraction.charAt(end - 1) == '0')
                end--;
            b.append('.').append(fraction, 0, end);
        }
        return b.toString();
    }

    private static StringBuilder two(StringBuilder b, int n) {
        return b.append((char) ('0' + n / 10)).append((char) ('0' + n % 10));
    }

    /**
     * SHA-256 over a row's canonical values, in the plan's column order. Each value is framed by its length, and its
     * chars are taken as they are, two bytes each, so that no two rows of different values give the same bytes: not
     * [a, bc] and [ab, c], nor two strings an encoder would make one of
     */
    static byte[] row(List<Object> values) {
        MessageDigest digest = sha256();
        for (Object value : values) {
            String form = canonical(value);
            byte[] bytes = new byte[4 + 2 * form.length()];
            int length = form.length();
            bytes[0] = (byte) (length >>> 24);
            bytes[1] = (byte) (length >>> 16);
            bytes[2] = (byte) (length >>> 8);
            bytes[3] = (byte) length;
            for (int i = 0; i < length; i++) {
                char c = form.charAt(i);
                bytes[4 + 2 * i] = (byte) (c >>> 8);
                bytes[5 + 2 * i] = (byte) c;
            }
            digest.update(bytes);
        }
        return digest.digest();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java has SHA-256", e);
        }
    }

    /**
     * A table's checksum: its row digests added as 256-bit numbers, so the order rows come in doesn't matter. Unlike
     * an exclusive or, a row that's there twice counts twice
     */
    static final class Sum {

        private static final BigInteger MODULUS = BigInteger.ONE.shiftLeft(256);

        private BigInteger total = BigInteger.ZERO;

        private long rows;

        void add(byte[] rowDigest) {
            if (rowDigest.length != 32)
                throw new IllegalArgumentException("a row's digest is 32 bytes, SHA-256's, not " + rowDigest.length);
            total = total.add(new BigInteger(1, rowDigest));
            if (total.compareTo(MODULUS) >= 0)
                total = total.subtract(MODULUS);
            rows++;
        }

        long rows() {
            return rows;
        }

        /** The sum, modulo 2^256, as 64 hex digits */
        String hex() {
            return String.format("%064x", total);
        }
    }
}
