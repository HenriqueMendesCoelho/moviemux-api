package com.moviemux.rankingcontent.application.spring;

import com.moviemux.rankingcontent.adapter.repository.postgres.RatingCopyReceiptSweeper;
import com.moviemux.rankingcontent.domain.port.CopyApplyAvailability;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
public class RatingCopyApplyConfiguration {
  @Bean CopyAvailabilityLifecycle copyApplyAvailability(DataSource source,ObjectProvider<MeterRegistry> meters,
      @Value("${ranking.rating-copy.apply-enabled:false}") String enabled,
      @Value("${ranking.rating-copy.cleanup-batch-size:}") String batch,
      @Value("${ranking.rating-copy.cleanup-interval-seconds:}") String interval) {
    return new CopyAvailabilityLifecycle(new RatingCopyReceiptSweeper(source),meters.getIfAvailable(),
        "true".equalsIgnoreCase(enabled),positive(batch,100),positive(interval,3600));
  }

  private static int positive(String raw,int ceiling) {
    if(raw==null || !raw.matches("[0-9]{1,4}")) return 0;
    int value=Integer.parseInt(raw);
    return value>=1 && value<=ceiling ? value : 0;
  }

  static final class CopyAvailabilityLifecycle implements CopyApplyAvailability, SmartLifecycle {
    private final RatingCopyReceiptSweeper sweeper;
    private final boolean configured;
    private final int batch;
    private final int interval;
    private volatile boolean running;
    private volatile boolean healthy;
    private volatile long boundedRemaining;
    private volatile double oldestLag;
    private ScheduledExecutorService executor;

    CopyAvailabilityLifecycle(RatingCopyReceiptSweeper sweeper,MeterRegistry meters,boolean enable,int batch,int interval) {
      this.sweeper=sweeper;
      this.batch=batch;
      this.interval=interval;
      configured=enable && batch>0 && interval>0;
      if(meters!=null && configured) {
        meters.gauge("moviemux.rating.copy.receipts.expired.bounded",this,worker->worker.boundedRemaining);
        meters.gauge("moviemux.rating.copy.receipts.oldest.lag.seconds",this,worker->worker.oldestLag);
        meters.gauge("moviemux.rating.copy.receipts.cleanup.healthy",this,worker->worker.healthy?1:0);
      }
    }

    @Override public boolean available(){return configured && running && healthy;}
    @Override public boolean isRunning(){return running;}

    @Override public synchronized void start() {
      if(!configured || running) return;
      executor=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon()
          .name("rating-copy-receipt-sweep").factory());
      running=true;
      sweep();
      executor.scheduleWithFixedDelay(this::sweep,interval,interval,TimeUnit.SECONDS);
    }

    @Override public synchronized void stop() {
      running=false;
      healthy=false;
      if(executor!=null) executor.shutdownNow();
      executor=null;
    }

    private void sweep() {
      try {
        var result=sweeper.sweep(batch);
        boundedRemaining=result.boundedRemaining();
        oldestLag=result.oldestLagSeconds();
        healthy=true;
      }catch(RuntimeException unavailable){
        healthy=false;
        // Never attach SQL, actor/round identifiers, ciphertext or request data.
        LoggerFactory.getLogger(CopyAvailabilityLifecycle.class).warn("Rating copy receipt cleanup delayed");
      }
    }
  }
}
