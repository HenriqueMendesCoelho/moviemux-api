package com.moviemux.application.spring.bridge;
import com.moviemux.movie.domain.model.MovieActor;
import com.moviemux.movie.domain.repository.MovieIdentityRepository;
import com.moviemux.user.domain.repository.UserIdentityLookup;
import com.moviemux.user.adapter.repository.UserRepository;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class MovieIdentityBridge implements MovieIdentityRepository {
 private final UserIdentityLookup identities;
 private final UserRepository legacyUsers;
 public MovieIdentityBridge(UserIdentityLookup identities, UserRepository legacyUsers){this.identities=identities;this.legacyUsers=legacyUsers;}
 public Optional<MovieActor> findById(UUID id){return identities.find(id).map(MovieActor::from);}
 public MovieActor findByEmail(String email){return identities.findByEmail(email).map(MovieActor::from).orElse(null);}
 public void flush(UUID id){legacyUsers.saveAndFlush(legacyUsers.findById(id).orElse(null));}
}
