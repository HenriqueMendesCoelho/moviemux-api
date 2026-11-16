package com.moviemux.kronusintegrationtool.domain.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MovieSearch {

  private Integer page;
  private List<ResultSearch> results;
  private Integer totalPages;
  private Integer totalResults;
}
