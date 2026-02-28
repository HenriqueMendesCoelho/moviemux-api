package com.moviemux.user.usecase.impl;

import com.moviemux.user.adapter.repository.UserRepository;
import com.moviemux.user.domain.Role;
import com.moviemux.user.domain.User;
import com.moviemux.user.usecase.UpdateUserUseCase;
import com.moviemux.user.usecase.exception.UserNotAuthorizedException;
import com.moviemux.user.usecase.exception.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UpdateUserUseCaseImpl implements UpdateUserUseCase {

	private final UserRepository repository;
	private final BCryptPasswordEncoder passwordEncoder;

	@Override
	public User update(User user, UUID userId, String emailUserLoged)
			throws UserNotFoundException, UserNotAuthorizedException {
		User userLoged = repository.findByEmail(emailUserLoged);
		User userToUpdate = repository.findById(userId).orElse(null);

		if (userLoged.getId() != userId && !userLoged.getRoles().contains(Role.ADM)) {
			throw new UserNotAuthorizedException();
		}

		if (userToUpdate == null) {
			throw new UserNotFoundException();
		}

		userToUpdate.setEmail(user.getEmail());
		userToUpdate.setName(user.getName());
		userToUpdate.getPreferences().setNotify(user.getPreferences().isNotify());

		return repository.saveAndFlush(userToUpdate);
	}

	@Override
	public User updateUserProfile(UUID userId, String name, String email, boolean notify) throws UserNotFoundException {
		User userToUpdate = repository.findById(userId).orElse(null);

		if (userToUpdate == null) {
			throw new UserNotFoundException();
		}

		userToUpdate.setName(name);
		userToUpdate.setEmail(email);
		userToUpdate.getPreferences().setNotify(notify);

		return repository.saveAndFlush(userToUpdate);
	}

	@Override
	public User updateUserPassoword(UUID userId, String password, String newPassword)
			throws UserNotFoundException, UserNotAuthorizedException {
		User user = repository.findById(userId).orElse(null);

		if (user == null) {
			throw new UserNotFoundException();
		}

		boolean passwordIsValid = passwordEncoder.matches(password, user.getPassword());

		if (!passwordIsValid) {
			throw new UserNotAuthorizedException();
		}

		user.setPassword(passwordEncoder.encode(newPassword));

		return repository.saveAndFlush(user);
	}

	@Override
	public User promoteUserToAdmin(UUID userId) throws UserNotFoundException {
		User user = repository.findById(userId).orElse(null);

		if (user == null) {
			throw new UserNotFoundException();
		}

		boolean isAlreadyAdm = user.getRoles().stream().anyMatch(r -> r.getDescription() == "ROLE_ADM");

		if (isAlreadyAdm) {
			return user;
		}

		user.addRole(Role.ADM);

		return repository.saveAndFlush(user);
	}

	@Override
	public User demoteUserToAdmin(UUID userId) throws UserNotFoundException {
		User user = repository.findById(userId).orElse(null);

		if (user == null) {
			throw new UserNotFoundException();
		}

		boolean isAlreadyAdm = user.getRoles().stream().anyMatch(r -> r.getDescription() == "ROLE_ADM");

		if (!isAlreadyAdm) {
			return user;
		}

		user.removeRole(Role.ADM);

		return repository.saveAndFlush(user);
	}

	@Override
	public User blockUser(UUID userId) throws UserNotFoundException {
		User user = repository.findById(userId).orElse(null);

		if (user == null) {
			throw new UserNotFoundException();
		}

		user.getStatistics().setConsecutiveFailedLoginAttempts(10);

		return repository.saveAndFlush(user);
	}

}
