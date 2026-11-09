package com.moviemux.user.domain.usecase;

import com.moviemux.user.domain.model.Invite;
import java.util.List;

public interface SearchInviteUseCase {

  List<Invite> execute();
}
