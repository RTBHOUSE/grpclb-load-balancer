package com.rtbhouse.grpc.loadbalancer.standalone;

import static java.lang.Math.ceil;

import com.google.protobuf.ByteString;
import io.grpc.lb.v1.Server;
import io.grpc.lb.v1.ServerList;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class AvailableServerList {
  private static final int SERVER_LIST_LENGTH_RATIO = 3;
  private static final long STARTING_STATE = 0L;

  private final LoadBalanceService lbService;
  /**
   * We aggregate the servers timestamps per service. key: name of service value: list of servers
   * providing such service
   */
  private final Map<String, Set<BackendServer>> serviceToServers = new ConcurrentHashMap<>();

  /** Indicates state of serviceToServers. Every add/remove operation should increase state. */
  private final AtomicLong state = new AtomicLong(STARTING_STATE);

  public AvailableServerList(LoadBalanceService lbService) {
    this(lbService, 1000, 1200);
  }

  public AvailableServerList(
      LoadBalanceService lbService, int timeBetweenUpToDateChecks, int timeToEvict) {
    this.lbService = lbService;
    runServersEvictor(timeBetweenUpToDateChecks, timeToEvict);
  }

  /**
   * Runs an executor that periodically evicts outdated servers .
   *
   * @param frequency delay between consecutive executions of the evicting function (in
   *     milliseconds)
   * @param timeToEvict time after a server becomes outdated (in milliseconds)
   */
  private void runServersEvictor(int frequency, int timeToEvict) {
    Executors.newScheduledThreadPool(1)
        .scheduleAtFixedRate(
            () -> evictOutdatedServers(timeToEvict), 0, frequency, TimeUnit.MILLISECONDS);
  }

  private void evictOutdatedServers(long timeToEvict) {
    for (Map.Entry<String, Set<BackendServer>> serviceToServersKV : serviceToServers.entrySet()) {
      boolean outdated = false;
      long currentTime = System.currentTimeMillis();

      String service = serviceToServersKV.getKey();
      Set<BackendServer> servers = serviceToServersKV.getValue();

      for (BackendServer server : servers) {
        if (currentTime - server.getTimestamp() > timeToEvict && servers.remove(server)) {
          state.incrementAndGet();
          outdated = true;
        }
      }

      if (outdated) {
        tryToUpdate(service);
      }
    }
  }

  /**
   * Prepares randomized list of servers sent to clients. Puts each server on the list as many times
   * as indicated by relative server's weight. Relative server weight is calculated with this
   * special formula: resulting_server_list_len = number_of_servers_providing_service *
   * server_list_length_ratio number_of_times_on_list = ceil(resulting_server_list_len *
   * server's_weight / total_servers'_weight)
   *
   * @param service name of the service
   * @return list of servers' prototypes
   */
  private ServerList getServersList(String service) {
    Set<BackendServer> servers = serviceToServers.get(service);

    int listLen = servers.size() * SERVER_LIST_LENGTH_RATIO;
    int weightSum = 0;
    for (BackendServer server : servers) {
      weightSum += server.getWeight();
    }

    /* Create raw list first, as it is not possible to shuffle proto repeated field. */
    List<BackendServer> serverList = new LinkedList<>();
    for (BackendServer server : servers) {
      int timesOnList = (int) ceil(((double) server.getWeight() / (double) weightSum) * listLen);
      for (int i = 0; i < timesOnList; ++i) {
        serverList.add(server);
      }
    }
    Collections.shuffle(serverList);

    ServerList.Builder serverListBuilder = ServerList.newBuilder();
    for (BackendServer server : serverList) {
      Server serverPrototype = server.toProto();
      serverListBuilder.addServers(serverPrototype);
    }

    return serverListBuilder.build();
  }

  public void addOrRefreshServer(BackendServer server) {
    long currentTime = System.currentTimeMillis();
    server.setTimestamp(currentTime);

    for (String service : server.getServices()) {
      serviceToServers.putIfAbsent(service, ConcurrentHashMap.newKeySet());
      Set<BackendServer> servers = serviceToServers.get(service);
      if (servers.add(server)) {
        state.incrementAndGet();
        tryToUpdate(service);
      }
    }
  }

  public boolean dropServer(BackendServer server) {
    boolean res = true;

    for (String service : server.getServices()) {
      Set<BackendServer> servers = serviceToServers.get(service);

      if (servers.remove(server)) {
        state.incrementAndGet();
        tryToUpdate(service);
      } else {
        res = false;
      }
    }

    return res;
  }

  /**
   * Attempts to update lbService with servers list for given service. Firstly, saves current state.
   * Secondly, builds servers proto list. Lastly, checks if the state has changed in the meantime.
   * If so, then abandons.
   */
  private void tryToUpdate(String service) {
    long curState = state.get();
    ServerList servers = getServersList(service);
    if (curState == state.get()) {
      synchronized (lbService) {
        if (curState == state.get()) {
          lbService.update(service, servers);
        }
      }
    }
  }

  /** This class is a container for server info stored in DirectServerList. */
  public static class BackendServer {
    public static final int DEFAULT_WEIGHT = 100;

    private InetSocketAddress address;
    private List<String> services;
    private int weight;
    private long timestamp;

    public BackendServer(InetSocketAddress address, List<String> services) {
      this(address, services, 0);
    }

    public BackendServer(InetSocketAddress address, List<String> services, int weight) {
      this.address = address;
      this.services = services;
      this.weight = weight;
      this.timestamp = 0L;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof BackendServer)) {
        return false;
      }

      BackendServer bs = (BackendServer) o;
      return bs.address.equals(address);
    }

    @Override
    public int hashCode() {
      return address.hashCode();
    }

    public Server toProto() {
      return Server.newBuilder()
          .setIpAddress(ByteString.copyFrom(address.getAddress().getAddress()))
          .setPort(address.getPort())
          .build();
    }

    public List<String> getServices() {
      return this.services;
    }

    public void setTimestamp(long timestamp) {
      this.timestamp = timestamp;
    }

    public long getTimestamp() {
      return this.timestamp;
    }

    public InetSocketAddress getAddress() {
      return this.address;
    }

    public int getWeight() {
      return this.weight;
    }
  }
}
