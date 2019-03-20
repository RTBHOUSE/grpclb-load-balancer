package com.rtbhouse.grpc.loadbalancer.zookeeper;

import com.google.common.net.HostAndPort;
import com.google.protobuf.ByteString;
import com.rtbhouse.grpc.loadbalancer.LoadBalanceService;
import io.grpc.lb.v1.Server;
import io.grpc.lb.v1.ServerList;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.utils.ZKPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ZKServerListFetcher keeps the list of available backend servers. The list is build based on the
 * node data retrieved from ZooKeeper. As the ServerList object is immutable it has to be rebuild
 * once any of the backend server was removed/new server was added. In order to optimize this
 * process the list is only rebuild when there is a request for the current list, and the list is
 * marked as outdated.
 */
public class ZKServerListFetcher {
  public static String MOCK_SERVICE =
      "zookeeper"; // TODO: add load balancing based on services aggregation.
  private static final Logger logger = LoggerFactory.getLogger(ZKServerListFetcher.class);
  private CuratorFramework zkClient;
  private PathChildrenCache zkBackendServerListCache;
  private ServerList readyToReturnServersList;
  private Set<InetSocketAddress> servers = ConcurrentHashMap.newKeySet();
  private LoadBalanceService lbService;

  public ZKServerListFetcher(
      CuratorFramework zkClient, String zkSrvsListPath, LoadBalanceService lbService) {
    this.lbService = lbService;
    this.zkClient = zkClient;
    zkBackendServerListCache = new PathChildrenCache(zkClient, zkSrvsListPath, true);

    setZKConnectionStateListener();
    setZKBackendServerListCacheListener();
    try {
      /* Normal mode enforces event on every node when building initial cache. */
      zkBackendServerListCache.start(PathChildrenCache.StartMode.NORMAL);
    } catch (Exception ex) {
      logger.error(ex.getMessage(), ex);
    }
  }

  public void stop() {
    try {
      zkBackendServerListCache.close();
    } catch (IOException ex) {
      logger.error(ex.getMessage(), ex);
    }
  }

  /* Tracks the connection state with ZooKeeper.*/
  private void setZKConnectionStateListener() {
    ConnectionStateListener zkConnectionStateListener =
        (client, newState) -> logger.debug("ZooKeeper connection: {}", newState.toString());
    zkClient.getConnectionStateListenable().addListener(zkConnectionStateListener);
  }

  private void setZKBackendServerListCacheListener() {
    PathChildrenCacheListener zkPathListener =
        (client, event) -> {
          String nodeName = ZKPaths.getNodeFromPath(event.getData().getPath());
          try {
            InetSocketAddress srvAddr = parseSrvAddress(nodeName);
            switch (event.getType()) {
              case CHILD_ADDED:
                servers.add(srvAddr);
                logger.info("Added new backend server {}", nodeName);
                break;

              case CHILD_REMOVED:
                boolean dbg_succeed = servers.remove(srvAddr);
                /* DEBUG: server should be on the list. */
                assert (dbg_succeed);
                /* END_DEBUG */
                logger.info("Removed backend server: {}", nodeName);
                break;

              default:
                return;
            }

            rebuildServerList();
            lbService.update(MOCK_SERVICE, readyToReturnServersList);

          } catch (IllegalArgumentException ex) {
            logger.error(ex.getMessage(), ex);
          }
        };
    zkBackendServerListCache.getListenable().addListener(zkPathListener);
  }

  private void rebuildServerList() {
    ServerList.Builder builder = ServerList.newBuilder();
    for (InetSocketAddress backendSrvAddr : servers) {
      Server backendSrv =
          Server.newBuilder()
              .setIpAddress(ByteString.copyFrom(backendSrvAddr.getAddress().getAddress()))
              .setPort(backendSrvAddr.getPort())
              .build();
      builder.addServers(backendSrv);
    }
    readyToReturnServersList = builder.build();
  }

  /* Builds new InetSocketAddress object based on passed host:port string. */
  private InetSocketAddress parseSrvAddress(String hostPortStr) throws IllegalArgumentException {
    HostAndPort hp = HostAndPort.fromString(hostPortStr);
    int port = hp.hasPort() ? hp.getPort() : 80;
    return new InetSocketAddress(hp.getHost(), port);
  }
}
