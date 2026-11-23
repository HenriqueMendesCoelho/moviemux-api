package com.moviemux.rankingcontent.application.spring;

import com.moviemux.ranking.domain.repository.CheckRankingCatalogEntryAccess;
import com.moviemux.rankingcontent.domain.port.ImportTmdbCatalogEntry;
import com.moviemux.rankingcontent.domain.port.TmdbMovieDetailsProvider;
import com.moviemux.rankingcontent.domain.port.TmdbPickerProvider;
import com.moviemux.rankingcontent.domain.usecase.ImportTmdbCatalogEntryUseCase;
import com.moviemux.rankingcontent.domain.usecase.SearchTmdbPickerUseCase;
import com.moviemux.rankingcontent.domain.usecase.impl.ImportTmdbCatalogEntryUseCaseImpl;
import com.moviemux.rankingcontent.domain.usecase.impl.SearchTmdbPickerUseCaseImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class TmdbImportConfiguration {
  @Bean
  ImportTmdbCatalogEntryUseCase tmdbImportUseCase(CheckRankingCatalogEntryAccess access,
      ImportTmdbCatalogEntry writes, TmdbMovieDetailsProvider provider,
      @Value("${ranking.catalog.tmdb-import-enabled:false}") boolean enabled) {
    return new ImportTmdbCatalogEntryUseCaseImpl(access, writes, provider, enabled);
  }

  @Bean
  SearchTmdbPickerUseCase tmdbPickerUseCase(CheckRankingCatalogEntryAccess access,
      TmdbPickerProvider provider, Environment environment) {
    return new SearchTmdbPickerUseCaseImpl(access, provider,
        () -> Boolean.TRUE.equals(environment.getProperty("ranking.catalog.tmdb-import-enabled", Boolean.class, false)));
  }
}
