package com.moviemux.manualcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.moviemux.movie.domain.exception.InvalidManualCatalogMovieException;
import com.moviemux.movie.domain.exception.ManualCatalogForbiddenException;
import com.moviemux.movie.domain.exception.ManualCatalogCommandConflictException;
import com.moviemux.movie.domain.model.ManualCanonicalMovie.Admin;
import com.moviemux.movie.domain.model.ManualCanonicalMovie.Command;
import com.moviemux.movie.domain.model.ManualCanonicalMovie.Result;
import com.moviemux.movie.domain.repository.ManualCatalogMovies;
import com.moviemux.movie.domain.usecase.impl.ManageManualCatalogMovieUseCaseImpl;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ManualCatalogMovieUseCaseTest {
  private final ManualCatalogMovies port=mock(ManualCatalogMovies.class);
  private final ManageManualCatalogMovieUseCaseImpl service=new ManageManualCatalogMovieUseCaseImpl(port);
  private final UUID actor=UUID.randomUUID(), command=UUID.randomUUID();
  private final Admin admin=new Admin(actor,true);

  @Test void onlyCurrentEnabledGlobalAdminMayReachTheWrite() {
    for(var denied:new Admin[]{null,new Admin(null,true),new Admin(actor,false),admin}) {
      assertThatThrownBy(()->service.requireCurrentAdmin(denied)).isInstanceOf(ManualCatalogForbiddenException.class);
      assertThatThrownBy(()->service.create(denied,valid())).isInstanceOf(ManualCatalogForbiddenException.class);
    }
    verify(port, times(2)).currentAdmin(actor);
    verifyNoMoreInteractions(port);
    when(port.currentAdmin(actor)).thenReturn(true);
    service.requireCurrentAdmin(admin);
  }

  @Test void normalizesEquivalentIntentsWithoutAddingUnknownData() {
    when(port.currentAdmin(actor)).thenReturn(true);
    var expected=new Result(UUID.randomUUID(),true);
    when(port.create(eq(actor),any(),anyString())).thenReturn(expected);
    var date=LocalDate.of(2024,2,29);
    assertThat(service.create(admin,new Command(command,"  Uma obra ",null," Original ","Direção\t ",
        "linha\r\nseguinte ",date,90,List.of(8L,2L)))).isEqualTo(expected);
    service.create(admin,new Command(command,"Uma obra",null,"Original","Direção","linha\r\nseguinte",date,90,List.of(2L,8L)));
    service.create(admin,valid());
    var commands=ArgumentCaptor.forClass(Command.class);
    var fingerprints=ArgumentCaptor.forClass(String.class);
    verify(port,times(3)).create(eq(actor),commands.capture(),fingerprints.capture());
    assertThat(commands.getAllValues().getFirst())
        .isEqualTo(new Command(command,"Uma obra",null,"Original","Direção","linha\r\nseguinte",date,90,List.of(2L,8L)));
    assertThat(commands.getAllValues().get(0)).isEqualTo(commands.getAllValues().get(1));
    assertThat(commands.getAllValues().get(0).toString()).isEqualTo("ManualCanonicalMovie.Command");
    assertThat(fingerprints.getAllValues().get(0)).matches("[0-9a-f]{64}").isEqualTo(fingerprints.getAllValues().get(1));
    assertThat(fingerprints.getAllValues().get(2)).isNotEqualTo(fingerprints.getAllValues().get(1));
  }

  @Test void malformedFieldsAreNotPersistedOrFingerprinted() {
    when(port.currentAdmin(actor)).thenReturn(true);
    var invalid=new ArrayList<Command>();
    invalid.add(null);
    invalid.add(new Command(null,"Título",null,null,null,null,null,null,List.of()));
    invalid.add(new Command(new UUID(0,0),"Título",null,null,null,null,null,null,List.of()));
    invalid.add(new Command(new UUID(0x4000,0),"Título",null,null,null,null,null,null,List.of()));
    invalid.add(new Command(command,null,null,null,null,null,null,null,List.of()));
    invalid.add(new Command(command,"Título",null,null,null,null,null,null,null));
    for(var title:List.of(" ", "x".repeat(151),"Some"+(char)0+"thing","a"+(char)0xd800+"z","a\nb"))invalid.add(
        new Command(command,title,null,null,null,null,null,null,List.of()));
    for(var description:List.of(" ","x".repeat(4001),"as\tb")) invalid.add(
        new Command(command,"Título",null,null,null,description,null,null,List.of()));
    for(var runtime:List.of(0,1000))invalid.add(new Command(command,"Título",null,null,null,null,null,runtime,List.of()));
    for(var date:List.of(LocalDate.of(0,1,1),LocalDate.of(10000,1,1)))
      invalid.add(new Command(command,"Título",null,null,null,null,date,null,List.of()));
    for(List<Long> genres:List.of(java.util.Collections.<Long>singletonList(null),List.of(0L),
        List.of(9007199254740992L),List.of(1L,1L),java.util.Collections.nCopies(33,1L)))
      invalid.add(new Command(command,"Título",null,null,null,null,null,null,genres));
    for(var candidate:invalid)assertThatThrownBy(()->service.create(admin,candidate))
        .isInstanceOf(InvalidManualCatalogMovieException.class);
    verify(port,never()).create(any(),any(),any());
  }

  @Test void commandConflictPropagatesWithoutRetryingOrRegeneratingIdentity() {
    when(port.currentAdmin(actor)).thenReturn(true);
    var conflict=new ManualCatalogCommandConflictException();
    when(port.create(eq(actor),any(),anyString())).thenThrow(conflict);
    assertThatThrownBy(()->service.create(admin,valid())).isSameAs(conflict);
    verify(port).create(eq(actor),any(),anyString());
  }

  private Command valid(){return new Command(command,null,"Title",null,null,null,null,null,List.of());}
}
