package com.moviemux.statistic.application.spring.controller;

import com.moviemux.statistic.adapter.controller.StatisticController;
import com.moviemux.statistic.adapter.controller.dto.MovieStatisticResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/statistic")
@RequiredArgsConstructor
public class StatisticSpringController {

  private final StatisticController controller;

  @GetMapping("/movies")
  public ResponseEntity<MovieStatisticResponseDto> sendMovieMessageDiscord() {
    MovieStatisticResponseDto response = controller.charts();

    return ResponseEntity.ok(response);
  }
}
