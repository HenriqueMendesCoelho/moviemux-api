package com.moviemux.user.usecase.impl;

import java.util.List;

import com.moviemux.user.adapter.repository.UserRepository;
import com.moviemux.user.domain.Role;
import com.moviemux.user.domain.User;
import com.moviemux.user.usecase.SearchUserUseCase;
import com.moviemux.user.usecase.exception.UserNotAuthorizedException;
import com.moviemux.user.usecase.exception.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

@Service
@RequiredArgsConstructor
public class SearchUserUseCaseImpl implements SearchUserUseCase {

	private final UserRepository repository;

	@Override
	public User getUserByEmail(String emailUserToFind, String emailUserLoged)
			throws UserNotFoundException, UserNotAuthorizedException {
		User userLoged = repository.findByEmail(emailUserLoged);
		User user = repository.findByEmail(emailUserToFind);

		if (!emailUserToFind.equals(emailUserLoged) && !userLoged.getRoles().contains(Role.ADM)) {
			throw new UserNotAuthorizedException();
		}

		if (user == null) {
			throw new UserNotFoundException();
		}

		return user;
	}

	@Override
	public List<User> getAllUsers() throws UserNotFoundException {
		List<User> users = repository.findAll();

		if (CollectionUtils.isEmpty(users)) {
			throw new UserNotFoundException();
		}

		return users;
	}

}
