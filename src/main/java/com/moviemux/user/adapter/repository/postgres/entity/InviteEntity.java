package com.moviemux.user.adapter.repository.postgres.entity;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Random;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Entity(name = "Invite")
@Table(name = "user_invites")
@Getter
@Setter
@EqualsAndHashCode
public class InviteEntity implements Serializable {

  private static final long serialVersionUID = 1L;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column
  private Long id;

  @Column(nullable = false, unique = true)
  private String code;

  public InviteEntity() {
    super();
    this.code = createRandomInvite();
  }

  private String createRandomInvite() {
    Random r = new Random();
    int minimum = 10000000;
    int maximum = 999999999;
    int result = r.nextInt(maximum - minimum) + minimum;

    return String.format("KB-%s", result);
  }

  public InviteEntity(Long id, String code) {
    super();
    this.id = id;
    this.code = code;
  }
}
