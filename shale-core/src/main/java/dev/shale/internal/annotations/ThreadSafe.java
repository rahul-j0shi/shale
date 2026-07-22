package dev.shale.internal.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

/** The annotated type is safe for concurrent use by any thread. */
@Documented
@Target(ElementType.TYPE)
public @interface ThreadSafe {}
