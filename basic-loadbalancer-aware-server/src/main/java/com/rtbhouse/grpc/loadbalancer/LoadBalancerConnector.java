package com.rtbhouse.grpc.loadbalancer;

import com.spotify.dns.DnsSrvResolver;
import com.spotify.dns.DnsSrvResolvers;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles backend server communication with load balancer (as defined in signup.proto). You should
 * provide server's address, port and domain addresses (host:port) of services that server serves.
 * These names have to be the same as those used by clients when creating gRPC channels, e.g.
 * through ManagedChannelBuilder.forAddress(host, port). You should also provide a list of
 * loadbalancers' addresses or a domain address to resolve them automatically, by checking SRV
 * records for the domain, just as clients do.
 *
 * <p>There is also serverWeight, which is optional. You may use it to modify the percentage of
 * requests that server receives. Default value is 100, and it means that server should receive c.a.
 * 1/N of all requests, where N is the total number of backend servers. Generally, load balancer
 * sums all servers' weights, and every server gets proportional (server_weight/sum) number of
 * requests.
 *
 * <p>You can pause/resume, or stop/start the connector multiple times. Notice, that using
 * pause()/resume() is less expensive than stop()/start(), because gRPC channel is not being closed
 * then. When there is no state change needed (e.g. when you call resume() on a working connector),
 * pause() and resume() won't be synchronizing. Start()/stop() are always synchronizing.
 */
public class LoadBalancerConnector {
  public static final int WEIGHT_NOT_SET = -1;
  public static final String GRPCLB_DNS_PREFIX = "_grpclb._tcp.";
  private final List<SingleLoadBalancerConnector> lbConnectors;
  private volatile ConnectorStatus status;

  private LoadBalancerConnector(
      int serverPort,
      InetAddress serverAddress,
      String[] services,
      List<Address> lbAddresses,
      int serverWeight) {
    this.status = ConnectorStatus.STOPPED;

    this.lbConnectors =
        lbAddresses
            .stream()
            .map(
                address ->
                    new SingleLoadBalancerConnector(
                        serverPort,
                        serverAddress,
                        address.hostName,
                        address.port,
                        services,
                        serverWeight))
            .collect(Collectors.toList());

    if (lbConnectors.isEmpty()) {
      throw new IllegalStateException("Load balancers address list cannot be empty!");
    }
  }

  public LoadBalancerConnector(
      int serverPort,
      InetAddress serverAddress,
      String[] lbAddresses,
      String[] services,
      int serverWeight) {
    this(serverPort, serverAddress, services, getLbConnectorsList(lbAddresses), serverWeight);
  }

  public LoadBalancerConnector(
      int serverPort, InetAddress serverAddress, String[] lbAddresses, String[] services) {
    this(serverPort, serverAddress, lbAddresses, services, WEIGHT_NOT_SET);
  }

  public LoadBalancerConnector(
      int serverPort,
      InetAddress serverAddress,
      String serviceDnsName,
      String[] services,
      int serverWeight) {
    this(serverPort, serverAddress, services, getResolvedLbAdresses(serviceDnsName), serverWeight);
  }

  public LoadBalancerConnector(
      int serverPort, InetAddress serverAddress, String serviceDnsName, String[] services) {
    this(serverPort, serverAddress, serviceDnsName, services, WEIGHT_NOT_SET);
  }

  private static List<Address> getLbConnectorsList(String[] lbAddresses) {
    List<Address> list = new ArrayList<>();
    for (String hostPortString : lbAddresses) {
      list.add(new Address(hostPortString));
    }
    return list;
  }

  private static List<Address> getResolvedLbAdresses(String dnsName) {
    DnsSrvResolver resolver = DnsSrvResolvers.newBuilder().dnsLookupTimeoutMillis(10000).build();

    return resolver
        .resolve(GRPCLB_DNS_PREFIX + dnsName)
        .stream()
        .map(r -> new Address(r.host(), r.port()))
        .collect(Collectors.toList());
  }

  public synchronized void start() {
    if (status != ConnectorStatus.STOPPED) {
      throw new IllegalStateException("Only stopped connector can be started.");
    }
    for (SingleLoadBalancerConnector lbConnector : lbConnectors) {
      lbConnector.start();
    }
    status = ConnectorStatus.WORKING;
  }

  public synchronized void stop() {
    if (status == ConnectorStatus.STOPPED) {
      return;
    }
    for (SingleLoadBalancerConnector lbConnector : lbConnectors) {
      lbConnector.stop();
    }
    status = ConnectorStatus.STOPPED;
  }

  public void resume() {
    if (status != ConnectorStatus.WORKING) {
      doResume();
    }
  }

  private synchronized void doResume() {
    if (status == ConnectorStatus.STOPPED) {
      throw new IllegalStateException(
          "Stopped connector can't be resumed, you should call start() instead.");
    } else if (status == ConnectorStatus.PAUSED) {
      for (SingleLoadBalancerConnector lbConnector : lbConnectors) {
        lbConnector.resume();
      }
      status = ConnectorStatus.WORKING;
    }
  }

  public void pause() {
    if (status != ConnectorStatus.PAUSED) {
      doPause();
    }
  }

  private synchronized void doPause() {
    if (status == ConnectorStatus.STOPPED) {
      throw new IllegalStateException("Stopped connector can't be paused.");
    } else if (status == ConnectorStatus.WORKING) {
      for (SingleLoadBalancerConnector lbConnector : lbConnectors) {
        lbConnector.pause();
      }
      status = ConnectorStatus.PAUSED;
    }
  }

  private enum ConnectorStatus {
    STOPPED,
    WORKING,
    PAUSED;
  }

  private static class Address {
    private String hostName;
    private Integer port;

    private Address(String hostPortString) {
      String[] parsed = hostPortString.split(":");
      if (parsed.length != 2)
        throw new IllegalArgumentException(
            "Load balancer address should be in host:serverPort format.");
      hostName = parsed[0];
      port = Integer.parseInt(parsed[1]);
    }

    private Address(String hostName, Integer port) {
      this.hostName = hostName;
      this.port = port;
    }
  }
}
