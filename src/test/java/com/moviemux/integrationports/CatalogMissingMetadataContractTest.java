package com.moviemux.integrationports;

import static org.assertj.core.api.Assertions.assertThat;
import com.moviemux.kronusintegrationtool.adapter.repository.rest.dto.MovieSummaryResponseDto;
import com.moviemux.kronusintegrationtool.adapter.repository.rest.mapper.CatalogResponseMapper;
import org.junit.jupiter.api.Test;

/** Missing provider data is not a license to persist a localized placeholder or invented metadata.
 * This contract does not enable a catalog insert into the still-incompatible legacy schema.
 */
class CatalogMissingMetadataContractTest {
  @Test void missingDirectorDescriptionAndPortugueseTitleRemainAbsentWhileFallbackCandidatesRemainSeparate() {
    var response=MovieSummaryResponseDto.builder().tmdbId(123L).originalTitle("Original title")
        .englishTitle("English title").build();
    var mapped=CatalogResponseMapper.summary(response);
    assertThat(mapped.getDirector()).isNull();
    assertThat(mapped.getDescription()).isNull();
    assertThat(mapped.getPortugueseTitle()).isNull();
    assertThat(mapped.getOriginalTitle()).isEqualTo("Original title");
    assertThat(mapped.getEnglishTitle()).isEqualTo("English title");
    assertThat(mapped.getTmdbId()).isEqualTo(123L);
  }
  @Test void absenceOfEveryTitleDoesNotInventLocalizedFallbackInStoredTransportData() {
    var mapped=CatalogResponseMapper.summary(MovieSummaryResponseDto.builder().tmdbId(456L).build());
    assertThat(mapped.getOriginalTitle()).isNull();
    assertThat(mapped.getEnglishTitle()).isNull();
    assertThat(mapped.getPortugueseTitle()).isNull();
    assertThat(mapped.getDirector()).isNull();
    assertThat(mapped.getDescription()).isNull();
  }
}
