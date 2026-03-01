package com.moviemux.mail.domain;

import lombok.*;

import java.util.LinkedHashMap;

@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class SendMailTemplate {

	private static final String WELCOME_TEMPLATE_ID = "welcome";
	private static final String FORGOT_PASSWORD_TEMPLATE_ID = "reset_password";
	private static final String BLOCKED_ACCOUNT_TEMPLATE_ID = "account_blocked";
	private static final String PASSOWORD_UPDATED_TEMPLATE_ID = "password_updated";
	private static final String MOVIE_MUX_FRONTEND_URL = "https://www.moviemux.com";

	@Builder.Default
	private String from = "no-reply@moviemux.com";
	@Builder.Default
	private String name = "Movie Mux";
	private String to;
	private String templateId;
	private boolean ignoreNotifyPreferences;
	private LinkedHashMap<String, String> params;

	public static SendMailTemplate welcomeMail(String to, String username) {
		LinkedHashMap<String, String> map = new LinkedHashMap<>();
		map.put("user", username);
		return SendMailTemplate.builder()
				.to(to)
				.params(map)
				.templateId(WELCOME_TEMPLATE_ID)
				.ignoreNotifyPreferences(true)
				.build();
	}

	public static SendMailTemplate forgotPasswordMail(String to, String username, String redefinePasswordKey) {
		LinkedHashMap<String, String> map = new LinkedHashMap<>();
		map.put("user", username);
		map.put("reset_link", String.format("%s/password/change/%s", MOVIE_MUX_FRONTEND_URL, redefinePasswordKey));
		return SendMailTemplate.builder()
				.to(to)
				.params(map)
				.templateId(FORGOT_PASSWORD_TEMPLATE_ID)
				.ignoreNotifyPreferences(true)
				.build();
	}

	public static SendMailTemplate passwordUpdatedMail(String to, String username) {
		LinkedHashMap<String, String> map = new LinkedHashMap<>();
		map.put("user", username);
		return SendMailTemplate.builder()
				.to(to)
				.params(map)
				.templateId(PASSOWORD_UPDATED_TEMPLATE_ID)
				.ignoreNotifyPreferences(true)
				.build();
	}

	public static SendMailTemplate accountBlockedMail(String to, String username) {
		LinkedHashMap<String, String> map = new LinkedHashMap<>();
		map.put("user", username);
		return SendMailTemplate.builder()
				.to(to)
				.params(map)
				.templateId(BLOCKED_ACCOUNT_TEMPLATE_ID)
				.ignoreNotifyPreferences(true)
				.build();
	}

}
