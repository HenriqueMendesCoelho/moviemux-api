package com.moviemux.user.domain.usecase.impl;

import com.moviemux.user.domain.model.Invite;
import com.moviemux.user.domain.repository.InviteRepository;
import com.moviemux.user.domain.usecase.CreateInviteUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CreateInviteUseCaseImpl implements CreateInviteUseCase {
  private final InviteRepository repository;
  private final com.moviemux.user.domain.repository.CodeGenerator codes;

  @Override
  public Invite execute() {

    Invite invite;

    do {
      invite = new Invite(null, codes.invitationCode());
    } while (repository.findByCode(invite.getCode()) != null);

    return repository.save(invite);
  }
}
