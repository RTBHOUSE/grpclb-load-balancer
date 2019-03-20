package com.rtbhouse.grpc.loadbalancer;

public interface UpdatableServerList {
  /**
   * Adds the server to the servers list. If the server is already on the list, then refreshes the
   * last dispatch time from the specified server.
   */
  void addOrRefreshServer(BackendServer server);

  /**
   * Removes the specified server from the servers list.
   *
   * @return true if server was on the list, and false otherwise
   */
  boolean dropServer(BackendServer server);
}
