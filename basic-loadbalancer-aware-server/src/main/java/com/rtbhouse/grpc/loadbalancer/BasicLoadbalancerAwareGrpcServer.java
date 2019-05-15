package com.rtbhouse.grpc.loadbalancer;

import static com.rtbhouse.grpc.loadbalancer.Addresses.getServerAddress;

import io.grpc.BindableService;
import io.grpc.health.v1.HealthGrpc;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wrapper for io.grpc.Server; adds communication with loadbalancer through LoadBalancerConnector.
 * You can also provide your custom healthcheck service (io.grpc.health.v1.HealthProto
 * implementation), and it will be probed every 5s (can be configured). If your server's health
 * status change, pause()/resume() on the LoadBalancerConnector will be called. Check
 * BasicLoadbalancerAwareGrpcServer.Builder documentation for more details.
 */
public class BasicLoadbalancerAwareGrpcServer extends HealthCheckCapableGrpcServerBase {
  private static final Logger logger =
      LoggerFactory.getLogger(BasicLoadbalancerAwareGrpcServer.class);

  private final LoadBalancerConnector lbConnector;

  private boolean healthy = true;

  private BasicLoadbalancerAwareGrpcServer(Builder builder) throws IOException {
    super(builder.port, builder.healthService, builder.services);
    healthInspectorDelay = builder.healthInspectorDelay;

    if (builder.lbAddresses != null) {
      lbConnector =
          new LoadBalancerConnector(
              port,
              getServerAddress(builder.natIsUsed),
              builder.lbAddresses,
              builder.namesOfServices,
              builder.serverWeight);
    } else {
      lbConnector =
          new LoadBalancerConnector(
              port,
              getServerAddress(builder.natIsUsed),
              builder.dnsServiceName,
              builder.namesOfServices,
              builder.serverWeight);
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public void start() throws IOException {
    super.start();
    lbConnector.start();
  }

  public void stop() {
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

  /**
   * Builder class for BasicLoadbalancerAwareGrpcServer. Following fields are required: - port -
   * namesOfServices - bindableServices and exactly one option from: dnsServiceName, lbAdresses.
   *
   * <p>For detailed information check builder's methods description.
   */
  public static final class Builder {
    private int port;
    private String[] lbAddresses;
    private String dnsServiceName;
    private String[] namesOfServices;
    private int serverWeight = LoadBalancerConnector.WEIGHT_NOT_SET;
    private BindableService[] services;
    private boolean natIsUsed = false;
    private HealthGrpc.HealthImplBase healthService;
    private long healthInspectorDelay =
        HealthCheckCapableGrpcServerBase.DEFAULT_HEALTH_INSPECTOR_DELAY;

    /**
     * By default, server discovers its adress by InetAddress.getLocalHost().getHostAddress() call.
     * But, if you use NAT and don't have public IP address, it won't work. When this option is
     * enabled, server will ask http://checkip.amazonaws.com about its IP address.
     */
    public Builder natIsUsed() {
      natIsUsed = true;
      return this;
    }

    /**
     * Provide list of load balancers' addresses in host:port format directly. Alternative:
     * useDnsServiceName()
     */
    public Builder useLbAddresses(String... val) {
      lbAddresses = val;
      return this;
    }

    /**
     * Resolve load balancers' addresses automatically, by checking SRV records for given domain,
     * just as clients do. Alternative: useLbAddresses
     */
    public Builder useDnsServiceName(String val) {
      dnsServiceName = val;
      return this;
    }

    /** Your server's port. */
    public Builder setPort(int val) {
      port = val;
      return this;
    }

    /**
     * Names of services (corresponding to provided bindable services) that this server serves, in
     * host, or host:port format. Those have to be the same names, that clients use when creating
     * gRPC channels, e.g. through ManagedChannelBuilder.fotTarget(host), or
     * ManagedChannelBuilder.forAddress(host, port)
     */
    public Builder setNamesOfServices(String... val) {
      namesOfServices = val;
      return this;
    }

    /**
     * Optionally: you may set custom weight, to modify the percentage of requests that this server
     * receives. Default value is 100, and it means that server should receive c.a. 1/N of all
     * requests, where N is the total number of backend servers.
     */
    public Builder setServerWeight(int val) {
      serverWeight = val;
      return this;
    }

    /** As for standard gRPC server. */
    public Builder setBindableServices(BindableService... val) {
      services = val;
      return this;
    }

    /**
     * You can optionally provide a io.grpc.health.v1.HealthProto implementation, to periodically
     * check healthcheck state, and unregister the server when it's not healthy.
     *
     * <p>To do this, there is an InProcessServer being run with this service in the background.
     */
    public Builder useHealthcheckService(HealthGrpc.HealthImplBase val) {
      healthService = val;
      return this;
    }

    /**
     * Valid only when useHealthcheckService() is used. By default, healthcheck is probed every 5000
     * milliseconds, and you can modify this here.
     */
    public Builder setHealthCheckingFrequency(long val) {
      healthInspectorDelay = val;
      return this;
    }

    /** Build the server. */
    public BasicLoadbalancerAwareGrpcServer build() throws IOException {
      if (port == 0) throw new IllegalStateException("Port has to be set.");

      if ((lbAddresses != null && dnsServiceName != null)
          || (lbAddresses == null && dnsServiceName == null))
        throw new IllegalStateException(
            "Provide exactly one of the following arguments: lbAdresses, dnsServiceName");

      if (services == null)
        throw new IllegalStateException("At least one bindable service has to be provided.");

      if (namesOfServices == null)
        throw new IllegalStateException("Names of services have to be provided.");

      return new BasicLoadbalancerAwareGrpcServer(this);
    }
  }
}
