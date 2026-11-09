package com.moviemux.user.application.spring;

import com.moviemux.user.domain.usecase.RequestPasswordResetUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AsyncPasswordReset {
  private final RequestPasswordResetUseCase operation;

  @Async
  public void request(String email) {
    operation.execute(email);
  }
}
