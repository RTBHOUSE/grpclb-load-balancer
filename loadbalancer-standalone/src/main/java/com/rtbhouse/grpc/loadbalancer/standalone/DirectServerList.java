package com.rtbhouse.grpc.loadbalancer.standalone;

import static java.lang.Math.ceil;

import com.rtbhouse.grpc.loadbalancer.BackendServer;
import com.rtbhouse.grpc.loadbalancer.LoadBalanceService;
import com.rtbhouse.grpc.loadbalancer.UpdatableServerList;
import io.grpc.lb.v1.Server;
import io.grpc.lb.v1.ServerList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DirectServerList implements UpdatableServerList {
  private static final int SERVER_LIST_LENGTH_RATIO = 3;

  private final LoadBalanceService lbService;
  /**
   * We aggregate the servers timestamps per service. key: name of service value: list of servers
   * providing such service
   */
  private final Map<String, Set<BackendServer>> serviceToServers = new ConcurrentHashMap<>();

  public DirectServerList(LoadBalanceService lbService) {
    this(lbService, 1000, 1200);
  }

  public DirectServerList(
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

  /** @param timeToEvict */
  private void evictOutdatedServers(long timeToEvict) {
    for (Map.Entry<String, Set<BackendServer>> serviceToServersKV : serviceToServers.entrySet()) {
      boolean outdated = false;
      long currentTime = System.currentTimeMillis();

      String service = serviceToServersKV.getKey();
      Set<BackendServer> servers = serviceToServersKV.getValue();

      for (BackendServer server : servers) {
        if (currentTime - server.getTimestamp() > timeToEvict && servers.remove(server)) {
          outdated = true;
        }
      }

      if (outdated) {
        lbService.update(service, getServersList(service));
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

  @Override
  public void addOrRefreshServer(BackendServer server) {
    long currentTime = System.currentTimeMillis();
    server.setTimestamp(currentTime);

    for (String service : server.getServices()) {
      serviceToServers.putIfAbsent(service, ConcurrentHashMap.newKeySet());

      Set<BackendServer> servers = serviceToServers.get(service);
      boolean existed = servers.contains(server);
      servers.add(server);

      if (!existed) {
        lbService.update(service, getServersList(service));
      }
    }
  }

  @Override
  public boolean dropServer(BackendServer server) {
    boolean res = true;

    for (String service : server.getServices()) {
      Set<BackendServer> servers = serviceToServers.get(service);
      if (servers.remove(server)) {
        lbService.update(service, getServersList(service));
      } else {
        res = false;
      }
    }

    return res;
  }
}
