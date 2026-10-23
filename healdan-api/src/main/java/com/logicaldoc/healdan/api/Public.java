package com.logicaldoc.healdan.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a resource method that needs no token. Every other method needs one.
 * {@code openapi.yaml} says {@code security: []} for exactly these
 * (RouteCoverageTest).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Public {
}
