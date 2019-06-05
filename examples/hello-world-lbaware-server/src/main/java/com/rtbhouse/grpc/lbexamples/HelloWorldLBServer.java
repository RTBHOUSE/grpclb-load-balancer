package com.rtbhouse.grpc.lbexamples;

import com.rtbhouse.grpc.loadbalancer.BasicLoadbalancerAwareGrpcServer;
import java.io.IOException;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HelloWorldLBServer {
  private static final Logger logger = LoggerFactory.getLogger(HelloWorldLBServer.class);

  /* Example usage:
     java -jar examples/hello-world-lbaware-server/target/hello-world-lbaware-server-1.0-shaded.jar\
      -p 2222 -lb "127.0.0.1:9090" -s "hello.mimgrpc.me:2222"

     or, if you are testing everything locally, on one machine, use environment variable LOCAL=1 :

     LOCAL=1 java -jar \
     examples/hello-world-lbaware-server/target/hello-world-lbaware-server-1.0-shaded.jar \
      -p 2222 -lb "127.0.0.1:9090" -s "hello.mimgrpc.me:2222"

     You may also use -lb "dns_resolve", if you want to resolve LB address automatically.
  */
  public static void main(String[] args) throws InterruptedException, IOException {
    Options options = new Options();
    options.addRequiredOption("p", "port", true, "Server port");
    options.addRequiredOption(
        "s",
        "services",
        true,
        "Comma-seperated list of services,"
            + " which this server serves. It is the same name:port, that client uses when creating gRPC channel, "
            + "e.g. hello.mimgrpc.me:2222");
    options.addRequiredOption(
        "lb",
        "loadbalancer-addresses",
        true,
        "Comma-seperated"
            + " loadbalancer addresses list (each in ip:port format), or"
            + " 'dns_resolve' if you want to resolve it automatically from DNS record of "
            + " the first service from -services option.");
    options.addOption(
        "w", "weight", true, "Server weight (default=100, every server" + " gets the same load)");
    options.addOption(
        "hc",
        "healthcheck",
        true,
        "Enable mock-healthcheck: if you want to see how loadbalancer"
            + " works when server becomes unhealthy. Provide argument in format X:Y (e.g. 3:1), which causes that"
            + " server will operate in cycles: X*5 seconds being healthy, Y*5 seconds being "
            + "unhealthy (we have '*5' because server checks health state only every "
            + "5 seconds). By defualt disabled.");
    options.addOption("h", "help", false, "Prints usage info.");

    BasicLoadbalancerAwareGrpcServer server;

    try {
      CommandLineParser parser = new DefaultParser();
      CommandLine cmd = parser.parse(options, args);

      if (cmd.hasOption("help")) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("ant", options);
      }

      String[] lbAddresses = cmd.getOptionValue("loadbalancer-addresses").split(",");
      String[] services = cmd.getOptionValue("services").split(",");
      int port = Integer.parseInt(cmd.getOptionValue("port"));
      int serverWeight = Integer.parseInt(cmd.getOptionValue("weight", "100"));

      BasicLoadbalancerAwareGrpcServer.Builder serverBuilder =
          BasicLoadbalancerAwareGrpcServer.builder()
              .natIsUsed()
              .setPort(port)
              .setServerWeight(serverWeight)
              .setNamesOfServices(services);

      if (cmd.hasOption("healthcheck")) {
        String[] healthcheckConfig = cmd.getOptionValue("healthcheck").split(":");
        int servingTime = Integer.parseInt(healthcheckConfig[0]);
        int notServingTime = Integer.parseInt(healthcheckConfig[1]);
        ExampleHealthService healthService = new ExampleHealthService(servingTime, notServingTime);

        serverBuilder
            .useHealthcheckService(healthService)
            .setBindableServices(new ExampleHealthCheckGreeterService(healthService, port));
      } else {
        serverBuilder.setBindableServices(new ExampleGreeterService(port));
      }

      if (lbAddresses[0].equals("dns_resolve")) {
        serverBuilder.useDnsServiceName(services[0].split(":")[0]);
      } else {
        serverBuilder.useLbAddresses(lbAddresses);
      }

      server = serverBuilder.build();
      System.out.println("Starting server...");
      server.start();
      server.blockUntilShutdown();

    } catch (ParseException e) {
      System.err.println(e.getLocalizedMessage());
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("ant", options);
    }
  }
}
