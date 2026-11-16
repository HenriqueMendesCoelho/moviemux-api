package com.moviemux.user.adapter.repository.postgres.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity(name = "Statistics")
@Table(name = "user_statistics")
@NoArgsConstructor
@Data
public class StatisticsEntity {

  @Id
  @Column(name = "user_id")
  private UUID id;

  @Column private Integer consecutiveFailedLoginAttempts;

  @Column(columnDefinition = "timestamp with time zone")
  private OffsetDateTime lastLoginAt;

  @JsonIgnore
  @OneToOne
  @MapsId
  @JoinColumn(name = "user_id", referencedColumnName = "id")
  private UserEntity user;

  @Builder
  public StatisticsEntity(int ratingsGiven, int registeredMovies, UserEntity user) {
    this.consecutiveFailedLoginAttempts = 0;
    this.user = user;
  }
}
