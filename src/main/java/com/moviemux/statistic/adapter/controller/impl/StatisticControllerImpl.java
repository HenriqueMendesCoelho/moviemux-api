package com.moviemux.statistic.adapter.controller.impl;

import com.moviemux.legacyboard.domain.repository.LegacyBoardInterlock;
import com.moviemux.statistic.adapter.controller.StatisticController;
import com.moviemux.statistic.adapter.controller.dto.MovieStatisticResponseDto;
import com.moviemux.statistic.domain.model.MovieStatistic;
import com.moviemux.statistic.domain.usecase.MovieStatisticsUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;

@Controller
@RequiredArgsConstructor
public class StatisticControllerImpl implements StatisticController {

  private final LegacyBoardInterlock legacyBoard;
  private final MovieStatisticsUseCase movieChartsUseCase;

  @Override
  @Transactional
  public MovieStatisticResponseDto charts() {
    legacyBoard.requireLegacy();
    MovieStatistic response = movieChartsUseCase.execute();
    return new MovieStatisticResponseDto(response);
  }
}
