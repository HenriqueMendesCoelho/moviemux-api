package com.moviemux.user.adapter.controller.dto;

import com.moviemux.user.domain.model.Preferences;
import lombok.Data;

@Data
public class PreferencesResponseDto {

  private boolean notify;

  public PreferencesResponseDto(Preferences preferences) {
    notify = preferences.isNotify();
  }
}
