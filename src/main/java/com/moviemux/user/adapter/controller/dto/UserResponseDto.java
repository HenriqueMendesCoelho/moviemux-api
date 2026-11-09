package com.moviemux.user.adapter.controller.dto;

import com.moviemux.user.domain.model.User;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.Data;

@Data
public class UserResponseDto {

  private UUID id;
  private String name;
  private String email;
  private Set<String> roles = new HashSet<>();
  private PreferencesResponseDto preferences;
  private StatisticsResponseDto statistics;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  public UserResponseDto(User user) {

    id = user.getId();
    name = user.getName();
    email = user.getEmail();
    roles =
        user.getRoles().stream()
            .map(r -> r.getDescription().split("_")[1])
            .collect(Collectors.toSet());
    preferences = new PreferencesResponseDto(user.getPreferences());
    statistics = new StatisticsResponseDto(user.getStatistics());
    createdAt = user.getCreatedAt();
    updatedAt = user.getUpdatedAt();
  }
}
