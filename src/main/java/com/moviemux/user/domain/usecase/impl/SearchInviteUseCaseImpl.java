package com.moviemux.user.domain.usecase.impl;

import com.moviemux.user.domain.model.Invite;
import com.moviemux.user.domain.repository.InviteRepository;
import com.moviemux.user.domain.usecase.SearchInviteUseCase;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SearchInviteUseCaseImpl implements SearchInviteUseCase {
  private final InviteRepository repository;

  @Override
  public List<Invite> execute() {
    return repository.findAll();
  }
}
