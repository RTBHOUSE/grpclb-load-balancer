package com.rtbhouse.grpc.loadbalancer;

import com.google.common.net.HostAndPort;
import com.google.protobuf.ByteString;
import com.rtbhouse.grpc.loadbalancer.ServerSignupGrpc.ServerSignupStub;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SingleLoadBalancerConnector {
  private static final int WEIGHT_NOT_DEFINED = -1;
  private static final Logger logger = LoggerFactory.getLogger(SingleLoadBalancerConnector.class);

  private final InetAddress serverAddress;
  private final int serverPort;
  private final String lbAddress;
  private final String[] services;
  private final int serverWeight;
  private StreamObserver<ServerReport> reportObserver;
  private ScheduledFuture<?> signUpHandle;
  private int updatesFrequency;

  private ManagedChannel lbChannel;
  private ServerSignupStub lbAsyncStub;

  private boolean initialReportSent = false;
  private boolean stopped = false;
  private boolean paused = false;
  private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

  public SingleLoadBalancerConnector(
      int serverPort,
      InetAddress serverAddress,
      String lbAddress,
      String[] services,
      int serverWeight) {
    this.serverPort = serverPort;
    this.serverAddress = serverAddress;
    this.lbAddress = lbAddress;
    this.services = services;
    this.serverWeight = serverWeight;
  }

  /**
   * Fixes channel for given host and port and creates non-blocking stub for this channel. Connects
   * with loadbalancer and sends initial report.
   */
  protected void start() {
    HostAndPort hp = HostAndPort.fromString(lbAddress);
    String lbHost = hp.getHost();
    int lbPort = hp.getPort();

    lbChannel = ManagedChannelBuilder.forAddress(lbHost, lbPort).usePlaintext().build();
    lbAsyncStub = ServerSignupGrpc.newStub(lbChannel);
    connectWithLoadBalancer();
    sendReport(reportObserver);
  }

  /**
   * Marks that the connector was stopped. Calls onCompleted() so that the server is removed from LB
   * policy.
   */
  protected void stop() {
    stopped = true;
    cancelSignUp();
    if (initialReportSent) {
      reportObserver.onCompleted();
    }
    try {
      if (!lbChannel.shutdown().awaitTermination(1, TimeUnit.SECONDS)) {
        logger.warn("Channel did not terminate");
      }
    } catch (InterruptedException e) {
      logger.warn("InterruptedException during channel termination", e);
    }
  }

  /**
   * Sets connection with the given load balancer. Waits for sending the initial report until load
   * balancer is ready to receive it. Once the first reply from load balancer is received, starts
   * sending reports with the requested frequency. Reconnects with load balancer in case of an error
   * (ex. loss of network connection) or when load balancer calls onCompleted even though the server
   * is working correctly.
   */
  private void connectWithLoadBalancer() {
    reportObserver =
        lbAsyncStub
            .withWaitForReady()
            .signup(
                new StreamObserver<LoadBalancerSignupReply>() {

                  @Override
                  public void onNext(LoadBalancerSignupReply reply) {
                    if (reply.getUpdatesFrequency()
                        != 0) { // check if updates_frequency was set (first message)
                      updatesFrequency = reply.getUpdatesFrequency();
                      logger.debug(
                          "Got confirmation {} and frequency {}",
                          reply.getConfirmed(),
                          reply.getUpdatesFrequency());

                      signUpHandle =
                          scheduler.scheduleWithFixedDelay(
                              () -> sendReport(reportObserver),
                              0, // initial delay
                              updatesFrequency, // further delays
                              TimeUnit.MILLISECONDS);

                    } else {
                      logger.debug("Got confirmation {}", reply.getConfirmed());
                    }
                  }

                  @Override
                  public void onError(Throwable throwable) {
                    logger.warn(
                        "ServerSignup failed: {}",
                        Status.fromThrowable(throwable).getDescription());
                    if (!stopped) {
                      initialReportSent = false;
                      reconnectWithLoadBalancer(true);
                    }
                  }

                  @Override
                  public void onCompleted() {
                    logger.debug("Finished ServerSignup");
                    if (!stopped && !paused) {
                      initialReportSent = false;
                      reconnectWithLoadBalancer(true);
                    }
                  }
                });
  }

  /**
   * Ends previous rpc and starts a new one with sending the initial report. If needed, cancels
   * sign-up.
   */
  private void reconnectWithLoadBalancer(boolean shouldCancelSignUp) {
    if (shouldCancelSignUp) {
      cancelSignUp();
    }
    connectWithLoadBalancer();
    sendReport(reportObserver);
  }

  /**
   * It's a callback run when channel state change occurs. If channel becomes ready, it calls the
   * function that sends the reports. Otherwise, it registers another one-off callback. Note that
   * the lags between subsequent callbacks will be increasing, thanks to exponential backoff in
   * connection retries.
   */
  private void sendReportWhenReady() {
    if (paused || stopped) {
      return;
    }

    if (lbChannel.getState(false) == ConnectivityState.READY) {
      sendReport(reportObserver);
    } else {
      logger.debug("Channel state {}", lbChannel.getState(false).toString());
      lbChannel.notifyWhenStateChanged(lbChannel.getState(false), this::sendReportWhenReady);
    }
  }

  /**
   * Prepares and sends the report with information about the server. In the initial message
   * includes, along with ready_to_serve field, server address, server port, list of services
   * provided by the server and its weight, if set. Initial report is sent only when the channel is
   * ready. Value of ready_to_serve field is indicated by server health status.
   */
  private void sendReport(StreamObserver<ServerReport> reportObserver) {
    ServerReport report;

    if (!initialReportSent) {
      Server.Builder serverDetails =
          com.rtbhouse.grpc.loadbalancer.Server.newBuilder()
              .setIpAddress(ByteString.copyFrom(serverAddress.getAddress()))
              .setPort(serverPort)
              .addAllServices(Arrays.asList(services));

      if (serverWeight != WEIGHT_NOT_DEFINED) {
        serverDetails.setWeight(serverWeight);
      }

      report =
          ServerReport.newBuilder()
              .setReadyToServe(true)
              .setServerDetails(serverDetails.build())
              .build();

    } else {
      report = ServerReport.newBuilder().setReadyToServe(true).build();
      logger.debug("Sending report: ready to serve {}", report.getReadyToServe());
    }

    if (!initialReportSent) {
      if (lbChannel.getState(false) == ConnectivityState.READY) {
        try {
          logger.info(
              "Sending initial report: ready to serve {}, server address {}, services {}, port {}, weight {}",
              report.getReadyToServe(),
              InetAddress.getByAddress(report.getServerDetails().getIpAddress().toByteArray())
                  .getHostAddress(),
              String.join(", ", report.getServerDetails().getServicesList()),
              report.getServerDetails().getPort(),
              report.getServerDetails().getWeight());
        } catch (UnknownHostException e) {
          logger.error("Server IP address is of illegal length", e);
          reportObserver.onError(e);
        }
        initialReportSent = true;
        reportObserver.onNext(report);
      } else {
        lbChannel.notifyWhenStateChanged(lbChannel.getState(false), this::sendReportWhenReady);
      }
    } else {
      reportObserver.onNext(report);
    }
  }

  /** Cancels signing up only if the task was already scheduled. */
  private void cancelSignUp() {
    if (signUpHandle != null && !signUpHandle.isCancelled()) {
      signUpHandle.cancel(true);
    }
  }

  /**
   * Marks that the connector was paused. Cancels sign-up, marks that initial server report was not
   * sent in this round and calls onCompleted().
   */
  protected void pause() {
    paused = true;
    cancelSignUp();
    if (initialReportSent) {
      initialReportSent = false;
      reportObserver.onCompleted();
    }
  }

  /**
   * Marks that the connector is not paused anymore. Reconnects with load balancer without
   * cancelling sign-up.
   */
  protected void resume() {
    paused = false;
    reconnectWithLoadBalancer(false);
  }
}
