package com.moviemux.user.domain.model;

import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class Preferences {
  private UUID id;
  private boolean notify;

  @Builder
  public Preferences(boolean notify, User user) {
    this.notify = notify;
    this.id = user == null ? null : user.getId();
  }
}
