package com.rtbhouse.grpc.lbexamples;

import com.rtbhouse.grpc.loadbalancer.BasicLoadbalancerAwareGrpcServer;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HelloWorldLBServer extends BasicLoadbalancerAwareGrpcServer {
  private static final Logger logger = LoggerFactory.getLogger(HelloWorldLBServer.class);

  public HelloWorldLBServer(
      int port,
      String[] lbAddresses,
      String[] services,
      int serverWeight,
      ExampleHealthService healthService)
      throws IOException {
    super(
        true,
        port,
        lbAddresses,
        healthService,
        services,
        serverWeight,
        new ExampleHealthCheckGreeterService(healthService, port));
  }

  public void start() throws IOException {
    super.start();
  }

  public static void main(String[] args) throws InterruptedException, IOException {
    if (args.length != 4) {
      System.err.println(
          "Usage: (...) server_port serving_period_size not_serving_period_size weight");
      System.exit(1);
    }

    System.out.println("Starting server...");

    String[] lbAddresses, services;
    int serverWeight;
    Properties properties = new Properties();
    InputStream inputStream =
        HelloWorldLBServer.class.getClassLoader().getResourceAsStream("config.properties");
    try {
      properties.load(inputStream);
      lbAddresses = properties.getProperty("loadbalancers_addresses").split(",");
      services = properties.getProperty("services").split(",");
      serverWeight = Integer.parseInt(args[3]);
    } catch (IOException e) {
      logger.error("Could not read config file", e);
      throw new RuntimeException("Could not read config file", e);
    }

    ExampleHealthService healthService =
        new ExampleHealthService(Integer.parseInt(args[1]), Integer.parseInt(args[2]));

    HelloWorldLBServer server =
        new HelloWorldLBServer(
            Integer.parseInt(args[0]), lbAddresses, services, serverWeight, healthService);

    server.start();
    server.blockUntilShutdown();
  }
}
