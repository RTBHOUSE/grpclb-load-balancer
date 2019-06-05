package com.rtbhouse.grpc.loadbalancer;

import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.health.v1.HealthGrpc.HealthBlockingStub;
import io.grpc.health.v1.HealthGrpc.HealthImplBase;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class HealthCheckCapableGrpcServerBase extends GrpcServerBase {
  public static final long DEFAULT_HEALTH_INSPECTOR_DELAY = 5000;
  protected final boolean checkingHealth;
  protected final HealthBlockingStub healthBlockingStub;
  private static final Logger logger =
      LoggerFactory.getLogger(HealthCheckCapableGrpcServerBase.class);
  private final Server inProcessServer;
  private final ManagedChannel inProcessChannel;

  protected long healthInspectorDelay = DEFAULT_HEALTH_INSPECTOR_DELAY;
  private ScheduledFuture<?> healthInspectorHandle;

  public HealthCheckCapableGrpcServerBase(int port, BindableService... services) {
    super(port, services);
    inProcessServer = null;
    inProcessChannel = null;
    healthBlockingStub = null;
    checkingHealth = false;
  }

  public HealthCheckCapableGrpcServerBase(
      int port, HealthImplBase healthService, BindableService... services) throws IOException {
    super(port, services);

    if (healthService == null) {
      inProcessServer = null;
      inProcessChannel = null;
      healthBlockingStub = null;
      checkingHealth = false;
    } else {
      String uniqueName = InProcessServerBuilder.generateName();
      inProcessServer =
          InProcessServerBuilder.forName(uniqueName).addService(healthService).build().start();
      inProcessChannel = InProcessChannelBuilder.forName(uniqueName).build();
      healthBlockingStub = HealthGrpc.newBlockingStub(inProcessChannel);
      checkingHealth = true;
    }
  }

  @Override
  protected void start() throws IOException {
    super.start();

    if (checkingHealth) {
      registerHealthInspector(healthInspectorDelay);
    }
  }

  @Override
  protected void stop() {
    if (checkingHealth) {
      deregisterHealthInspector();
      inProcessChannel.shutdown();
      inProcessServer.shutdown();
    }

    super.stop();
  }

  private void registerHealthInspector(long delay) {
    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    this.healthInspectorHandle =
        scheduler.scheduleWithFixedDelay(
            this::healthInspectorTask,
            0, // inital delay
            delay, // further delays
            TimeUnit.MILLISECONDS);

    logger.debug("Health inspector registered");
  }

  /** Task executed by health inspector periodically. */
  protected abstract void healthInspectorTask();

  private void deregisterHealthInspector() {
    healthInspectorHandle.cancel(true); // can interrupt health check in progress
    logger.debug("Health inspector deregistered");
  }

  protected boolean isHealthy() {
    HealthCheckResponse response =
        healthBlockingStub.check(HealthCheckRequest.getDefaultInstance());
    ServingStatus status = response.getStatus();
    return status.equals(ServingStatus.SERVING);
  }
}
