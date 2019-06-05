package com.rtbhouse.grpc.loadbalancer.standalone;

import io.grpc.ServerBuilder;
import java.io.IOException;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoadBalancerServer {
  private static final Logger logger = LoggerFactory.getLogger(LoadBalancerServer.class);

  private static io.grpc.Server server;

  /**
   * @param port the server is running on
   * @param heartbeatsFrequency specifies how often backend servers must report to signup service
   *     not to be marked as outdated
   * @param timeToEvict the time after outdated backend servers are evicted
   * @throws IOException
   */
  public LoadBalancerServer(int port, int heartbeatsFrequency, int timeToEvict) throws IOException {
    this(ServerBuilder.forPort(port), heartbeatsFrequency, timeToEvict);
  }

  /**
   * @param heartbeatsFrequency specifies how often backend servers must report to signup service
   *     not to be marked as outdated
   * @param timeToEvict the time after outdated backend servers are evicted
   * @throws IOException
   */
  public LoadBalancerServer(
      ServerBuilder<?> serverBuilder, int heartbeatsFrequency, int timeToEvict) throws IOException {
    LoadBalanceService lbService = new LoadBalanceService();
    AvailableServerList serverListFetcher =
        new AvailableServerList(lbService, heartbeatsFrequency, timeToEvict);
    server =
        serverBuilder
            .addService(new SignupService(serverListFetcher, heartbeatsFrequency))
            .addService(lbService)
            .build();
  }

  /*
   Example usage: java -jar loadbalancer-standalone/target/loadbalancer-standalone-1.0-shaded.jar \
                  -port 9090 -heartbeats-frequency 3000 -time-to-evict 4000
  */
  public static void main(String[] args) throws IOException, InterruptedException {
    Options options = new Options();
    options.addRequiredOption("p", "port", true, "loadbalancer-port");
    options.addRequiredOption(
        "hf",
        "heartbeats-frequency",
        true,
        "how often backend servers must send heartbeats to the LB (to stay on the "
            + "active servers list) (ms). LB will pass this information to servers.");
    options.addRequiredOption(
        "evict",
        "time-to-evict",
        true,
        "if LB won't receive heartbeat that long, backend server is evicted from LB "
            + "list (ms)");

    try {
      CommandLineParser parser = new DefaultParser();
      CommandLine cmd = parser.parse(options, args);

      int port = Integer.parseInt(cmd.getOptionValue("p"));
      int heartbeatsFrequency = Integer.parseInt(cmd.getOptionValue("hf"));
      int timeToEvict = Integer.parseInt(cmd.getOptionValue("evict"));

      LoadBalancerServer lbServer = new LoadBalancerServer(port, heartbeatsFrequency, timeToEvict);
      lbServer.start();
      logger.info("Loadbalancer started at port {}", port);
      lbServer.blockUntilShutdown();
    } catch (ParseException e) {
      System.err.println(e.getLocalizedMessage());
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("ant", options);
    }
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
