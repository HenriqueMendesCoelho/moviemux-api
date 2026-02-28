package com.moviemux.user.usecase.impl;

import com.moviemux.user.adapter.repository.InviteRepository;
import com.moviemux.user.domain.Invite;
import com.moviemux.user.usecase.DeleteInviteUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DeleteInviteUseCaseImpl implements DeleteInviteUseCase {

	private final InviteRepository repository;

	@Override
	public void delete(String code) {
		Invite invite = repository.findByCode(code);

		if (invite == null) {
			return;
		}

		repository.delete(invite);
	}

}
