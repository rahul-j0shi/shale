package dev.shale.internal.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

/** The annotated type has no mutable state reachable after construction. */
@Documented
@Target(ElementType.TYPE)
public @interface Immutable {}
