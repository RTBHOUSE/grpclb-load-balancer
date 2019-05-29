package com.rtbhouse.grpc.loadbalancer;

import com.google.protobuf.ByteString;
import com.rtbhouse.grpc.loadbalancer.ServerSignupGrpc.ServerSignupStub;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class should be used only through LoadBalancerConnector, never directly. It handles
 * registering backend server in single load balancer, using SignupService, defined in signup.proto.
 *
 * <p>Server sends initial report to LB with its details, then receives initial response from LB,
 * and immediately starts sending heartbeats. LB includes server in its policy just after receiving
 * the first heartbeat (not initial report). Server is removed from the policy when it calls
 * .onCompleted() on the RPC or if the LB won't get heartbeat longer than configured limit.
 *
 * <p>Connector has a state, which can change in the following manner:
 *
 * <p>stopped -> connecting -> working -> paused -> connecting -> working -> (...)
 *
 * <p>stopped -> connecting -> paused -> connecting -> working -> (...)
 *
 * <p>The intermediate 'connecting' state is set, when RPC to the load balancer was started, but the
 * LB haven't responded yet. It is a short phase, when LB is online, or may last longer, when LB is
 * offline.
 *
 * <p>We use a gRPC exponential backoff retries to send requests in increasing intervals of time
 * until LB is online. This ensures that there is at most one RPC opened at any given time.
 */
class SingleLoadBalancerConnector {
  private static final Logger logger = LoggerFactory.getLogger(SingleLoadBalancerConnector.class);
  private final ManagedChannelBuilder channelBuilder;
  private final InetAddress serverAddress;
  private final int serverPort;
  private final String[] services;
  private final int serverWeight;
  private StreamObserver<ServerReport> reportObserver;
  private ScheduledFuture<?> signUpHandle;

  private ManagedChannel lbChannel;
  private ServerSignupStub lbAsyncStub;

  private SingleConnectorStatus status;

  private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

  SingleLoadBalancerConnector(
      int serverPort,
      InetAddress serverAddress,
      String lbAddress,
      int lbPort,
      String[] services,
      int serverWeight) {
    this(
        ManagedChannelBuilder.forAddress(lbAddress, lbPort).usePlaintext(),
        serverPort,
        serverAddress,
        services,
        serverWeight);
  }

  SingleLoadBalancerConnector(
      ManagedChannelBuilder<?> channelBuilder,
      int serverPort,
      InetAddress serverAddress,
      String[] services,
      int serverWeight) {
    this.serverPort = serverPort;
    this.serverAddress = serverAddress;
    this.services = services;
    this.serverWeight = serverWeight;
    this.status = SingleConnectorStatus.STOPPED;
    this.channelBuilder = channelBuilder;
  }

  /**
   * Builds channel from given channel builder and creates non-blocking stub for this channel.
   * Connects with loadbalancer (starts RPC), marking connector as connecting and sends initial
   * report.
   */
  synchronized void start() {
    lbChannel = channelBuilder.build();
    lbAsyncStub = ServerSignupGrpc.newStub(lbChannel);

    status = SingleConnectorStatus.CONNECTING;

    connectWithLoadBalancer();
    sendReport();
  }

  /**
   * When rpc is still opened, ends it (in case of WORKING state, server is included in LB policy
   * and now it is being removed). Then changes connector status to stopped and closes the channel.
   */
  synchronized void stop() {
    if (status == SingleConnectorStatus.WORKING || status == SingleConnectorStatus.CONNECTING) {
      /* We have to change state to stopped before calling endRPC(), because in
       * SignupReplyStreamObserver.onCompleted() callback
       * we would reconnect with LB, if we would see state e.g. WORKING */
      status = SingleConnectorStatus.STOPPED;
      endRPC();
    }

    status = SingleConnectorStatus.STOPPED;

    try {
      if (!lbChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS)) {
        logger.warn("Channel did not terminate in 5 seconds, force shutdown..");
        lbChannel.shutdownNow();
      }
    } catch (InterruptedException e) {
      logger.warn("InterruptedException during channel termination", e);
    }
  }

  /**
   * Sets connection (starts RPC) with the given load balancer. Using gRPC exponential backoff retry
   * mechanism, sends initial reports periodically in increasing time intervals until load balancer
   * is ready to receive it. Once the first reply from load balancer is received, starts sending
   * reports with the requested frequency. Callbacks (onCompleted, onError) from
   * SignupReplyStreamObserver ensure reconnecting with load balancer in case of an error (ex. loss
   * of network connection) or when load balancer calls onCompleted even though the server is
   * working correctly.
   */
  private void connectWithLoadBalancer() {
    logger.debug("Connecting to load balancer (starting signup rpc)...");
    reportObserver = lbAsyncStub.signup(new SignupReplyStreamObserver());
  }

  /**
   * Only if the connector wasn't already stopped or paused, it starts new rpc, marks the new status
   * and sends the initial report.
   */
  private synchronized void reconnectWithLoadBalancer() {
    if (status == SingleConnectorStatus.STOPPED || status == SingleConnectorStatus.PAUSED) {
      logger.debug("No reconnect - connector stopped, paused");
      return;
    }

    logger.debug("Reconnecting with load balancer...");
    status = SingleConnectorStatus.CONNECTING;
    connectWithLoadBalancer();
    sendReport();
  }

  /**
   * Prepares and sends heartbeat, or an initial report with information about the server. In the
   * initial message includes server address, server port, list of services provided by the server
   * and its weight, if set.
   */
  private void sendReport() {
    ServerReport report;

    if (status == SingleConnectorStatus.CONNECTING) { // prepare initial report
      Server.Builder serverDetails =
          com.rtbhouse.grpc.loadbalancer.Server.newBuilder()
              .setIpAddress(ByteString.copyFrom(serverAddress.getAddress()))
              .setPort(serverPort)
              .addAllServices(Arrays.asList(services));

      if (serverWeight != LoadBalancerConnector.WEIGHT_NOT_SET) {
        serverDetails.setWeight(serverWeight);
      }

      report =
          ServerReport.newBuilder()
              .setReadyToServe(true)
              .setServerDetails(serverDetails.build())
              .build();

      logger.info(
          "Sending initial report: ready to serve true, server address {}, services {}, port {}, weight {}",
          serverAddress.toString(),
          String.join(", ", report.getServerDetails().getServicesList()),
          serverPort,
          report.getServerDetails().getWeight());

    } else if (status == SingleConnectorStatus.WORKING) { // prepare heartbeat
      report = ServerReport.newBuilder().setReadyToServe(true).build();
      logger.debug("Sending heartbeat: ready to serve {}", report.getReadyToServe());

    } else {
      logger.info(
          "Requested to send report when connector status is neither CONNECTING nor WORKING "
              + "(it is: {})",
          status);
      return;
    }

    try {
      reportObserver.onNext(report);
    } catch (IllegalStateException e) {
      logger.info("Trying to send server report but the rpc has already ended");
    }
  }

  /**
   * Cleans up the current rpc that's being closed. Cancels sign-up if needed and calls
   * onCompleted().
   */
  private synchronized void endRPC() {
    logger.debug("Ending RPC...");

    if (signUpHandle != null && !signUpHandle.isCancelled()) {
      signUpHandle.cancel(true);
    }
    reportObserver.onCompleted();
  }

  /** Marks that the connector was paused and ends current rpc. */
  synchronized void pause() {
    status = SingleConnectorStatus.PAUSED;
    endRPC();
  }

  /** Marks that the connector status changed to resumed and reconnects with load balancer. */
  synchronized void resume() {
    status = SingleConnectorStatus.CONNECTING;
    reconnectWithLoadBalancer();
  }

  /** For details about those states, read class comment. */
  private enum SingleConnectorStatus {
    STOPPED,
    CONNECTING,
    WORKING,
    PAUSED;
  }

  private class SignupReplyStreamObserver implements StreamObserver<LoadBalancerSignupReply> {

    @Override
    public void onNext(LoadBalancerSignupReply reply) {
      if (reply.getHeartbeatsFrequency() != 0) {
        /* check if heartbeats_frequency was set (if it is the initial response) */
        synchronized (SingleLoadBalancerConnector.this) {
          handleInitialResponse(reply);
        }
      } else {
        logger.debug("Got heartbeat-ack from LB {}", reply.getConfirmed());
      }
    }

    @Override
    public void onError(Throwable throwable) {
      logger.warn("ServerSignup failed: {}", Status.fromThrowable(throwable).getDescription());

      synchronized (SingleLoadBalancerConnector.this) {
        if (status == SingleConnectorStatus.WORKING || status == SingleConnectorStatus.CONNECTING) {
          endRPC();
          if (lbChannel.getState(true) != ConnectivityState.READY) {
            lbChannel.notifyWhenStateChanged(
                lbChannel.getState(true),
                SingleLoadBalancerConnector.this::reconnectWithLoadBalancer);
          } else {
            reconnectWithLoadBalancer();
          }
        }
      }
    }

    @Override
    public void onCompleted() {
      logger.debug("ServerSignup: loadbalancer closed connection");
      if (status == SingleConnectorStatus.WORKING || status == SingleConnectorStatus.CONNECTING)
        endRPC();
      reconnectWithLoadBalancer();
    }

    private void handleInitialResponse(LoadBalancerSignupReply reply) {
      if (status == SingleConnectorStatus.CONNECTING) {
        logger.debug(
            "Got initial response: acknowledged = {}, LB requests heartbeats frequency = {}",
            reply.getConfirmed(),
            reply.getHeartbeatsFrequency());

        status = SingleConnectorStatus.WORKING;

        /* schedule heartbeats */
        signUpHandle =
            scheduler.scheduleWithFixedDelay(
                SingleLoadBalancerConnector.this::sendReport,
                0, /* initial delay */
                reply.getHeartbeatsFrequency(), /* further delays */
                TimeUnit.MILLISECONDS);
      } else if (status == SingleConnectorStatus.WORKING) {
        logger.warn("Load balancer sent initial response more than once!");
      }
    }
  }
}
