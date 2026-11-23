package com.moviemux.legacyboard.application.spring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks an exact HTTP handler whose first protected operation acquires the legacy phase lock. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface LegacyBoardEndpoint {}
