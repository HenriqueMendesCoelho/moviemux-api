package com.moviemux.user.adapter.controller.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.Length;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserUpdateProfileRequestDto {

  @NotBlank
  @Length(max = 100)
  @Length(min = 3)
  private String name;

  @NotBlank @Email private String email;

  private boolean notify;
}
