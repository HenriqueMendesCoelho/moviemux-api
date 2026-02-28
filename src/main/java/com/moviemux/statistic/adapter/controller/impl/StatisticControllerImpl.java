package com.moviemux.statistic.adapter.controller.impl;

import com.moviemux.statistic.adapter.controller.StatisticController;
import com.moviemux.statistic.adapter.controller.dto.MovieStatisticResponseDto;
import com.moviemux.statistic.domain.MovieStatistic;
import com.moviemux.statistic.usecase.MovieStatisticsUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class StatisticControllerImpl implements StatisticController {

	private final MovieStatisticsUseCase movieChartsUseCase;

	@Override
	public MovieStatisticResponseDto charts() {
		MovieStatistic response = movieChartsUseCase.getStatistics();
		return new MovieStatisticResponseDto(response);
	}

}
