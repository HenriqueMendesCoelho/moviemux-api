package com.moviemux.kronusintegrationtool.domain.model;

import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WatchProviders {

  private Long id;
  private Map<String, Country> results;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Country {
    private String link;
    private List<Provider> flatrate;
    private List<Provider> rent;
    private List<Provider> buy;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Provider {
    private String logoPath;
    private Long providerId;
    private String providerName;
    private int displayPriority;
  }
}
