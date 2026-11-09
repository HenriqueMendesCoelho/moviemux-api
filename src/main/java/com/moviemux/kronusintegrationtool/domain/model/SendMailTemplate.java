package com.moviemux.kronusintegrationtool.domain.model;

import java.util.LinkedHashMap;
import java.util.stream.Stream;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class SendMailTemplate {

  private static final String WELCOME_TEMPLATEID = "d-c1f102718d3c4a5da8ea81e418676469";
  private static final String FORGOT_PASSWORD_TEMPLATEID = "d-9cd809526c25440eb5722cbe68221491";
  private static final String BLOCKED_ACCOUNT_TEMPLATEID = "d-0a10cc53f5df49a6822321c209ad6b69";
  private static final String[] MOVIE_MUX_FRONTEND_URL =
      Stream.of("cine_url", "https://www.moviemux.com").toArray(String[]::new);

  @Builder.Default private String from = "no-reply@moviemux.com";
  @Builder.Default private String name = "Movie Mux";
  private String to;
  private String templateId;
  private String subject;
  private boolean ignoreNotifyPreferences;
  private LinkedHashMap<String, String> params;

  public static SendMailTemplate welcomeMail(String to, String username) {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("user", username);
    map.put(MOVIE_MUX_FRONTEND_URL[0], MOVIE_MUX_FRONTEND_URL[1]);
    return SendMailTemplate.builder()
        .to(to)
        .subject(String.format("%s, bem-vindo ao Cineminha!", username))
        .params(map)
        .templateId(WELCOME_TEMPLATEID)
        .ignoreNotifyPreferences(true)
        .build();
  }

  public static SendMailTemplate forgotPasswordMail(
      String to, String username, String redefinePasswordKey) {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("user", username);
    map.put(
        MOVIE_MUX_FRONTEND_URL[0],
        String.format("%s/password/change/%s", MOVIE_MUX_FRONTEND_URL[1], redefinePasswordKey));
    return SendMailTemplate.builder()
        .to(to)
        .subject(String.format("%s, sua solicitação de recuperação de senha!", username))
        .params(map)
        .templateId(FORGOT_PASSWORD_TEMPLATEID)
        .ignoreNotifyPreferences(true)
        .build();
  }

  public static SendMailTemplate accountBlockedMail(String to, String username) {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("user", username);
    return SendMailTemplate.builder()
        .to(to)
        .subject(String.format("%s, sua conta foi bloqueada!", username))
        .params(map)
        .templateId(BLOCKED_ACCOUNT_TEMPLATEID)
        .ignoreNotifyPreferences(true)
        .build();
  }
}
