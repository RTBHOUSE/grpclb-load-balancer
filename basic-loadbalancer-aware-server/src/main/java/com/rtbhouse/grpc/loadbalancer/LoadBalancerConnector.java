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
 * then.
 */
public class LoadBalancerConnector {
  public static final int WEIGHT_NOT_SET = -1;
  public static final String GRPCLB_DNS_PREFIX = "_grpclb._tcp.";
  private final List<SingleLoadBalancerConnector> lbConnectors;
  private final InetAddress serverAddress;
  private final int serverPort;
  private final int serverWeight;
  private final String[] services;
  private ConnectorStatus status;

  public LoadBalancerConnector(
      int serverPort,
      InetAddress serverAddress,
      String[] lbAddresses,
      String[] services,
      int serverWeight) {
    this.serverPort = serverPort;
    this.serverAddress = serverAddress;
    this.services = services;
    this.serverWeight = serverWeight;
    this.lbConnectors = getLbConnectorsList(lbAddresses);
    this.status = ConnectorStatus.STOPPED;
  }

  public LoadBalancerConnector(
      int serverPort, InetAddress serverAddress, String[] lbAddresses, String[] services) {
    this.serverPort = serverPort;
    this.serverAddress = serverAddress;
    this.services = services;
    this.serverWeight = WEIGHT_NOT_SET;
    this.lbConnectors = getLbConnectorsList(lbAddresses);
    this.status = ConnectorStatus.STOPPED;
  }

  public LoadBalancerConnector(
      int serverPort,
      InetAddress serverAddress,
      String serviceDnsName,
      String[] services,
      int serverWeight) {
    this.serverPort = serverPort;
    this.serverAddress = serverAddress;
    this.services = services;
    this.serverWeight = serverWeight;
    this.lbConnectors = getLbConnectorsList(getResolvedLbAdresses(serviceDnsName));
    this.status = ConnectorStatus.STOPPED;
  }

  public LoadBalancerConnector(
      int serverPort, InetAddress serverAddress, String serviceDnsName, String[] services) {
    this.serverPort = serverPort;
    this.serverAddress = serverAddress;
    this.services = services;
    this.serverWeight = WEIGHT_NOT_SET;
    this.lbConnectors = getLbConnectorsList(getResolvedLbAdresses(serviceDnsName));
    this.status = ConnectorStatus.STOPPED;
  }

  private List<SingleLoadBalancerConnector> getLbConnectorsList(String[] lbAddresses) {
    List<Pair<String, Integer>> list = new ArrayList<>();
    for (String address : lbAddresses) {
      String[] parsed = address.split(":");
      if (parsed.length != 2)
        throw new IllegalArgumentException(
            "Load balancer address should be in host:serverPort format.");
      list.add(new Pair<>(parsed[0], Integer.parseInt(parsed[1])));
    }
    return getLbConnectorsList(list);
  }

  private List<SingleLoadBalancerConnector> getLbConnectorsList(
      List<Pair<String, Integer>> lbAdresses) {
    if (lbAdresses.isEmpty()) {
      throw new IllegalStateException("Load balancers address list cannot be empty!");
    }
    return lbAdresses
        .stream()
        .map(
            address ->
                new SingleLoadBalancerConnector(
                    serverPort,
                    serverAddress,
                    address.getFirst(),
                    address.getSecond(),
                    services,
                    serverWeight))
        .collect(Collectors.toList());
  }

  private List<Pair<String, Integer>> getResolvedLbAdresses(String dnsName) {
    DnsSrvResolver resolver =
        DnsSrvResolvers.newBuilder()
            .cachingLookups(true)
            .retainingDataOnFailures(true)
            .dnsLookupTimeoutMillis(5000)
            .build();

    return resolver
        .resolve(GRPCLB_DNS_PREFIX + dnsName)
        .stream()
        .map(r -> new Pair<>(r.host(), r.port()))
        .collect(Collectors.toList());
  }

  public void start() {
    if (status != ConnectorStatus.STOPPED) {
      throw new IllegalStateException();
    }
    for (SingleLoadBalancerConnector lbConnector : lbConnectors) {
      lbConnector.start();
    }
    status = ConnectorStatus.WORKING;
  }

  public void stop() {
    if (status == ConnectorStatus.STOPPED) {
      throw new IllegalStateException();
    }
    for (SingleLoadBalancerConnector lbConnector : lbConnectors) {
      lbConnector.stop();
    }
    status = ConnectorStatus.STOPPED;
  }

  public void resume() {
    if (status != ConnectorStatus.PAUSED) {
      throw new IllegalStateException();
    }
    for (SingleLoadBalancerConnector lbConnector : lbConnectors) {
      lbConnector.resume();
    }
    status = ConnectorStatus.WORKING;
  }

  public void pause() {
    if (status != ConnectorStatus.WORKING) {
      throw new IllegalStateException();
    }
    for (SingleLoadBalancerConnector lbConnector : lbConnectors) {
      lbConnector.pause();
    }
    status = ConnectorStatus.PAUSED;
  }

  private enum ConnectorStatus {
    STOPPED,
    WORKING,
    PAUSED;
  }
}
