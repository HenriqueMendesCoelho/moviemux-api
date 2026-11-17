package com.moviemux.rankingcontent;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.moviemux.rankingcontent.adapter.controller.AddExistingCatalogEntryController;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.AddedCatalogEntry;
import com.moviemux.rankingcontent.domain.port.AddExistingCatalogEntry;
import com.moviemux.rankingcontent.domain.usecase.impl.AddExistingCatalogEntryUseCaseImpl;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AddExistingCatalogEntryControllerTest {
  private final UUID rank=UUID.randomUUID(),user=UUID.randomUUID(),movie=UUID.randomUUID(),entry=UUID.randomUUID();
  private String path(){return "/rankings/"+rank+"/entries/catalog";}
  private String body(){return "{\"movie_id\":\""+movie+"\"}";}
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  private MockMvc mvc(AddExistingCatalogEntry entries) {
    authenticate(new Principal(user,true,true,true,true));
    var mapper=new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
    return MockMvcBuilders.standaloneSetup(new AddExistingCatalogEntryController(new AddExistingCatalogEntryUseCaseImpl(entries)))
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
  }
  private void authenticate(Principal principal){SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(principal,null,"ROLE_ADM"));}
  @Test void responds201ForCreatedAnd200ForIdempotentExistingWithAllowlistedBody() throws Exception {
    for(boolean created:new boolean[]{true,false}) {
      var mvc=mvc((r,a,m)->new AddedCatalogEntry(entry,created));
      mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).content(body()))
          .andExpect(status().is(created?201:200)).andExpect(header().string("Cache-Control","no-store"))
          .andExpect(content().json("{\"entry_id\":\""+entry+"\"}",true));
    }
  }
  @Test void forgedBodyRolesCreatorAndMetadataCannotReplacePrincipalOrCatalogIdentity() throws Exception {
    var writes=new AtomicInteger();
    var mvc=mvc((r,a,m)->{assertThat(r).isEqualTo(rank);assertThat(a).isEqualTo(user);assertThat(m).isEqualTo(movie);writes.incrementAndGet();return new AddedCatalogEntry(entry,true);});
    String forged=new ObjectMapper().writeValueAsString(Map.of("movie_id",movie,"user_id",UUID.randomUUID(),"actor_id",UUID.randomUUID(),
        "added_by_author_id",UUID.randomUUID(),"roles",List.of("ADM"),"capabilities",List.of("ADD_ENTRY"),"english_title","Forged","tmdb_id",999));
    mvc.perform(post(path()).header("X-User-Id",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(forged))
        .andExpect(status().isCreated()).andExpect(content().json("{\"entry_id\":\""+entry+"\"}",true));
    assertThat(writes.get()).isEqualTo(1);
    mvc=mvc((r,a,m)->{throw new RankingEntriesDeniedException();});
    mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).content(forged))
        .andExpect(status().isNotFound()).andExpect(content().json("{\"code\":\"ranking.not_found\"}",true));
  }
  @Test void allFourAccountFlagsAndMissingPrincipalFailBeforePersistence() throws Exception {
    var mvc=mvc((r,a,m)->{throw new AssertionError("Invalid principal must not write");});
    for(var principal:List.of(new Principal(user,false,true,true,true),new Principal(user,true,false,true,true),
        new Principal(user,true,true,false,true),new Principal(user,true,true,true,false),new Principal(null,true,true,true,true))) {
      authenticate(principal);
      mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).content(body()))
          .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control","no-store"))
          .andExpect(content().json("{\"code\":\"ranking.not_found\"}",true));
    }
    SecurityContextHolder.clearContext();
    mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isNotFound());
  }
  @Test void missingMalformedAndInvalidMovieIdsReturn400WithoutWriting() throws Exception {
    var mvc=mvc((r,a,m)->{throw new AssertionError("Invalid body must not write");});
    for(String invalid:List.of("", "null", "{}", "{", "{\"movie_id\":null}","{\"movie_id\":\"not-a-uuid\"}","{\"movie_id\":12}"))
      mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).content(invalid))
          .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"))
          .andExpect(content().json("{\"code\":\"ranking.invalid_catalog_entry\"}",true));
    mvc.perform(post("/rankings/invalid/entries/catalog").contentType(MediaType.APPLICATION_JSON).content(body()))
        .andExpect(status().isBadRequest()).andExpect(content().json("{\"code\":\"ranking.invalid_catalog_entry\"}",true));
  }
  @Test void archivePersistenceAndTransactionErrorsHaveOnlyStableCodesAndNoStore() throws Exception {
    var mvc=mvc((r,a,m)->{throw new ArchivedCatalogEntryException();});
    mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).content(body()))
        .andExpect(status().isConflict()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(content().json("{\"code\":\"ranking.archived_read_only\"}",true));
    for(RuntimeException failure:List.of(new org.springframework.dao.DataAccessResourceFailureException("private SQL"),
        new org.springframework.transaction.TransactionSystemException("private transaction"))) {
      mvc=mvc((r,a,m)->{throw failure;});
      mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).content(body()))
          .andExpect(status().isInternalServerError()).andExpect(header().string("Cache-Control","no-store"))
          .andExpect(content().json("{\"code\":\"ranking.operation_failed\"}",true));
    }
  }
  @Test void nullBodyInDirectAdapterCallIsInvalidRatherThanWriting() {
    var controller=new AddExistingCatalogEntryController((a,r,m)->{throw new AssertionError("Must not call use case");});
    assertThatThrownBy(()->controller.add(rank,null,user,new Principal(user,true,true,true,true))).isInstanceOf(InvalidCatalogEntryException.class);
    assertThatThrownBy(()->controller.add(rank,new AddExistingCatalogEntryController.CatalogRequest(movie),user,null)).isInstanceOf(RankingEntriesDeniedException.class);
  }
  public static final class Principal extends User {
    private final UUID userid;
    Principal(UUID id,boolean enabled,boolean notExpired,boolean credentialsNotExpired,boolean notLocked){super("synthetic","unused",enabled,notExpired,credentialsNotExpired,notLocked,List.of());userid=id;}
    public UUID getUserid(){return userid;}
  }
}
