package com.moviemux.user.domain.usecase.impl;

import com.moviemux.user.domain.model.Invite;
import com.moviemux.user.domain.repository.InviteRepository;
import com.moviemux.user.domain.usecase.DeleteInviteUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DeleteInviteUseCaseImpl implements DeleteInviteUseCase {
  private final InviteRepository repository;

  @Override
  public void execute(String code) {
    Invite invite = repository.findByCode(code);

    if (invite == null) {
      return;
    }

    repository.delete(invite);
  }
}
