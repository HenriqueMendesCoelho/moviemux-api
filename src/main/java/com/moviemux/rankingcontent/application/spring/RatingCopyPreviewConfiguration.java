package com.moviemux.rankingcontent.application.spring;

import com.moviemux.rankingcontent.adapter.security.AesGcmRatingCopyPreviewSealer;
import com.moviemux.rankingcontent.domain.port.SealOwnRatingCopyPreview;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
public class RatingCopyPreviewConfiguration {
  @Bean SealOwnRatingCopyPreview ratingCopyPreviewSealer(
      @Value("${ranking.rating-copy.preview-key:}") String key,
      @Value("${ranking.rating-copy.preview-ttl-seconds:}") String ttl) {
    return new AesGcmRatingCopyPreviewSealer(key,ttl,Clock.systemUTC());
  }
}
