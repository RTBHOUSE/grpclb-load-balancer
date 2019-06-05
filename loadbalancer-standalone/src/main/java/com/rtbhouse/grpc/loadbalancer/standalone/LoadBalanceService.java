package com.rtbhouse.grpc.loadbalancer.standalone;

import com.google.protobuf.Duration;
import io.grpc.lb.v1.*;
import io.grpc.stub.StreamObserver;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoadBalanceService extends LoadBalancerGrpc.LoadBalancerImplBase {
  private static final Logger logger = LoggerFactory.getLogger(LoadBalanceService.class);
  private final Duration statsInterval = Duration.newBuilder().setSeconds(0).build();
  /** key: name of service value: list of servers providing such service */
  private final Map<String, ServerList> servers = new ConcurrentHashMap<>();
  /** key: name of service value: list of clients subscribed to the service */
  private final Map<String, Set<StreamObserver<LoadBalanceResponse>>> clients =
      new ConcurrentHashMap<>();

  @Override
  public StreamObserver<LoadBalanceRequest> balanceLoad(
      final StreamObserver<LoadBalanceResponse> responseObserver) {

    return new StreamObserver<LoadBalanceRequest>() {
      private AtomicBoolean first = new AtomicBoolean(true);
      private String service;

      @Override
      public void onNext(LoadBalanceRequest loadBalanceRequest) {
        if (first.compareAndSet(true, false)) {
          service = loadBalanceRequest.getInitialRequest().getName();
          logger.debug("Got initial request from client for service {}", service);

          clients.putIfAbsent(service, ConcurrentHashMap.newKeySet());
          servers.putIfAbsent(service, ServerList.newBuilder().build());

          clients.get(service).add(responseObserver);

          InitialLoadBalanceResponse initialResponse =
              InitialLoadBalanceResponse.newBuilder()
                  .setClientStatsReportInterval(statsInterval)
                  .build();

          LoadBalanceResponse response =
              LoadBalanceResponse.newBuilder().setInitialResponse(initialResponse).build();
          responseObserver.onNext(response);
        }

        LoadBalanceResponse response =
            LoadBalanceResponse.newBuilder().setServerList(servers.get(service)).build();

        logger.debug(
            "Sending to client list of size: {}", response.getServerList().getServersList().size());
        responseObserver.onNext(response);
      }

      @Override
      public void onError(Throwable throwable) {
        removeClient();
        logger.error(throwable.getMessage(), throwable);
      }

      @Override
      public void onCompleted() {
        responseObserver.onCompleted();
        removeClient();
        logger.debug("onCompleted");
      }

      private void removeClient() {
        if (service == null) {
          return;
        }
        clients.get(service).remove(responseObserver);
      }
    };
  }

  /**
   * Updates "servers" with the new servers lists for the given service. Informs every client
   * subscribed to the service about the change.
   *
   * @param service name of the service
   * @param newServerList updated list of servers providing such service
   */
  public synchronized void update(String service, ServerList newServerList) {
    logger.debug("Sending updates for {}", service);

    servers.put(service, newServerList);

    /* Send new servers list to clients. */
    LoadBalanceResponse response =
        LoadBalanceResponse.newBuilder().setServerList(newServerList).build();

    /* Make sure not to iterate over null */
    clients.putIfAbsent(service, ConcurrentHashMap.newKeySet());

    for (StreamObserver<LoadBalanceResponse> client : clients.get(service)) {
      client.onNext(response);
    }
  }
}
