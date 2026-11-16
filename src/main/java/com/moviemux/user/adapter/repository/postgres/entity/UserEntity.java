package com.moviemux.user.adapter.repository.postgres.entity;

import com.moviemux.user.domain.model.Role;
import jakarta.persistence.*;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity(name = "User")
@Table(name = "\"user\"")
@Getter
@Setter
@EqualsAndHashCode(of = "id")
public class UserEntity implements Serializable {

  private static final long serialVersionUID = 1L;

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  @Column
  private UUID id;

  @Column(length = 100, nullable = false)
  private String name;

  @Column(unique = true, length = 100, nullable = false)
  private String email;

  @Column(length = 70, nullable = false)
  private String password;

  @CollectionTable
  @ElementCollection(fetch = FetchType.LAZY)
  private Set<Integer> roles = new HashSet<>();

  @OneToOne(mappedBy = "user", cascade = CascadeType.ALL)
  @PrimaryKeyJoinColumn
  private PreferencesEntity preferences;

  @OneToOne(mappedBy = "user", cascade = CascadeType.ALL)
  @PrimaryKeyJoinColumn
  private StatisticsEntity statistics;

  @CreationTimestamp
  @Column(nullable = false, columnDefinition = "timestamp with time zone")
  private OffsetDateTime createdAt;

  @UpdateTimestamp
  @Column(columnDefinition = "timestamp with time zone")
  private OffsetDateTime updatedAt;

  @Column(length = 200, nullable = true)
  private String redefinePasswordKey;

  @Column(nullable = true, columnDefinition = "timestamp with time zone")
  private OffsetDateTime redefinePasswordKeyCreatedAt;

  public UserEntity() {
    addRole(Role.USER);
  }

  @Builder
  public UserEntity(
      UUID id,
      String name,
      String email,
      String password,
      PreferencesEntity preferences,
      StatisticsEntity statistics) {
    this.id = id;
    this.name = name;
    this.email = email;
    this.password = password;
    this.preferences = preferences;
    this.statistics = statistics;
    addRole(Role.USER);
  }

  public Set<Role> getRoles() {
    return roles.stream().map(i -> Role.toEnum(i)).collect(Collectors.toSet());
  }

  public void addRole(Role role) {
    roles.add(role.getCode());
  }

  public void removeRole(Role role) {
    roles.remove(role.getCode());
  }
}
