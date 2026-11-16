package com.moviemux.application.spring.bridge;

import com.moviemux.movie.domain.repository.MovieActivityReader;
import com.moviemux.user.domain.repository.UserActivityReader;
import com.moviemux.user.domain.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserActivityBridge implements UserActivityReader {
  private final UserRepository users;
  private final MovieActivityReader movies;

  @Override
  public Activity read(UUID id) {
    var user = users.findById(id).orElse(null);
    if (user == null || user.getStatistics() == null) return new Activity(0, 0, 0, 0d);
    var activity = movies.read(id);
    return new Activity(
        activity.ratingsGiven(),
        activity.registeredMovies(),
        activity.displayTime(),
        activity.averageRatingMovies());
  }
}
