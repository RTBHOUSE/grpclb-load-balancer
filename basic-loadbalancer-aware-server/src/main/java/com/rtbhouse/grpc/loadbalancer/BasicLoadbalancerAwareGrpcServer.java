package com.rtbhouse.grpc.loadbalancer;

import static com.rtbhouse.grpc.loadbalancer.Helper.getServerAddress;

import io.grpc.BindableService;
import io.grpc.health.v1.HealthGrpc;
import java.io.IOException;
import java.net.InetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BasicLoadbalancerAwareGrpcServer extends HealthCheckCapableGrpcServerBase {
  private static final int WEIGHT_NOT_DEFINED = -1;
  private static final Logger logger =
      LoggerFactory.getLogger(BasicLoadbalancerAwareGrpcServer.class);

  private final InetAddress serverAddress;
  private final boolean natIsUsed;
  private final LoadBalancerConnector lbConnector;

  private volatile boolean healthy = true;

  public BasicLoadbalancerAwareGrpcServer(
      boolean natIsUsed,
      int port,
      String[] lbAddresses,
      String[] namesOfServices,
      BindableService... services) {

    this(natIsUsed, port, lbAddresses, namesOfServices, WEIGHT_NOT_DEFINED, services);
  }

  public BasicLoadbalancerAwareGrpcServer(
      boolean natIsUsed,
      int port,
      String[] lbAddresses,
      HealthGrpc.HealthImplBase healthService,
      String[] namesOfServices,
      BindableService... services)
      throws IOException {

    this(
        natIsUsed, port, lbAddresses, healthService, namesOfServices, WEIGHT_NOT_DEFINED, services);
  }

  public BasicLoadbalancerAwareGrpcServer(
      boolean natIsUsed,
      int port,
      String[] lbAddresses,
      String[] namesOfServices,
      int serverWeight,
      BindableService... services) {

    super(port, services);

    this.natIsUsed = natIsUsed;
    this.serverAddress = getServerAddress(natIsUsed);
    this.lbConnector =
        new LoadBalancerConnector(port, serverAddress, lbAddresses, namesOfServices, serverWeight);
  }

  public BasicLoadbalancerAwareGrpcServer(
      boolean natIsUsed,
      int port,
      String[] lbAddresses,
      HealthGrpc.HealthImplBase healthService,
      String[] namesOfServices,
      int serverWeight,
      BindableService... services)
      throws IOException {

    super(port, healthService, services);

    this.natIsUsed = natIsUsed;
    this.serverAddress = getServerAddress(natIsUsed);
    this.lbConnector =
        new LoadBalancerConnector(port, serverAddress, lbAddresses, namesOfServices, serverWeight);
  }

  protected void start() throws IOException {
    super.start();
    lbConnector.start();
  }

  protected void stop() {
    lbConnector.stop();
    super.stop();
  }

  @Override
  protected void healthInspectorTask() {
    if (isHealthy() != healthy) {
      healthy = !healthy;
      if (healthy) {
        lbConnector.resume();
      } else {
        lbConnector.pause();
      }
    }
  }
}
