package com.moviemux.user.adapter.controller.mapper;

import com.moviemux.user.adapter.controller.dto.UserRequestDto;
import com.moviemux.user.domain.command.RegisterUserCommand;
import com.moviemux.user.domain.model.Preferences;
import com.moviemux.user.domain.model.User;
import org.springframework.stereotype.Component;

@Component
public class UserRequestMapper {
  public User toModel(UserRequestDto request) {
    return User.builder()
        .name(request.getName())
        .email(request.getEmail())
        .password(request.getPassword())
        .preferences(Preferences.builder().notify(request.isNotify()).build())
        .build();
  }

  public RegisterUserCommand toRegistration(UserRequestDto request) {
    return new RegisterUserCommand(toModel(request), request.getInviteCode());
  }
}
