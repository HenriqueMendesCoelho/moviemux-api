package com.moviemux.userboundary;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.moviemux.user.adapter.controller.dto.UserRequestDto;
import com.moviemux.user.adapter.controller.mapper.UserRequestMapper;
import com.moviemux.user.adapter.controller.mapper.UserResponseMapper;
import com.moviemux.user.domain.model.Preferences;
import com.moviemux.user.domain.model.Statistics;
import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.repository.UserActivityReader;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserTransportMapperTest {
  @Test
  void registrationMapsTransportWithoutLosingInvitationOrNotificationChoice() {
    var dto = new UserRequestDto();
    dto.setName("Synthetic");
    dto.setEmail("user@example.test");
    dto.setPassword("Synthetic1!");
    dto.setNotify(true);
    dto.setInviteCode("KB-synthetic");
    var command = new UserRequestMapper().toRegistration(dto);
    assertThat(command.inviteCode()).isEqualTo("KB-synthetic");
    assertThat(command.user().getId()).isNull();
    assertThat(command.user().getName()).isEqualTo(dto.getName());
    assertThat(command.user().getEmail()).isEqualTo(dto.getEmail());
    assertThat(command.user().getPassword()).isEqualTo(dto.getPassword());
    assertThat(command.user().getPreferences().isNotify()).isTrue();
  }

  @Test
  void profilePayloadUsesActivityProjectionAndNeverExposesAuthenticationSecrets() throws Exception {
    var user =
        User.builder()
            .id(UUID.randomUUID())
            .name("Synthetic")
            .email("user@example.test")
            .password("private-hash")
            .build();
    user.setPreferences(Preferences.builder().notify(true).user(user).build());
    user.setStatistics(Statistics.builder().user(user).build());
    user.setRedefinePasswordKey("private-reset");
    var response =
        new UserResponseMapper(
                id -> {
                  assertThat(id).isEqualTo(user.getId());
                  return new UserActivityReader.Activity(2, 3, 180, 7.5);
                })
            .toResponse(user);
    var json =
        new ObjectMapper()
            .findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .valueToTree(response);
    assertThat(json.path("statistics").path("ratings_given").asInt()).isEqualTo(2);
    assertThat(json.path("statistics").path("registered_movies").asInt()).isEqualTo(3);
    assertThat(json.path("statistics").path("display_time").asInt()).isEqualTo(180);
    assertThat(json.path("statistics").path("average_rating_movies").asDouble()).isEqualTo(7.5);
    assertThat(json.toString())
        .doesNotContain("private-hash", "private-reset", "password", "redefine_password");
  }
}
