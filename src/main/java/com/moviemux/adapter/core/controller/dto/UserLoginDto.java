package com.moviemux.adapter.core.controller.dto;

import java.io.Serializable;

import lombok.Data;

@Data
public class UserLoginDto implements Serializable {

	private static final long serialVersionUID = 1L;

	private String email;
	private String password;
}
