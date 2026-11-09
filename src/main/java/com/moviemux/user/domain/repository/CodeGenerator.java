package com.moviemux.user.domain.repository;

public interface CodeGenerator {
  String invitationCode();

  String passwordResetKey();
}
