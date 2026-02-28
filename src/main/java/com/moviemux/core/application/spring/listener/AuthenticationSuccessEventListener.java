package com.moviemux.core.application.spring.listener;

import com.moviemux.user.adapter.repository.UserRepository;
import com.moviemux.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationListener;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
@RequiredArgsConstructor
public class AuthenticationSuccessEventListener implements ApplicationListener<AuthenticationSuccessEvent> {

	private final UserRepository repository;

	@Override
	public void onApplicationEvent(AuthenticationSuccessEvent event) {
		String email = (String) event.getAuthentication().getName();

		if (StringUtils.isEmpty(email)) {
			return;
		}

		updateUserStatistics(email);
	}

	private void updateUserStatistics(String email) {
		User user = repository.findByEmail(email);

		if (user == null) {
			return;
		}

		user.getStatistics().setConsecutiveFailedLoginAttempts(0);
		user.getStatistics().setLastLoginAt(OffsetDateTime.now());

		repository.saveAndFlush(user);
	}

}
