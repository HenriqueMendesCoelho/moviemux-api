package com.moviemux.user.domain.registration;

import com.moviemux.user.domain.exception.InviteNotValidException;

/** Transactional final registration policy; never inferred from a rendered form. */
public interface RegistrationGate {
  record Decision(RegistrationMode mode, boolean bootstrap) {}
  Decision authorize(String email, String inviteCode) throws InviteNotValidException;
  void complete(String email, Decision decision);
  boolean existingEmail(String email);
}
