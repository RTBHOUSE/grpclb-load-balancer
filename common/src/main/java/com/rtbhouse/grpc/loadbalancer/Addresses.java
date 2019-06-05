package com.rtbhouse.grpc.loadbalancer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Addresses {
  private static final Logger logger = LoggerFactory.getLogger(Addresses.class);

  // This service is used to obtain our public IP address, in case when we are behind NAT and have
  // only e.g. 192.168.*
  // address bind to our network interface.
  private static final String CHECK_IP_URL = "http://checkip.amazonaws.com";

  private Addresses() {}

  public static InetAddress getServerAddress(boolean natIsUsed) {
    try {
      // TODO: remove this test mode
      if (System.getenv().get("LOCAL") != null) return InetAddress.getByName("127.0.0.1");

      String address;
      if (natIsUsed) {
        URL whatIsMyIp = new URL(CHECK_IP_URL);
        BufferedReader in = new BufferedReader(new InputStreamReader(whatIsMyIp.openStream()));
        address = in.readLine();
        in.close();
      } else {
        address = InetAddress.getLocalHost().getHostAddress();
      }
      return InetAddress.getByName(address);
    } catch (IOException e) {
      logger.error("Could not get host address", e);
      throw new RuntimeException("Could not get host address", e);
    }
  }
}
