package com.moviemux.user.usecase.impl;

import java.util.List;

import com.moviemux.user.adapter.repository.InviteRepository;
import com.moviemux.user.domain.Invite;
import com.moviemux.user.usecase.SearchInviteUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SearchInviteUseCaseImpl implements SearchInviteUseCase {

	private final InviteRepository repository;

	@Override
	public List<Invite> list() {
		return repository.findAll();
	}

}
