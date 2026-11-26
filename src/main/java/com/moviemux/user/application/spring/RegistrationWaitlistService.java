package com.moviemux.user.application.spring;

import com.moviemux.mail.domain.exception.MailDeliveryFailure;
import com.moviemux.mail.domain.model.SendMailTemplate;
import com.moviemux.mail.domain.repository.EmailGateway;
import com.moviemux.user.adapter.repository.postgres.RegistrationPostgresStore;
import com.moviemux.user.domain.registration.RegistrationMode;
import com.moviemux.user.domain.registration.RegistrationWaitlist;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RegistrationWaitlistService implements RegistrationWaitlist {
  private final RegistrationPostgresStore store;
  private final TransactionTemplate transactions;
  private final EmailGateway gateway;
  private final String template;
  private final String registrationUrl;
  private final boolean configured;

  public RegistrationWaitlistService(RegistrationPostgresStore store, PlatformTransactionManager manager,
      EmailGateway gateway,@Value("${registration.waitlist.invite-template:}") String template,
      @Value("${registration.waitlist.registration-url:}") String url,
      @Value("${send.mail:false}") boolean mailEnabled) {
    this.store=store;this.transactions=new TransactionTemplate(manager);this.gateway=gateway;
    this.transactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.template=template;this.registrationUrl=url;
    this.configured=mailEnabled && !template.isBlank() && validUrl(url);
  }

  @Override public RegistrationMode mode(){return transactions.execute(s->store.mode());}
  @Override public RegistrationMode adminMode(UUID actor){return transactions.execute(s->store.adminMode(actor));}
  @Override public RegistrationMode changeMode(UUID actor,RegistrationMode mode){
    return transactions.execute(s->store.changeMode(actor,mode));
  }
  @Override public void request(String email){transactions.executeWithoutResult(s->store.request(email));}
  @Override public Page list(UUID actor,UUID after,int limit){return transactions.execute(s->store.list(actor,after,limit));}

  @Override public Invitation invite(UUID actor,UUID id){
    var claim=transactions.execute(s->store.claim(actor,id,configured));
    if(claim.attemptId()==null)return new Invitation(id,claim.status(),claim.code());

    Status status;
    try {
      var delivery=gateway.sendEmail(new SendMailTemplate(claim.email(),template,Map.of(
          "invite_code",claim.code(),"registration_link",registrationUrl)));
      status=delivery==EmailGateway.Delivery.SENT?Status.GATEWAY_ACCEPTED:Status.DELIVERY_UNAVAILABLE;
    } catch(MailDeliveryFailure failure) {
      status=failure.explicitRejection()?Status.DELIVERY_FAILED:Status.DELIVERY_UNKNOWN;
    } catch(RuntimeException failure) {status=Status.DELIVERY_UNKNOWN;}
    Status outcome=claim.priorUnknown() && status!=Status.GATEWAY_ACCEPTED
        ?Status.DELIVERY_UNKNOWN:status;
    var result=transactions.execute(s->store.finish(claim,outcome));
    // Outcome must survive a concurrent administrator revocation, but no code is returned
    // to an actor whose persisted authority disappeared during the external request.
    transactions.execute(s->store.adminMode(actor));
    return result;
  }

  private static boolean validUrl(String url){
    try{
      URI uri=URI.create(url);
      return "https".equals(uri.getScheme()) && uri.getHost()!=null && uri.getUserInfo()==null
          && uri.getFragment()==null && uri.getQuery()==null;
    }catch(IllegalArgumentException invalid){return false;}
  }
}
