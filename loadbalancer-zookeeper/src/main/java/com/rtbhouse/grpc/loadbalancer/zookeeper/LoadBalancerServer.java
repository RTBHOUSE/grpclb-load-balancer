package com.rtbhouse.grpc.loadbalancer.zookeeper;

import com.rtbhouse.grpc.loadbalancer.LoadBalanceService;
import io.grpc.ServerBuilder;
import java.io.IOException;
import org.apache.commons.cli.*;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;

public class LoadBalancerServer {
  private static io.grpc.Server server;
  private final CuratorFramework zkClient;
  private final ZKServerListFetcher zkServerListFetcher;

  public LoadBalancerServer(
      ServerBuilder<?> serverBuilder, CuratorFramework zkClient, String zkSrvsListPath)
      throws IOException {
    this.zkClient = zkClient;
    LoadBalanceService lbService = new LoadBalanceService();
    zkServerListFetcher = new ZKServerListFetcher(zkClient, zkSrvsListPath, lbService);
    server = serverBuilder.addService(lbService).build();
  }

  public LoadBalancerServer(Integer port, CuratorFramework zkClient, String zkSrvsListPath)
      throws IOException {
    this(ServerBuilder.forPort(port), zkClient, zkSrvsListPath);
  }

  /**
   * Example usage: mvn exec:java -Dexec.mainClass=com.rtbhouse.grpc.loadbalancer.LoadBalancerServer
   * -Dexec.args="-p 9090 -zkaddr 127.0.0.1:2181 -zkpath /servers"
   */
  public static void main(String[] args) throws IOException, InterruptedException, ParseException {
    Options options = new Options();
    options.addRequiredOption("p", "port", true, "loadbalancer-port port");
    options.addRequiredOption("zkaddr", "zookeeper-address", true, "zookeeper-address ip:port");
    options.addRequiredOption("zkpath", "zookeeper-path", true, "zookeeper-path path");
    CommandLineParser parser = new DefaultParser();
    CommandLine cmd = parser.parse(options, args);

    int port = Integer.parseInt(cmd.getOptionValue("p"));
    String zkAddress = cmd.getOptionValue("zkaddr");
    String zkPath = cmd.getOptionValue("zkpath");

    RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000, 3);
    CuratorFramework zkClient = CuratorFrameworkFactory.newClient(zkAddress, retryPolicy);
    zkClient.start();

    LoadBalancerServer lbServer = new LoadBalancerServer(port, zkClient, zkPath);
    lbServer.start();
    lbServer.blockUntilShutdown();
  }

  public void start() throws IOException {
    server.start();
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread() {
              @Override
              public void run() {
                // Use stderr here since the logger may has been reset by its JVM shutdown hook.
                System.err.println("*** shutting down gRPC server since JVM is shutting down");
                LoadBalancerServer.server.shutdown();
                zkClient.close();
                System.err.println("*** server shut down");
              }
            });
  }

  /** Stop serving requests and shutdown resources. */
  public void stop() {
    if (server != null) {
      server.shutdown();
      zkServerListFetcher.stop();
    }
  }

  /** Await termination on the main thread since the grpc library uses daemon threads. */
  private void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }
}
