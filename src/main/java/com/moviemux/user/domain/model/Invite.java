package com.moviemux.user.domain.model;

public record Invite(Long id, String code) {
  public Long getId() {
    return id;
  }

  public String getCode() {
    return code;
  }
}
