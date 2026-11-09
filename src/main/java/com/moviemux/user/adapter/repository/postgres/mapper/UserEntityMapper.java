package com.moviemux.user.adapter.repository.postgres.mapper;

import com.moviemux.user.domain.model.Preferences;
import com.moviemux.user.domain.model.Statistics;
import com.moviemux.user.domain.model.User;
import org.springframework.stereotype.Component;

/** Translates the legacy ORM representation. No persistent collection leaves this mapper. */
@Component
public class UserEntityMapper {
  public User toDomain(com.moviemux.user.adapter.repository.postgres.entity.UserEntity entity) {
    if (entity == null) return null;
    User result =
        User.builder()
            .id(entity.getId())
            .name(entity.getName())
            .email(entity.getEmail())
            .password(entity.getPassword())
            .build();
    result.setRoles(entity.getRoles());
    result.setCreatedAt(entity.getCreatedAt());
    result.setUpdatedAt(entity.getUpdatedAt());
    result.setRedefinePasswordKey(entity.getRedefinePasswordKey());
    result.setRedefinePasswordKeyCreatedAt(entity.getRedefinePasswordKeyCreatedAt());
    if (entity.getPreferences() != null) {
      Preferences p =
          Preferences.builder().notify(entity.getPreferences().isNotify()).user(result).build();
      p.setId(entity.getPreferences().getId());
      result.setPreferences(p);
    }
    if (entity.getStatistics() != null) {
      var old = entity.getStatistics();
      Statistics s = Statistics.builder().user(result).build();
      s.setId(old.getId());
      s.setConsecutiveFailedLoginAttempts(old.getConsecutiveFailedLoginAttempts());
      s.setLastLoginAt(old.getLastLoginAt());
      result.setStatistics(s);
    }
    return result;
  }

  public com.moviemux.user.adapter.repository.postgres.entity.UserEntity updateEntity(
      User source, com.moviemux.user.adapter.repository.postgres.entity.UserEntity target) {
    target.setId(source.getId());
    target.setName(source.getName());
    target.setEmail(source.getEmail());
    target.setPassword(source.getPassword());
    target.setRoles(
        source.getRoles().stream()
            .map(com.moviemux.user.domain.model.Role::getCode)
            .collect(java.util.stream.Collectors.toSet()));
    target.setCreatedAt(source.getCreatedAt());
    target.setUpdatedAt(source.getUpdatedAt());
    target.setRedefinePasswordKey(source.getRedefinePasswordKey());
    target.setRedefinePasswordKeyCreatedAt(source.getRedefinePasswordKeyCreatedAt());
    if (source.getPreferences() != null) {
      var p = target.getPreferences();
      if (p == null) p = new com.moviemux.user.adapter.repository.postgres.entity.PreferencesEntity();
      p.setId(source.getPreferences().getId());
      p.setNotify(source.getPreferences().isNotify());
      p.setUser(target);
      target.setPreferences(p);
    }
    if (source.getStatistics() != null) {
      var s = target.getStatistics();
      if (s == null) s = new com.moviemux.user.adapter.repository.postgres.entity.StatisticsEntity();
      s.setId(source.getStatistics().getId());
      s.setConsecutiveFailedLoginAttempts(
          source.getStatistics().getConsecutiveFailedLoginAttempts());
      s.setLastLoginAt(source.getStatistics().getLastLoginAt());
      s.setUser(target);
      target.setStatistics(s);
    }
    return target;
  }
}
