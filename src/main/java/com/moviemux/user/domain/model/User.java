package com.moviemux.user.domain.model;

import com.moviemux.user.domain.Role;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class User {
  private UUID id;
  private String name, email, password;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private Set<Role> roles = new HashSet<>();

  private Preferences preferences;
  private Statistics statistics;
  private OffsetDateTime createdAt, updatedAt;
  private String redefinePasswordKey;
  private OffsetDateTime redefinePasswordKeyCreatedAt;

  public User() {
    roles.add(Role.USER);
  }

  @Builder
  public User(
      UUID id,
      String name,
      String email,
      String password,
      Preferences preferences,
      Statistics statistics) {
    this();
    this.id = id;
    this.name = name;
    this.email = email;
    this.password = password;
    this.preferences = preferences;
    this.statistics = statistics;
  }

  public Set<Role> getRoles() {
    return Set.copyOf(roles);
  }

  public void setRoles(Set<Role> roles) {
    this.roles = new HashSet<>(roles);
  }

  public void addRole(Role role) {
    roles.add(role);
  }

  public void removeRole(Role role) {
    roles.remove(role);
  }
}
