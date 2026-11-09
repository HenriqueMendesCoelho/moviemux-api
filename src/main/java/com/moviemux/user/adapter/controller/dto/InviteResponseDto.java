package com.moviemux.user.adapter.controller.dto;

import com.moviemux.user.domain.model.Invite;
import lombok.Data;

@Data
public class InviteResponseDto {

  private String code;

  public InviteResponseDto(Invite invite) {
    code = invite.getCode();
  }
}
