package com.rtbhouse.grpc.loadbalancer;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public class LoadBalancerConnector {
  private final List<SingleLoadBalancerConnector> lbConnectors;
  private final InetAddress serverAddress;
  private final int port;
  private final int serverWeight;
  private final String[] services;
  private ConnectorStatus status;

  public LoadBalancerConnector(
      int port,
      InetAddress serverAddress,
      String[] lbAddresses,
      String[] services,
      int serverWeight) {
    this.port = port;
    this.serverAddress = serverAddress;
    this.services = services;
    this.serverWeight = serverWeight;
    this.lbConnectors = getLbConnectorsList(lbAddresses);
    this.status = ConnectorStatus.STOPPED;
  }

  private List<SingleLoadBalancerConnector> getLbConnectorsList(String[] lbAddresses) {
    List<SingleLoadBalancerConnector> list = new ArrayList();
    for (String address : lbAddresses) {
      SingleLoadBalancerConnector connector =
          new SingleLoadBalancerConnector(port, serverAddress, address, services, serverWeight);
      list.add(connector);
    }
    return list;
  }

  public void start() {
    if (status != ConnectorStatus.STOPPED) {
      throw new IllegalStateException();
    }
    for (SingleLoadBalancerConnector lbConnector : lbConnectors) {
      lbConnector.start();
    }
    status = ConnectorStatus.WORKING;
  }

  public void stop() {
    if (status == ConnectorStatus.STOPPED) {
      throw new IllegalStateException();
    }
    for (SingleLoadBalancerConnector lbConnector : lbConnectors) {
      lbConnector.stop();
    }
    status = ConnectorStatus.STOPPED;
  }

  public void resume() {
    if (status != ConnectorStatus.PAUSED) {
      throw new IllegalStateException();
    }
    for (SingleLoadBalancerConnector lbConnector : lbConnectors) {
      lbConnector.resume();
    }
    status = ConnectorStatus.WORKING;
  }

  public void pause() {
    if (status != ConnectorStatus.WORKING) {
      throw new IllegalStateException();
    }
    for (SingleLoadBalancerConnector lbConnector : lbConnectors) {
      lbConnector.pause();
    }
    status = ConnectorStatus.PAUSED;
  }

  private enum ConnectorStatus {
    STOPPED,
    WORKING,
    PAUSED;
  }
}
