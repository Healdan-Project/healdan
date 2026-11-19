// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Reads one value as the type a caller asks for, converting in Java what
 * MariaDB's driver converts and PostgreSQL's refuses.
 * <p>
 * {@link HibernatePersistentObjectDAO}'s typed reads asked the driver for the
 * type with {@code getObject(1, type)}. PostgreSQL's driver gives a
 * {@code Long} only from a {@code bigint}, and refuses one from an {@code int}
 * or a {@code numeric} such as a {@code sum()}. Its refusal marks the caller's
 * transaction for rollback, and the commit then rolls back without a word, so
 * the caller's writes are lost. A number held exactly (an integer type or a
 * {@link BigDecimal}) is therefore converted here when the type holds it
 * exactly too. Everything else, a fraction asked for as a whole number, a
 * number the type can't hold, a {@code Double} or a {@code Float} (whose text
 * the driver converts in its own way), and anything that isn't a number, goes
 * to the driver as before, so MariaDB and HSQLDB answer as they did.
 * <p>
 * Healdan addition: lives in its own file so upstream merges don't touch it.
 */
public final class HealdanTypedReads {

    private HealdanTypedReads() {
    }

    /**
     * @param rs the result set, on a row
     * @param value its first column, as rs.getObject(1) returned it
     * @param type the type asked for
     * @return null for SQL NULL; the value itself when its class is the
     *         type; a number held exactly (an integer type, {@code BigInteger}
     *         or {@code BigDecimal}) converted to Long or Integer when that
     *         holds it exactly, or to Double, Float, BigDecimal or plain text;
     *         otherwise what rs.getObject(1, type) gives
     *
     * @throws SQLException what the driver throws, when it is asked
     */
    public static <R> R read(ResultSet rs, Object value, Class<R> type) throws SQLException {
        if (value == null)
            return null;
        if (value.getClass() == type)
            return type.cast(value);

        BigDecimal number = exactNumber(value);
        if (number != null) {
            Object converted = convert(number, type);
            if (converted != null)
                return type.cast(converted);
        }
        return rs.getObject(1, type);
    }

    /**
     * @return the value as a BigDecimal when it is held exactly, otherwise null
     */
    private static BigDecimal exactNumber(Object value) {
        if (value instanceof BigDecimal decimal)
            return decimal;
        if (value instanceof BigInteger integer)
            return new BigDecimal(integer);
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte)
            return BigDecimal.valueOf(((Number) value).longValue());
        return null;
    }

    /**
     * @return the number as the type, or null when this doesn't convert it
     */
    private static Object convert(BigDecimal number, Class<?> type) {
        try {
            if (type == Long.class)
                return number.longValueExact();
            if (type == Integer.class)
                return number.intValueExact();
        } catch (ArithmeticException e) {
            // A fraction, or more than the type holds: the driver decides, as before
            return null;
        }
        if (type == Double.class)
            return number.doubleValue();
        if (type == Float.class)
            return number.floatValue();
        if (type == BigDecimal.class)
            return number;
        if (type == String.class)
            return number.toPlainString();
        return null;
    }
}
