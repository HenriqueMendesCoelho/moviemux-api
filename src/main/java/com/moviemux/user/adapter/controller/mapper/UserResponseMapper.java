package com.moviemux.user.adapter.controller.mapper;

import com.moviemux.user.adapter.controller.dto.UserResponseAdmDto;
import com.moviemux.user.adapter.controller.dto.UserResponseDto;
import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.repository.UserActivityReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserResponseMapper {
  private final UserActivityReader activityReader;

  public UserResponseDto toResponse(User user) {
    enrich(user);
    return new UserResponseDto(user);
  }

  public UserResponseAdmDto toAdminResponse(User user) {
    enrich(user);
    return new UserResponseAdmDto(user);
  }

  private void enrich(User user) {
    var activity = activityReader.read(user.getId());
    var statistics = user.getStatistics();
    statistics.setRatingsGiven(activity.ratingsGiven());
    statistics.setRegisteredMovies(activity.registeredMovies());
    statistics.setDisplayTime(activity.displayTime());
    statistics.setAverageRatingMovies(activity.averageRatingMovies());
  }
}
