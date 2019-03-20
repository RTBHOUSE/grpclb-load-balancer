package com.rtbhouse.grpc.loadbalancer.standalone;

import com.rtbhouse.grpc.loadbalancer.LoadBalanceService;
import com.rtbhouse.grpc.loadbalancer.SignupService;
import io.grpc.ServerBuilder;
import java.io.IOException;
import org.apache.commons.cli.*;

public class LoadBalancerServer {
  private static io.grpc.Server server;

  /**
   * @param port the server is running on
   * @param updatesFrequency specifies how often backend servers must report to signup service not
   *     to be marked as outdated
   * @param timeToEvict the time after outdated backend servers are evicted
   * @throws IOException
   */
  public LoadBalancerServer(int port, int updatesFrequency, int timeToEvict) throws IOException {
    this(ServerBuilder.forPort(port), updatesFrequency, timeToEvict);
  }

  /**
   * @param updatesFrequency specifies how often backend servers must report to signup service not
   *     to be marked as outdated
   * @param timeToEvict the time after outdated backend servers are evicted
   * @throws IOException
   */
  public LoadBalancerServer(ServerBuilder<?> serverBuilder, int updatesFrequency, int timeToEvict)
      throws IOException {
    LoadBalanceService lbService = new LoadBalanceService();
    DirectServerList serverListFetcher =
        new DirectServerList(lbService, updatesFrequency, timeToEvict);
    server =
        serverBuilder
            .addService(new SignupService(serverListFetcher, updatesFrequency))
            .addService(lbService)
            .build();
  }

  /**
   * Example usage: mvn exec:java
   * -Dexec.mainClass=com.rtbhouse.grpc.loadbalancer.standalone.LoadBalancerServer -Dexec.args="-p
   * 9090 -uf 1000 -evict 1200"
   */
  public static void main(String[] args) throws IOException, InterruptedException, ParseException {
    Options options = new Options();
    options.addRequiredOption("p", "port", true, "loadbalancer-port port");
    options.addRequiredOption(
        "uf",
        "updates-frequency",
        true,
        "how often backend servers must to report not to be marked as outdated (ms)");
    options.addRequiredOption(
        "evict",
        "time-to-evict",
        true,
        "time after backend server becomes marked as outdated (ms)");
    CommandLineParser parser = new DefaultParser();
    CommandLine cmd = parser.parse(options, args);

    int port = Integer.parseInt(cmd.getOptionValue("p"));
    int updatesFrequency = Integer.parseInt(cmd.getOptionValue("uf"));
    int timeToEvict = Integer.parseInt(cmd.getOptionValue("evict"));

    LoadBalancerServer lbServer = new LoadBalancerServer(port, updatesFrequency, timeToEvict);
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
                System.err.println("*** server shut down");
              }
            });
  }

  /** Stop serving requests and shutdown resources. */
  public void stop() {
    if (server != null) {
      server.shutdown();
    }
  }

  /** Await termination on the main thread since the grpc library uses daemon threads. */
  private void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }
}
