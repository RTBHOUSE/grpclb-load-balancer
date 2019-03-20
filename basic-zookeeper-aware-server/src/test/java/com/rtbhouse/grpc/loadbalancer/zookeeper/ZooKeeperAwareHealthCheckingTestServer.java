package com.rtbhouse.grpc.loadbalancer.zookeeper;

import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.curator.framework.CuratorFramework;

public class ZooKeeperAwareHealthCheckingTestServer extends ZooKeeperAwareBasicGrpcServer {
  public ZooKeeperAwareHealthCheckingTestServer(
      int port,
      String zooKeeperServerNodePath,
      CuratorFramework curatorFramework,
      ToggleableHealthService healthService)
      throws IOException {
    super(port, zooKeeperServerNodePath, curatorFramework, false, healthService);
  }

  public static class ToggleableHealthService extends HealthGrpc.HealthImplBase {
    private AtomicBoolean shouldFail = new AtomicBoolean(false);

    public void setHealthy() {
      shouldFail.set(false);
    }

    public void setUnhealthy() {
      shouldFail.set(true);
    }

    @Override
    public void check(
        HealthCheckRequest request, StreamObserver<HealthCheckResponse> responseObserver) {
      if (!shouldFail.get()) {
        responseObserver.onNext(
            HealthCheckResponse.newBuilder()
                .setStatus(HealthCheckResponse.ServingStatus.SERVING)
                .build());
      } else {
        responseObserver.onNext(
            HealthCheckResponse.newBuilder()
                .setStatus(HealthCheckResponse.ServingStatus.NOT_SERVING)
                .build());
      }
      responseObserver.onCompleted();
    }
  }
}
