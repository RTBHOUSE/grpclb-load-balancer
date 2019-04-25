package com.rtbhouse.grpc.loadbalancer.standalone;

import com.rtbhouse.grpc.loadbalancer.LoadBalancerSignupReply;
import com.rtbhouse.grpc.loadbalancer.ServerReport;
import com.rtbhouse.grpc.loadbalancer.ServerSignupGrpc;
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
  private final int heartbeatsFrequency;
  private final AvailableServerList availableServerList;

  public SignupService(AvailableServerList availableServerList) {
    this(availableServerList, 1000);
  }

  public SignupService(AvailableServerList availableServerList, int heartbeatsFrequency) {
    this.availableServerList = availableServerList;
    this.heartbeatsFrequency = heartbeatsFrequency;
  }

  /**
   * Accepts signup requests and state reports from backend servers. The first message from backend
   * server is expected to contain server details. The loadbalancer replies with heartbeats
   * frequency. Next messages are treated as heartbeats and server will reply to them with only
   * confirmation field set. Each information received from backend server is then passed to
   * availableServerList.
   */
  @Override
  public StreamObserver<ServerReport> signup(
      StreamObserver<LoadBalancerSignupReply> responseObserver) {
    return new StreamObserver<ServerReport>() {
      private AtomicBoolean first = new AtomicBoolean(true);
      private AvailableServerList.BackendServer server;

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
                  .setHeartbeatsFrequency(heartbeatsFrequency)
                  .build();
          logger.debug("Sending initial response to {}", server.getAddress().toString());
          responseObserver.onNext(initialResponse);
        } else {
          logger.debug("Sending heartbeat confirmation to {}", server.getAddress().toString());
          LoadBalancerSignupReply response =
              LoadBalancerSignupReply.newBuilder().setConfirmed(true).build();
          responseObserver.onNext(response);

          availableServerList.addOrRefreshServer(server);
        }
      }

      @Override
      public void onError(Throwable throwable) {
        logger.error(throwable.getMessage(), throwable);
        if (server != null) {
          availableServerList.dropServer(server);
        }
      }

      @Override
      public void onCompleted() {
        responseObserver.onCompleted();
        logger.debug("Server signup: onCompleted (backend server dropped from LB list)");
        if (server != null) {
          availableServerList.dropServer(server);
        }
      }

      private AvailableServerList.BackendServer buildBackendServer(ServerReport serverData)
          throws UnknownHostException {
        InetSocketAddress address =
            new InetSocketAddress(
                InetAddress.getByAddress(
                    serverData.getServerDetails().getIpAddress().toByteArray()),
                serverData.getServerDetails().getPort());
        List<String> services = serverData.getServerDetails().getServicesList();
        int weight = serverData.getServerDetails().getWeight();
        if (weight == 0) {
          weight = AvailableServerList.BackendServer.DEFAULT_WEIGHT;
        }

        return new AvailableServerList.BackendServer(address, services, weight);
      }
    };
  }
}
