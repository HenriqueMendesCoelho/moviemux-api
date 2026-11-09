package com.moviemux.user.adapter.repository.postgres.entity;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Entity(name = "Preferences")
@Table(name = "user_preferences")
@NoArgsConstructor
@Data
@EqualsAndHashCode(of = "id")
public class PreferencesEntity {

  @Id @Column private UUID id;

  @Column private boolean notify;

  @OneToOne
  @MapsId
  @JoinColumn(name = "user_id", referencedColumnName = "id")
  private UserEntity user;

  @Builder
  public PreferencesEntity(boolean notify, UserEntity user) {
    this.notify = notify;
    this.user = user;
  }
}
