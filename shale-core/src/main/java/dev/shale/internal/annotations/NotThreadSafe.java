package dev.shale.internal.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

/** The annotated type is single-threaded; its Javadoc names the owning thread or role. */
@Documented
@Target(ElementType.TYPE)
public @interface NotThreadSafe {}
