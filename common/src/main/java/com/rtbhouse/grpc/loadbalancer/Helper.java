package com.rtbhouse.grpc.loadbalancer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Helper {
  private static final Logger logger = LoggerFactory.getLogger(Helper.class);

  public static InetAddress getServerAddress(boolean natIsUsed) {
    try {
      // TODO: remove this test mode
      if (System.getenv().get("LOCAL") != null) return InetAddress.getByName("127.0.0.1");

      String address;
      if (natIsUsed) {
        URL whatIsMyIp = new URL("http://checkip.amazonaws.com");
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
