package com.moviemux.user.domain.model;

import lombok.Getter;

@Getter
public enum Role {
	ADM(1, "ROLE_ADM"), USER(2, "ROLE_USER");

	private int code;
	private String description;

	private Role(int code, String description) {
		this.code = code;
		this.description = description;
	}

	public static Role toEnum(Integer code) {

		if (code == null) {
			return null;
		}

		for (Role i : Role.values()) {
			if (code.equals(i.getCode())) {
				return i;
			}
		}

		throw new IllegalArgumentException("User type invalid " + code);

	}

}
