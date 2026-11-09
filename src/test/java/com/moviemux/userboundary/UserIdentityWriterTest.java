package com.moviemux.userboundary;

import static org.mockito.Mockito.*;

import com.moviemux.application.spring.bridge.MovieIdentityBridge;
import com.moviemux.user.adapter.repository.UserRepository;
import com.moviemux.user.adapter.repository.postgres.UserIdentityWriterPostgresAdapter;
import com.moviemux.user.adapter.repository.postgres.entity.UserEntity;
import com.moviemux.user.domain.repository.UserIdentityLookup;
import com.moviemux.user.domain.repository.UserIdentityWriter;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserIdentityWriterTest {
  @Test
  void adapterPreservesTheExistingIdentityFlush() {
    UserRepository users = mock(UserRepository.class);
    UUID id = UUID.randomUUID();
    UserEntity entity = new UserEntity();
    when(users.findById(id)).thenReturn(Optional.of(entity));
    new UserIdentityWriterPostgresAdapter(users).flush(id);
    verify(users).saveAndFlush(entity);
  }

  @Test
  void movieBridgeDelegatesThroughThePublicContract() {
    UserIdentityWriter writer = mock(UserIdentityWriter.class);
    UUID id = UUID.randomUUID();
    new MovieIdentityBridge(mock(UserIdentityLookup.class), writer).flush(id);
    verify(writer).flush(id);
  }
}
