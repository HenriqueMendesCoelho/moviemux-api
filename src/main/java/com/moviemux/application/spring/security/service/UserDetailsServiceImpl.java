package com.moviemux.application.spring.security.service;

import com.moviemux.application.spring.security.UserSS;
import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

  private final UserRepository repository;

  @Override
  public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
    User user = repository.findByEmail(email);

    if (user == null) {
      throw new UsernameNotFoundException(email);
    }

    return new UserSS(
        user.getId(),
        user.getName(),
        user.getEmail(),
        user.getPassword(),
        user.getStatistics().getConsecutiveFailedLoginAttempts(),
        user.getRoles());
  }
}
