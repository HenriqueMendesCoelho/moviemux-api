package com.moviemux.mail.domain.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable public mail command, independent of the provider's HTTP representation. */
public record SendMailTemplate(String to, String templateId, Map<String, String> metadata) {
  private static final String FRONTEND_URL = "https://www.moviemux.com";

  public SendMailTemplate {
    metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
  }

  public static SendMailTemplate welcomeMail(String to, String username) {
    return accountMail(to, username, "welcome");
  }

  public static SendMailTemplate forgotPasswordMail(String to, String username, String key) {
    var metadata = accountMetadata(username);
    metadata.put("reset_link", FRONTEND_URL + "/password/change/" + key);
    return new SendMailTemplate(to, "reset_password", metadata);
  }

  public static SendMailTemplate passwordUpdatedMail(String to, String username) {
    return accountMail(to, username, "password_updated");
  }

  public static SendMailTemplate accountBlockedMail(String to, String username) {
    return accountMail(to, username, "account_blocked");
  }

  private static SendMailTemplate accountMail(String to, String username, String templateId) {
    return new SendMailTemplate(to, templateId, accountMetadata(username));
  }

  private static LinkedHashMap<String, String> accountMetadata(String username) {
    var metadata = new LinkedHashMap<String, String>();
    metadata.put("user", username);
    return metadata;
  }
}
