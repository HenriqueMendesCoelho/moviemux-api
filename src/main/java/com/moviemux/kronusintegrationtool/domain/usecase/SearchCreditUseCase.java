package com.moviemux.kronusintegrationtool.domain.usecase;

import com.moviemux.kronusintegrationtool.domain.model.Credit;

public interface SearchCreditUseCase {

  Credit execute(Long movieId);
}
