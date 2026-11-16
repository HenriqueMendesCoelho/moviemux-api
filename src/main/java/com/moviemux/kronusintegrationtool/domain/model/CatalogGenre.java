package com.moviemux.kronusintegrationtool.domain.model;

public record CatalogGenre(Long id, String name) {
  public Long getId() {
    return id;
  }

  public String getName() {
    return name;
  }
}
