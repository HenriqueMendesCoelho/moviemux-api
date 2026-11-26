package com.moviemux.userboundary;

import com.moviemux.user.adapter.controller.RegistrationController;
import com.moviemux.user.domain.registration.RegistrationFailure;
import com.moviemux.user.domain.registration.RegistrationMode;
import com.moviemux.user.domain.registration.RegistrationWaitlist;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RegistrationControllerTest {
  RegistrationWaitlist service;
  jakarta.validation.ValidatorFactory validation;
  RegistrationController controller;
  MockMvc mvc;

  @BeforeEach void setup(){
    service=mock(RegistrationWaitlist.class);
    validation=Validation.buildDefaultValidatorFactory();
    controller=new RegistrationController(service,validation.getValidator());
    mvc=MockMvcBuilders.standaloneSetup(controller).build();
  }
  @AfterEach void finish(){validation.close();}

  @Test void publicModeAndWaitlistHaveNoStoreAndExactWire() throws Exception {
    when(service.mode()).thenReturn(RegistrationMode.WAITLIST);
    mvc.perform(get("/public/registration-mode")).andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("WAITLIST")).andExpect(header().string("Cache-Control","no-store"));
    mvc.perform(post("/public/registration-waitlist").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"  FIXTURE@EXAMPLE.TEST  \"}"))
        .andExpect(status().isAccepted()).andExpect(content().string(""))
        .andExpect(header().string("Cache-Control","no-store"));
    verify(service).request("fixture@example.test");
  }
  @Test void malformedOrOverlongWaitlistBodyNeverReachesService() throws Exception {
    for(String body:new String[]{"","{}","{\"email\":null}","{\"email\":\"bad\"}",
        "{\"email\":\"x@example.test\",\"extra\":1}","{\"email\":\"a@example.test\",\"email\":\"b@example.test\"}",
        "{\"email\":\"a@example.test\"}{}", "{\"email\":\""+ "x".repeat(101)+"@example.test\"}"}){
      mvc.perform(post("/public/registration-waitlist").contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("registration.invalid_request"));
    }
    mvc.perform(post("/public/registration-waitlist").contentType(MediaType.APPLICATION_JSON)
        .content("x".repeat(4097))).andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test void explicitErrorsUseSanitizedStableCodesAndCurrentActorRequired(){
    for(var reason:RegistrationFailure.Reason.values()){
      var ex=new RegistrationFailure(reason);
      var response=controller.failure(ex);
      assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
      assertThat(response.getBody()).containsOnlyKeys("code");
      assertThat(response.getBody().get("code")).isEqualTo("registration."+reason.name().toLowerCase(java.util.Locale.ROOT));
    }
    var request=new MockHttpServletRequest();
    assertThatThrownBy(()->controller.adminMode(request,null,null)).isInstanceOf(RegistrationFailure.class);
    var blocked=User.withUsername("synthetic").password("unused").roles("ADM").accountLocked(true).build();
    assertThatThrownBy(()->controller.adminMode(request,UUID.randomUUID(),blocked)).isInstanceOf(RegistrationFailure.class);
    verifyNoInteractions(service);
  }
}
