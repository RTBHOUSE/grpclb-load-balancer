package com.rtbhouse.grpc.loadbalancer.standalone;

import com.rtbhouse.grpc.loadbalancer.LoadBalancerConnector;
import com.rtbhouse.grpc.loadbalancer.tests.EchoGrpc;
import com.rtbhouse.grpc.loadbalancer.tests.EchoReply;
import com.rtbhouse.grpc.loadbalancer.tests.EchoRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetAddress;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/* There is a problem, that currently gRPC client supports resolving loadbalancer address only
 * from DNS SRV record.
 * It cannot be explicitly provided e.g. through a constructor. So, we have registered a dull
 * domain "hello.mimgrpc.me" which provides a proper record. We could try to mock the DNS request,
 * but it is difficult because it is done internally by gRPC library. */

@RunWith(MockitoJUnitRunner.class)
public class LoadBalancerIntegrationTest {
  private static final Logger logger = LoggerFactory.getLogger(LoadBalancerIntegrationTest.class);

  /* LoadBalancerConnector will be able to fetch it from DNS just as client does, then it won't be
   * obligatory to pass it explicitly. */
  private static final String[] lb_addresses = new String[] {"127.0.0.1:9090"};

  /* We need to specify, what services our server serves, because one LB can serve clients that use
   * different services.
   * Every service has to be in format "dns_host_name:port", and it has to be the same name and port
   * that you use to create client's channel (look at SimpleEchoClient class). */
  private static final String[] services = new String[] {"hello.mimgrpc.me:5000"};

  private int s1_response_count;
  private int s2_response_count;

  private LoadBalancerServer loadbalancer;

  @BeforeClass
  public static void enableGrpclb() {
    /* When you have "normal" command-line program, you can enable grpclb through command-line
     * argument: -Dio.grpc.internal.DnsNameResolverProvider.enable_grpclb=true*/
    System.setProperty("io.grpc.internal.DnsNameResolverProvider.enable_grpclb", "true");
  }

  //    @Before
  public void startLoadbalancer() throws IOException {
    loadbalancer = new LoadBalancerServer(9090, 3000, 4000);
    loadbalancer.start();
    logger.info(
        "Started loadbalancer server at port 9090. LB will reqeust heartbeats from backend servers every 3s, "
            + "and will evict a server when no heartbeat is sent for 4s.");
  }

  @Before
  public void zeroCounters() {
    s1_response_count = s2_response_count = 0;
  }

  @After
  public void stopLoadbalancer() {
    logger.info("Shutting down loadbalancer");
    loadbalancer.stop();
    logger.info("Loadbalancer down");
  }

  @Test
  public void failingBackendsTest() throws IOException, InterruptedException {
    startLoadbalancer();
    Server s1 = ServerBuilder.forPort(1111).addService(new EchoService("1")).build();
    LoadBalancerConnector s1_connector =
        new LoadBalancerConnector(
            1111, // our server port
            InetAddress.getLoopbackAddress(), // our server address
            lb_addresses,
            services);

    /* Typically, when you would have your custom server class (not gRPC's default),
     * you would have the connector inside the server class. */
    s1.start();
    logger.info("Started 1st backend server at port 1111 and weight 100.");
    s1_connector.start();
    logger.info(
        "Backend server(1111) registered in load balancer and started sending heartbeats to the LB");

    Server s2 = ServerBuilder.forPort(2222).addService(new EchoService("2")).build();
    LoadBalancerConnector s2_connector =
        new LoadBalancerConnector(2222, InetAddress.getLoopbackAddress(), lb_addresses, services);

    s2.start();
    logger.info("Started 2nd backend server at port 2222 and weight 100.");
    s2_connector.start();
    logger.info(
        "Backend server(2222) registered in load balancer and started sending heartbeats to the LB");
    logger.info("Wait 3s...");
    Thread.sleep(3000);

    logger.info("Starting client, making requests every 0,1s for 10 seconds");
    Thread client_thread = new SimpleEchoClient(100);
    client_thread.start();
    Thread.sleep(3000);

    logger.info("Shutting down 1111 server");
    s1_connector.stop();
    s1.shutdown();
    s1.awaitTermination();
    logger.info("Server 1111 down");
    Thread.sleep(3000);

    logger.info("Starting 1111 server again");
    s1 = ServerBuilder.forPort(1111).addService(new EchoService("1")).build();
    s1.start();
    s1_connector.start();
    logger.info("Server 1111 started");
    Thread.sleep(3000);

    logger.info("Shutting down 2222 server");
    s2_connector.stop();
    s2.shutdown();
    s2.awaitTermination();
    logger.info("Server 2222 down");
    Thread.sleep(3000);

    client_thread.join();

    logger.info("Shutting down 1111 server");
    s1_connector.stop();
    s1.shutdown();
    s1.awaitTermination();
    logger.info("Server 1111 down");

    Assert.assertEquals(100, s1_response_count + s2_response_count);
  }

  @Test
  public void differentWeightsTest() throws IOException, InterruptedException {
    startLoadbalancer();
    Server s1 = ServerBuilder.forPort(1111).addService(new EchoService("1")).build();
    LoadBalancerConnector s1_connector =
        new LoadBalancerConnector(
            1111, // our server port
            InetAddress.getLoopbackAddress(), // our server address
            lb_addresses,
            services,
            AvailableServerList.BackendServer.DEFAULT_WEIGHT);

    /* Typically, you would use a custom server class instead of gRPC's default implementation
     * and the LoadBalancerConnector object would be hidden inside of that class. */
    s1.start();
    logger.info("Started 1st backend server at port 1111 and weight 100.");
    s1_connector.start();
    logger.info(
        "Backend server(1111) registered in load balancer and started sending heartbeats to the LB");

    Server s2 = ServerBuilder.forPort(2222).addService(new EchoService("2")).build();
    LoadBalancerConnector s2_connector =
        new LoadBalancerConnector(
            2222,
            InetAddress.getLoopbackAddress(),
            lb_addresses,
            services,
            3 * AvailableServerList.BackendServer.DEFAULT_WEIGHT);

    s2.start();
    logger.info("Started 2nd backend server at port 2222 and weight 300.");
    s2_connector.start();
    logger.info(
        "Backend server(2222) registered in load balancer and started sending heartbeats to the LB");
    logger.info("Wait 3s...");
    Thread.sleep(3000);

    logger.info("Starting client, making requests every 0,1s for 10 seconds (total 100 requests)");
    Thread client_thread = new SimpleEchoClient(100);
    client_thread.start();
    client_thread.join();

    logger.info("Shutting down 1111 server");
    s1_connector.stop();
    s1.shutdown();
    s1.awaitTermination();
    logger.info("Server 1111 down");

    logger.info("Shutting down 2222 server");
    s2_connector.stop();
    s2.shutdown();
    s2.awaitTermination();
    logger.info("Server 2222 down");
    Thread.sleep(3000);

    Assert.assertEquals(100, s1_response_count + s2_response_count);
    Assert.assertTrue(Math.abs(3 * s1_response_count - s2_response_count) < 25);
  }

  @Test
  public void dissappearingLoadBalancer() throws IOException, InterruptedException {
    Server s1 = ServerBuilder.forPort(1111).addService(new EchoService("1")).build();
    LoadBalancerConnector s1_connector =
        new LoadBalancerConnector(
            1111, // our server port
            InetAddress.getLoopbackAddress(), // our server address
            lb_addresses,
            services,
            AvailableServerList.BackendServer.DEFAULT_WEIGHT); // TODO: new constructor without it

    s1.start();
    logger.info("Started backend server at port 1111 and weight 100.");
    s1_connector.start();
    logger.info("Connector started, will do a few pause/resume...");
    s1_connector.pause();
    s1_connector.resume();
    s1_connector.pause();
    Thread.sleep(100);
    s1_connector.resume();
    Thread.sleep(100);

    //    Thread.sleep(100);
    //    logger.info("Stopping load balancer and wait 5s...");
    //    stopLoadbalancer();

    Thread.sleep(5000);
    logger.info("Starting loadbalancer...");
    startLoadbalancer();

    Thread client_thread = new SimpleEchoClient(5);
    logger.info("Starting client, making 5 requests...");
    client_thread.start();
    client_thread.join();

    s1.shutdownNow();
    s1_connector.stop();

    Assert.assertEquals(5, s1_response_count);
  }

  private class EchoService extends EchoGrpc.EchoImplBase {
    private final String num;

    public EchoService(String num) {
      this.num = num;
    }

    @Override
    public void sayHello(EchoRequest req, StreamObserver<EchoReply> responseObserver) {
      EchoReply reply =
          EchoReply.newBuilder().setMessage(req.getMessage()).setServerNum(num).build();
      responseObserver.onNext(reply);
      responseObserver.onCompleted();
    }
  }

  private class SimpleEchoClient extends Thread {
    private final Logger logger =
        LoggerFactory.getLogger(LoadBalancerIntegrationTest.SimpleEchoClient.class);

    private int no_requests;

    public SimpleEchoClient(int no_requests) {
      this.no_requests = no_requests;
    }

    public void run() {
      ManagedChannel channel =
          ManagedChannelBuilder.forAddress("hello.mimgrpc.me", 5000).usePlaintext().build();
      EchoGrpc.EchoBlockingStub stub = EchoGrpc.newBlockingStub(channel);

      for (int i = 0; i < no_requests; i++) {
        try {
          EchoReply response = stub.sayHello(EchoRequest.newBuilder().setMessage("Hello!").build());
          logger.info("Got response from " + response.getServerNum());
          if (response.getServerNum().equals("1")) s1_response_count++;
          else if (response.getServerNum().equals("2")) s2_response_count++;
          Thread.sleep(100);
        } catch (InterruptedException e) {
          logger.warn("Client interrupted: ", e);
        } catch (StatusRuntimeException e) {
          logger.warn("Client: ", e);
          i--;
        }
      }

      logger.info(
          "Client summary: responses from s1: {}, from s2: {}",
          s1_response_count,
          s2_response_count);
      logger.info("Client exits");
    }
  }
}
