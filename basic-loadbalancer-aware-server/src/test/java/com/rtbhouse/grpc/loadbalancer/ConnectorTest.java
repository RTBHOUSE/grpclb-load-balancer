package com.rtbhouse.grpc.loadbalancer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.util.MutableHandlerRegistry;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunWith(JUnit4.class)
public class ConnectorTest {

  @Rule public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  private final MutableHandlerRegistry serviceRegistry = new MutableHandlerRegistry();
  private SingleLoadBalancerConnector singleConnector;

  // example test data
  private Integer backendServerPort = 9090;
  private InetAddress backendServerAddress = InetAddress.getLoopbackAddress();
  private String[] backendServices = {"hello.mimgrpc.me:2222", "hello2.mimgrpc.me:2222"};
  private Integer backendServerWeight = BackendServer.DEFAULT_WEIGHT;
  private Integer heartbeatsFrequency = 400;
  private AtomicInteger noOfReportsReceived = new AtomicInteger();
  private AtomicInteger noOfInitialReportsReceived = new AtomicInteger();

  private LoadBalancerSignupReply initialReply =
      LoadBalancerSignupReply.newBuilder()
          .setConfirmed(true)
          .setHeartbeatsFrequency(heartbeatsFrequency)
          .build();

  @Before
  public void setUp() throws Exception {
    String serverName = InProcessServerBuilder.generateName();
    grpcCleanup.register(
        InProcessServerBuilder.forName(serverName)
            .fallbackHandlerRegistry(serviceRegistry)
            .directExecutor()
            .build()
            .start());

    singleConnector =
        new SingleLoadBalancerConnector(
            InProcessChannelBuilder.forName(serverName).directExecutor(),
            backendServerPort,
            backendServerAddress,
            backendServices,
            backendServerWeight);

    noOfReportsReceived.set(0);
    noOfInitialReportsReceived.set(0);
  }

  @After
  public void tearDown() {
    singleConnector.stop();
  }

  @Test
  public void initialReportReceivedAsFirst() throws InterruptedException {
    final CountDownLatch reportsDelivered = new CountDownLatch(1);
    final AtomicReference<ServerReport> receivedReport = new AtomicReference<ServerReport>();

    // implement the fake service
    ServerSignupGrpc.ServerSignupImplBase getSignupImpl =
        new ServerSignupGrpc.ServerSignupImplBase() {
          @Override
          public StreamObserver<ServerReport> signup(
              StreamObserver<LoadBalancerSignupReply> responseObserver) {

            return new StreamObserver<ServerReport>() {
              @Override
              public void onNext(ServerReport report) {
                receivedReport.set(report);
                reportsDelivered.countDown();
              }

              @Override
              public void onError(Throwable throwable) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    serviceRegistry.addService(getSignupImpl);

    singleConnector.start();
    assertTrue(reportsDelivered.await(1, TimeUnit.SECONDS));
    assertTrue(receivedReport.get().hasServerDetails());
  }

  @Test
  public void initialReportCorrect() throws InterruptedException {
    final AtomicReference<ServerReport> initialReport = new AtomicReference<ServerReport>();
    final CountDownLatch initialReportDelivered = new CountDownLatch(1);

    // implement the fake service
    ServerSignupGrpc.ServerSignupImplBase getSignupImpl =
        new ServerSignupGrpc.ServerSignupImplBase() {
          @Override
          public StreamObserver<ServerReport> signup(
              StreamObserver<LoadBalancerSignupReply> responseObserver) {

            return new StreamObserver<ServerReport>() {
              @Override
              public void onNext(ServerReport report) {
                initialReport.set(report);
                initialReportDelivered.countDown();
              }

              @Override
              public void onError(Throwable throwable) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    serviceRegistry.addService(getSignupImpl);

    singleConnector.start();
    assertTrue(initialReportDelivered.await(1, TimeUnit.SECONDS));
    assertEquals(
        ServerReport.newBuilder()
            .setReadyToServe(true)
            .setServerDetails(
                Server.newBuilder()
                    .setIpAddress(ByteString.copyFrom(backendServerAddress.getAddress()))
                    .setPort(backendServerPort)
                    .setWeight(backendServerWeight)
                    .addAllServices(Arrays.asList(backendServices)))
            .build(),
        initialReport.get());
  }

  @Test
  public void noSignupReportsSentWithoutInitialConfirmation() throws InterruptedException {

    // implement the fake service
    ServerSignupGrpc.ServerSignupImplBase getSignupImpl =
        new ServerSignupGrpc.ServerSignupImplBase() {
          @Override
          public StreamObserver<ServerReport> signup(
              StreamObserver<LoadBalancerSignupReply> responseObserver) {

            return new StreamObserver<ServerReport>() {
              @Override
              public void onNext(ServerReport report) {
                noOfReportsReceived.getAndIncrement();
              }

              @Override
              public void onError(Throwable throwable) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    serviceRegistry.addService(getSignupImpl);

    singleConnector.start();
    Thread.sleep(2000);
    assertEquals(1, noOfReportsReceived.get());
  }

  @Test
  public void initialReportReceivedAfterOnCompleted() throws InterruptedException {
    final AtomicReference<StreamObserver<LoadBalancerSignupReply>> responseObserverRef =
        new AtomicReference<StreamObserver<LoadBalancerSignupReply>>();
    final CountDownLatch initialReportsDelivered = new CountDownLatch(2);

    // implement the fake service
    ServerSignupGrpc.ServerSignupImplBase getSignupImpl =
        new ServerSignupGrpc.ServerSignupImplBase() {
          @Override
          public StreamObserver<ServerReport> signup(
              StreamObserver<LoadBalancerSignupReply> responseObserver) {

            responseObserverRef.set(responseObserver);

            StreamObserver<ServerReport> requestObserver =
                new StreamObserver<ServerReport>() {
                  @Override
                  public void onNext(ServerReport report) {
                    if (report.hasServerDetails()) {
                      initialReportsDelivered.countDown();
                    }
                  }

                  @Override
                  public void onError(Throwable throwable) {}

                  @Override
                  public void onCompleted() {
                    responseObserver.onCompleted();
                  }
                };

            return requestObserver;
          }
        };

    serviceRegistry.addService(getSignupImpl);
    singleConnector.start();
    responseObserverRef.get().onCompleted();
    assertTrue(initialReportsDelivered.await(1, TimeUnit.SECONDS));
  }

  @Test
  public void initialReportReceivedAfterOnError() throws InterruptedException {
    final AtomicReference<StreamObserver<LoadBalancerSignupReply>> responseObserverRef =
        new AtomicReference<StreamObserver<LoadBalancerSignupReply>>();
    final CountDownLatch initialReportsDelivered = new CountDownLatch(2);
    final StatusRuntimeException fakeError = new StatusRuntimeException(Status.PERMISSION_DENIED);

    // implement the fake service
    ServerSignupGrpc.ServerSignupImplBase getSignupImpl =
        new ServerSignupGrpc.ServerSignupImplBase() {
          @Override
          public StreamObserver<ServerReport> signup(
              StreamObserver<LoadBalancerSignupReply> responseObserver) {

            responseObserverRef.set(responseObserver);

            StreamObserver<ServerReport> requestObserver =
                new StreamObserver<ServerReport>() {
                  @Override
                  public void onNext(ServerReport report) {
                    if (report.hasServerDetails()) {
                      initialReportsDelivered.countDown();
                    }
                  }

                  @Override
                  public void onError(Throwable throwable) {}

                  @Override
                  public void onCompleted() {
                    responseObserver.onCompleted();
                  }
                };

            return requestObserver;
          }
        };

    serviceRegistry.addService(getSignupImpl);

    singleConnector.start();
    responseObserverRef.get().onError(fakeError);
    assertTrue(initialReportsDelivered.await(1, TimeUnit.SECONDS));
  }

  @Test
  public void initialReportReceivedAfterPauseResume() throws InterruptedException {
    final AtomicReference<StreamObserver<LoadBalancerSignupReply>> responseObserverRef =
        new AtomicReference<StreamObserver<LoadBalancerSignupReply>>();
    final CountDownLatch initialReportsDelivered = new CountDownLatch(2);
    final CountDownLatch onCompletedReceived = new CountDownLatch(1);

    // implement the fake service
    ServerSignupGrpc.ServerSignupImplBase getSignupImpl =
        new ServerSignupGrpc.ServerSignupImplBase() {
          @Override
          public StreamObserver<ServerReport> signup(
              StreamObserver<LoadBalancerSignupReply> responseObserver) {

            responseObserverRef.set(responseObserver);

            StreamObserver<ServerReport> requestObserver =
                new StreamObserver<ServerReport>() {
                  @Override
                  public void onNext(ServerReport report) {
                    if (report.hasServerDetails()) {
                      initialReportsDelivered.countDown();
                      responseObserver.onNext(
                          LoadBalancerSignupReply.newBuilder()
                              .setHeartbeatsFrequency(500)
                              .setConfirmed(true)
                              .build());
                    }
                  }

                  @Override
                  public void onError(Throwable throwable) {}

                  @Override
                  public void onCompleted() {
                    onCompletedReceived.countDown();
                    responseObserver.onCompleted();
                  }
                };

            return requestObserver;
          }
        };

    Logger logger = LoggerFactory.getLogger(ConnectorTest.class);

    serviceRegistry.addService(getSignupImpl);
    singleConnector.start();
    singleConnector.pause();
    singleConnector.resume();

    assertTrue(initialReportsDelivered.await(6, TimeUnit.SECONDS));
  }
}
