package com.moviemux.user.usecase.impl;

import com.moviemux.user.adapter.repository.InviteRepository;
import com.moviemux.user.domain.Invite;
import com.moviemux.user.usecase.CreateInviteUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CreateInviteUseCaseImpl implements CreateInviteUseCase {

	private final InviteRepository repository;

	@Override
	public Invite create() {

		Invite invite;

		do {
			invite = new Invite();
		} while (repository.findByCode(invite.getCode()) != null);

		return repository.saveAndFlush(invite);
	}

}
