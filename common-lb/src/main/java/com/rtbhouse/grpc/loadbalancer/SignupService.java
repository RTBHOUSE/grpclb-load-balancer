package com.rtbhouse.grpc.loadbalancer;

import io.grpc.stub.StreamObserver;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SignupService extends ServerSignupGrpc.ServerSignupImplBase {
  private static final Logger logger = LoggerFactory.getLogger(SignupService.class);
  private final int updatesFrequency;
  private final UpdatableServerList serverListFetcher;

  public SignupService(UpdatableServerList serverListFetcher) {
    this(serverListFetcher, 1000);
  }

  public SignupService(UpdatableServerList serverListFetcher, int updatesFrequency) {
    this.serverListFetcher = serverListFetcher;
    this.updatesFrequency = updatesFrequency;
  }

  /**
   * Accepts signup requests and state reports from backend servers. The first message from backend
   * server is expected to contain server details. The loadbalancer replies with updates frequency.
   * Next messages are treated as heartbeats and server will reply to them with only confirmation
   * field set. Each information received from backend server is then passed to serverListFetcher.
   */
  @Override
  public StreamObserver<ServerReport> signup(
      StreamObserver<LoadBalancerSignupReply> responseObserver) {
    return new StreamObserver<ServerReport>() {
      private AtomicBoolean first = new AtomicBoolean(true);
      private BackendServer server;

      @Override
      public void onNext(ServerReport serverReport) {
        if (first.compareAndSet(true, false)) {
          try {
            server = buildBackendServer(serverReport);
          } catch (UnknownHostException e) {
            logger.error("Server IP address is of illegal length", e);
            responseObserver.onError(e);
            return;
          }
          LoadBalancerSignupReply initialResponse =
              LoadBalancerSignupReply.newBuilder()
                  .setConfirmed(true)
                  .setUpdatesFrequency(updatesFrequency)
                  .build();
          logger.debug("Sending initial response to {}", server.getAddress().toString());
          responseObserver.onNext(initialResponse);
        } else {
          logger.debug("Sending refresh confirmation to {}", server.getAddress().toString());
          LoadBalancerSignupReply response =
              LoadBalancerSignupReply.newBuilder().setConfirmed(true).build();
          responseObserver.onNext(response);
        }

        serverListFetcher.addOrRefreshServer(server);
      }

      @Override
      public void onError(Throwable throwable) {
        logger.error(throwable.getMessage(), throwable);
        if (server != null) {
          serverListFetcher.dropServer(server);
        }
      }

      @Override
      public void onCompleted() {
        responseObserver.onCompleted();
        logger.debug("onCompleted");
        if (server != null) {
          serverListFetcher.dropServer(server);
        }
      }

      private BackendServer buildBackendServer(ServerReport serverData)
          throws UnknownHostException {
        InetSocketAddress address =
            new InetSocketAddress(
                InetAddress.getByAddress(
                    serverData.getServerDetails().getIpAddress().toByteArray()),
                serverData.getServerDetails().getPort());
        List<String> services = serverData.getServerDetails().getServicesList();
        int weight = serverData.getServerDetails().getWeight();
        if (weight == 0) {
          weight = BackendServer.DEFAULT_WEIGHT;
        }

        return new BackendServer(address, services, weight);
      }
    };
  }
}
