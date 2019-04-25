package com.rtbhouse.grpc.lbexamples;

import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExampleHealthService extends HealthGrpc.HealthImplBase {
  private static final Logger logger = LoggerFactory.getLogger(ExampleHealthService.class);
  private int checksCount = 0;
  private int servingPeriodSize;
  private int notServingPeriodSize;

  ExampleHealthService(int servingPeriodSize, int notServingPeriodSize) {
    super();
    assert (servingPeriodSize >= 0 && notServingPeriodSize >= 0);
    assert (servingPeriodSize != 0 || notServingPeriodSize != 0);

    this.servingPeriodSize = servingPeriodSize;
    this.notServingPeriodSize = notServingPeriodSize;
  }

  /**
   * First responds HEALTHY to servingPeriodSize requests. Then responds NOT_HEALTHY to
   * notServingPeriodSize requests. And so on.
   */
  @Override
  public synchronized void check(
      HealthCheckRequest request, StreamObserver<HealthCheckResponse> responseObserver) {

    checksCount = (checksCount + 1) % (servingPeriodSize + notServingPeriodSize);

    if (checksCount < servingPeriodSize) {
      logger.info("State HEALTHY");
      responseObserver.onNext(
          HealthCheckResponse.newBuilder()
              .setStatus(HealthCheckResponse.ServingStatus.SERVING)
              .build());
    } else {
      logger.info("State NOT_HEALTHY");
      responseObserver.onNext(
          HealthCheckResponse.newBuilder()
              .setStatus(HealthCheckResponse.ServingStatus.NOT_SERVING)
              .build());
    }

    responseObserver.onCompleted();
  }

  public boolean isRespondingServing() {
    return checksCount < servingPeriodSize;
  }
}
