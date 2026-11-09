package com.moviemux.movie.domain.model;
import com.moviemux.user.domain.Role;
import com.moviemux.user.domain.model.UserIdentity;
import java.util.Set;
import java.util.UUID;
public record MovieActor(UUID id, String name, String email, Set<Role> roles) {
 public MovieActor { roles = Set.copyOf(roles); }
 public static MovieActor from(UserIdentity value) { return new MovieActor(value.id(),value.name(),value.email(),value.roles()); }
 public UUID getId(){return id;}
 public String getName(){return name;}
 public String getEmail(){return email;}
 public Set<Role> getRoles(){return roles;}
}
