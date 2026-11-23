package com.moviemux.rankingcontent.domain.model;

/** Single authorized snapshot across both exact scopes. Null source means no owned rating. */
public record RatingCopyPreviewSnapshot(RatingCopySourceVersion source, boolean destinationExists) {}
