package com.moviemux.userboundary;

import com.moviemux.mail.domain.exception.MailDeliveryFailure;
import com.moviemux.mail.domain.repository.EmailGateway;
import com.moviemux.user.adapter.repository.postgres.RegistrationPostgresStore;
import com.moviemux.user.application.spring.RegistrationWaitlistService;
import com.moviemux.user.domain.registration.RegistrationWaitlist.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RegistrationWaitlistServiceTest {
  @Mock RegistrationPostgresStore store;
  @Mock PlatformTransactionManager manager;
  @Mock TransactionStatus transaction;
  @Mock EmailGateway gateway;
  final UUID actor=UUID.randomUUID(),id=UUID.randomUUID(),attempt=UUID.randomUUID();
  final String email="fixture@example.test",code="KB-123456789",template="fixture_template",url="https://fixture.example.test/register";

  RegistrationWaitlistService service(String template,String url,boolean enabled) {
    when(manager.getTransaction(any())).thenReturn(transaction);
    return new RegistrationWaitlistService(store,manager,gateway,template,url,enabled);
  }

  @Test void acceptedGatewayResponseIsNotAClaimOfDeliveryAndReplayAvoidsProvider() {
    var service=service(template,url,true);
    var claim=new RegistrationPostgresStore.Claim(id,email,code,attempt,Status.SENDING,false);
    when(store.claim(actor,id,true)).thenReturn(claim,
        new RegistrationPostgresStore.Claim(id,email,code,null,Status.GATEWAY_ACCEPTED,false));
    when(gateway.sendEmail(any())).thenReturn(EmailGateway.Delivery.SENT);
    var result=new Invitation(id,Status.GATEWAY_ACCEPTED,code);
    when(store.finish(claim,Status.GATEWAY_ACCEPTED)).thenReturn(result);
    assertThat(service.invite(actor,id)).isEqualTo(result);
    assertThat(service.invite(actor,id)).isEqualTo(result);
    verify(gateway).sendEmail(argThat(mail->mail.to().equals(email)&&mail.templateId().equals(template)
        &&mail.metadata().get("invite_code").equals(code)&&mail.metadata().get("registration_link").equals(url)));
    InOrder order=inOrder(store,manager,gateway);
    order.verify(store).claim(actor,id,true);
    order.verify(manager).commit(transaction);
    order.verify(gateway).sendEmail(any());
    order.verify(store).finish(claim,Status.GATEWAY_ACCEPTED);
    order.verify(manager).commit(transaction);
  }

  @Test void absentOrInvalidConfigurationRetainsManualCodeAndNeverCallsProvider(){
    var service=service("","http://example.test/register",true);
    when(store.claim(actor,id,false)).thenReturn(new RegistrationPostgresStore.Claim(id,email,code,null,Status.DELIVERY_UNAVAILABLE,false));
    assertThat(service.invite(actor,id)).isEqualTo(new Invitation(id,Status.DELIVERY_UNAVAILABLE,code));
    verifyNoInteractions(gateway);
  }

  @Test void ambiguousDeliveryIsRecordedAndExplicitRetryReusesSameCode(){
    var service=service(template,url,true);
    var first=new RegistrationPostgresStore.Claim(id,email,code,attempt,Status.SENDING,false);
    var second=new RegistrationPostgresStore.Claim(id,email,code,UUID.randomUUID(),Status.SENDING,false);
    when(store.claim(actor,id,true)).thenReturn(first,second);
    when(gateway.sendEmail(any())).thenThrow(new MailDeliveryFailure(MailDeliveryFailure.Reason.HTTP_ERROR,503))
        .thenReturn(EmailGateway.Delivery.SENT);
    when(store.finish(first,Status.DELIVERY_UNKNOWN)).thenReturn(new Invitation(id,Status.DELIVERY_UNKNOWN,code));
    when(store.finish(second,Status.GATEWAY_ACCEPTED)).thenReturn(new Invitation(id,Status.GATEWAY_ACCEPTED,code));
    assertThat(service.invite(actor,id).status()).isEqualTo(Status.DELIVERY_UNKNOWN);
    assertThat(service.invite(actor,id).status()).isEqualTo(Status.GATEWAY_ACCEPTED);
    verify(gateway,times(2)).sendEmail(argThat(mail->mail.metadata().get("invite_code").equals(code)));
  }

  @Test void priorUnknownSurvivesDefiniteFailureOnExplicitRetry(){
    var service=service(template,url,true);
    var retry=new RegistrationPostgresStore.Claim(id,email,code,attempt,Status.SENDING,true);
    when(store.claim(actor,id,true)).thenReturn(retry);
    when(gateway.sendEmail(any())).thenThrow(new MailDeliveryFailure(MailDeliveryFailure.Reason.HTTP_ERROR,422));
    when(store.finish(retry,Status.DELIVERY_UNKNOWN)).thenReturn(new Invitation(id,Status.DELIVERY_UNKNOWN,code));
    assertThat(service.invite(actor,id).status()).isEqualTo(Status.DELIVERY_UNKNOWN);
  }

  @Test void explicitGatewayFailureAndDisabledOutcomesRemainDistinct(){
    var service=service(template,url,true);
    var first=new RegistrationPostgresStore.Claim(id,email,code,attempt,Status.SENDING,false);
    var second=new RegistrationPostgresStore.Claim(id,email,code,UUID.randomUUID(),Status.SENDING,false);
    when(store.claim(actor,id,true)).thenReturn(first,second);
    when(gateway.sendEmail(any())).thenThrow(new MailDeliveryFailure(MailDeliveryFailure.Reason.HTTP_ERROR,422))
        .thenReturn(EmailGateway.Delivery.DISABLED);
    when(store.finish(first,Status.DELIVERY_FAILED)).thenReturn(new Invitation(id,Status.DELIVERY_FAILED,code));
    when(store.finish(second,Status.DELIVERY_UNAVAILABLE)).thenReturn(new Invitation(id,Status.DELIVERY_UNAVAILABLE,code));
    assertThat(service.invite(actor,id).status()).isEqualTo(Status.DELIVERY_FAILED);
    assertThat(service.invite(actor,id).status()).isEqualTo(Status.DELIVERY_UNAVAILABLE);
  }
}
