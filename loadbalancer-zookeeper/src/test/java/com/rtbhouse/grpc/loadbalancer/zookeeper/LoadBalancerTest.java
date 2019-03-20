/** WARNING: Commented due to radical changes in load balancing policy. */
// package com.rtbhouse.grpc.loadbalancer.zookeeper;
//
// import static org.junit.Assert.assertEquals;
// import static org.mockito.Matchers.any;
// import static org.mockito.Mockito.*;
//
// import com.google.common.net.InetAddresses;
// import io.grpc.ManagedChannel;
// import io.grpc.inprocess.InProcessChannelBuilder;
// import io.grpc.inprocess.InProcessServerBuilder;
// import io.grpc.lb.v1.InitialLoadBalanceRequest;
// import io.grpc.lb.v1.LoadBalanceRequest;
// import io.grpc.lb.v1.LoadBalanceResponse;
// import io.grpc.lb.v1.LoadBalancerGrpc;
// import io.grpc.stub.StreamObserver;
// import io.grpc.testing.GrpcCleanupRule;
// import java.util.HashSet;
// import java.util.Iterator;
// import java.util.Random;
// import java.util.Set;
// import org.apache.curator.framework.CuratorFramework;
// import org.apache.curator.framework.CuratorFrameworkFactory;
// import org.apache.curator.retry.RetryNTimes;
// import org.apache.curator.test.TestingServer;
// import org.junit.After;
// import org.junit.Before;
// import org.junit.Rule;
// import org.junit.Test;
// import org.mockito.ArgumentCaptor;
//
// public class LoadBalancerTest {
//  private static final int MASSIVE_TEST_ACTIONS = 50;
//  private static final int ADD_BACKEND_SERVER = 0;
//  private static final int REMOVE_BACKEND_SERVER = 1;
//
//  // This rule manages automatic graceful shutdown for the registered channel at the end of test.
//  @Rule public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();
//  private final String zkSrvsListPath = "/servers";
//  private final Random random = new Random();
//  private CuratorFramework curatorFramework;
//  private TestingServer zookeeperServer;
//  private LoadBalancerServer loadBalancerServer;
//  private ManagedChannel inProcessChannel;
//
//  @Before
//  public void beforeEachTest() throws Exception {
//    zookeeperServer = new TestingServer();
//
//    curatorFramework =
//        CuratorFrameworkFactory.newClient(
//            zookeeperServer.getConnectString(), new RetryNTimes(10, 100));
//    curatorFramework.start();
//
//    // Generate a unique in-process server name.
//    String loadBalancerName = InProcessServerBuilder.generateName();
//    /* Use directExecutor for both InProcessServerBuilder and InProcessChannelBuilder can reduce
// the
//    usage timeouts and latches in test. */
//    loadBalancerServer =
//        new LoadBalancerServer(
//            InProcessServerBuilder.forName(loadBalancerName).directExecutor(),
//            curatorFramework,
//            zkSrvsListPath);
//    loadBalancerServer.start();
//
//    inProcessChannel =
//        grpcCleanup.register(
//            InProcessChannelBuilder.forName(loadBalancerName).directExecutor().build());
//  }
//
//  @After
//  public void afterEachTest() throws Exception {
//    loadBalancerServer.stop();
//    zookeeperServer.close();
//    curatorFramework.close();
//  }
//
//  @Test
//  public void shouldGracefullyShutdown() {}
//
//  @Test
//  public void massiveTest() throws Exception {
//    Set<String> backendServerAddresses = new HashSet<>();
//
//    @SuppressWarnings("unchecked")
//    StreamObserver<LoadBalanceResponse> responseObserver =
//        (StreamObserver<LoadBalanceResponse>) mock(StreamObserver.class);
//    LoadBalancerGrpc.LoadBalancerStub stub = LoadBalancerGrpc.newStub(inProcessChannel);
//
//    StreamObserver<LoadBalanceRequest> requestObserver = stub.balanceLoad(responseObserver);
//
//    verify(responseObserver, never()).onNext(any(LoadBalanceResponse.class));
//
//    ArgumentCaptor<LoadBalanceResponse> responsesCaptor =
//        ArgumentCaptor.forClass(LoadBalanceResponse.class);
//    int expectedResponsesCount = 0;
//
//    // send initial request
//    LoadBalanceRequest initialRequest =
//        LoadBalanceRequest.newBuilder()
//            .setInitialRequest(
//                InitialLoadBalanceRequest.newBuilder()
//                    .setName(ZKServerListFetcher.MOCK_SERVICE)
//                    .build())
//            .build();
//    requestObserver.onNext(initialRequest);
//    expectedResponsesCount += 2;
//
//    // we expect to get initial response and the normal server list
//    verify(responseObserver, timeout(100).times(expectedResponsesCount))
//        .onNext(responsesCaptor.capture());
//    LoadBalanceResponse response =
//        responsesCaptor.getAllValues().get(1); // any initial response is fine
//    assertEquals(backendServerAddresses.size(), response.getServerList().getServersCount());
//    for (int i = 0; i < MASSIVE_TEST_ACTIONS; ++i) {
//      int action = random.nextInt(2);
//      switch (action) {
//        case ADD_BACKEND_SERVER:
//          String newBackendServer = generateRandomAddress();
//          curatorFramework.create().forPath(zkSrvsListPath + "/" + newBackendServer);
//          backendServerAddresses.add(newBackendServer);
//          expectedResponsesCount++;
//          break;
//
//        case REMOVE_BACKEND_SERVER:
//          Iterator<String> iter = backendServerAddresses.iterator();
//          if (iter.hasNext()) {
//            String toRemoveBackendServer = iter.next();
//            backendServerAddresses.remove(toRemoveBackendServer);
//            curatorFramework.delete().forPath(zkSrvsListPath + "/" + toRemoveBackendServer);
//            expectedResponsesCount++;
//          }
//          break;
//      }
//
//      verify(responseObserver, timeout(100).times(expectedResponsesCount))
//          .onNext(responsesCaptor.capture());
//      response = responsesCaptor.getValue();
//      assertEquals(backendServerAddresses.size(), response.getServerList().getServersCount());
//    }
//
//    requestObserver.onCompleted();
//    verify(responseObserver, timeout(100)).onCompleted();
//    verify(responseObserver, never()).onError(any(Throwable.class));
//  }
//
//  private String generateRandomAddress() {
//    String host = InetAddresses.fromInteger(random.nextInt()).getHostAddress();
//    int port = random.nextInt(65535 - 1024 + 1) + 1025;
//    return host + ":" + port;
//  }
// }
