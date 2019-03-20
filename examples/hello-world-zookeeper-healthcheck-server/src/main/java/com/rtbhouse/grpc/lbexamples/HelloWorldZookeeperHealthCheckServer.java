package com.rtbhouse.grpc.lbexamples;

import com.rtbhouse.grpc.loadbalancer.zookeeper.ZooKeeperAwareBasicGrpcServer;
import java.io.IOException;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;

public class HelloWorldZookeeperHealthCheckServer extends ZooKeeperAwareBasicGrpcServer {
  private final CuratorFramework zkClient;

  public HelloWorldZookeeperHealthCheckServer(
      int port, CuratorFramework curatorFramework, ExampleHealthService healthService)
      throws IOException {
    super(
        port,
        "/servers",
        curatorFramework,
        true,
        healthService,
        new ExampleHealthCheckGreeterService(healthService, port));
    this.zkClient = curatorFramework;
  }

  public static void main(String[] args) throws InterruptedException, IOException {
    if (args.length != 4) {
      System.err.println(
          "Usage: (...) zookeeper_address:zookeeper_port server_port serving_period_size not_serving_period_size");
      System.exit(1);
    }

    System.out.println("Starting server...");
    RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000, 3);
    CuratorFramework zkClient = CuratorFrameworkFactory.newClient(args[0], retryPolicy);
    zkClient.start();

    ExampleHealthService healthService =
        new ExampleHealthService(Integer.parseInt(args[2]), Integer.parseInt(args[3]));

    HelloWorldZookeeperHealthCheckServer server =
        new HelloWorldZookeeperHealthCheckServer(
            Integer.parseInt(args[1]), zkClient, healthService);

    server.start();
    server.blockUntilShutdown();
  }

  public void start() throws IOException {
    super.start();
  }

  @Override
  protected void stop() {
    super.stop();
    zkClient.close();
  }
}
