package com.moviemux.user.usecase.impl;

import com.moviemux.kronusintegrationtool.adapter.repository.rest.KronusIntegrationToolRepository;
import com.moviemux.kronusintegrationtool.domain.SendMailTemplate;
import com.moviemux.user.adapter.repository.InviteRepository;
import com.moviemux.user.adapter.repository.UserRepository;
import com.moviemux.user.domain.*;
import com.moviemux.user.usecase.CreateUserUseCase;
import com.moviemux.user.usecase.exception.DuplicatedUserException;
import com.moviemux.user.usecase.exception.InviteNotValidException;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Log4j2
@RequiredArgsConstructor
public class CreateUserUseCaseImpl implements CreateUserUseCase {

	private final KronusIntegrationToolRepository kronusIntegrationToolRepository;
	private final UserRepository userRepository;
	private final InviteRepository inviteRepository;
	private final BCryptPasswordEncoder passwordEncoder;

	@Override
	public User save(User user, String inviteCode) throws DuplicatedUserException, InviteNotValidException {
		boolean isFirstUser = userRepository.count() == 0;
		if (isFirstUser) {
			user.addRole(Role.ADM);
		} else {
			Invite invite = Optional.ofNullable(inviteRepository.findByCode(inviteCode))
					.orElseThrow(InviteNotValidException::new);

			inviteRepository.delete(invite);
		}
		if (userRepository.findByEmail(user.getEmail()) != null) {
			throw new DuplicatedUserException();
		}

		user.setPassword(passwordEncoder.encode(user.getPassword()));
		user.setStatistics(Statistics.builder().user(user).ratingsGiven(0).registeredMovies(0).build());
		user.setPreferences(Preferences.builder().user(user).notify(true).build());

		User userCreated = userRepository.saveAndFlush(user);

		sendWelcomeMail(userCreated);

		return userCreated;
	}

	private void sendWelcomeMail(User user) {
		try {
			kronusIntegrationToolRepository.sendMailTemplate(
					SendMailTemplate.welcomeMail(user.getEmail(), user.getName()));
		} catch (Exception e) {
			log.error("Error to send welcome mail to {}", user.getName());
		}
	}
}
