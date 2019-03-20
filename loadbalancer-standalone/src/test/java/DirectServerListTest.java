/** WARNING: Commented due to radical changes in load balancing policy. */
// package  com.rtbhouse.grpc.loadbalancer.standalone;
// import static org.junit.Assert.assertEquals;
// import static org.mockito.ArgumentMatchers.any;
// import static org.mockito.Mockito.times;
// import static org.mockito.Mockito.verify;
//
// import com.google.common.net.InetAddresses;
// import com.google.protobuf.ByteString;
// import com.rtbhouse.grpc.loadbalancer.BackendServer;
// import com.rtbhouse.grpc.loadbalancer.LoadBalanceService;
// import com.rtbhouse.grpc.loadbalancer.standalone.DirectServerList;
// import io.grpc.lb.v1.Server;
// import io.grpc.lb.v1.ServerList;
// import java.net.InetAddress;
// import java.net.InetSocketAddress;
// import java.util.*;
// import org.junit.Before;
// import org.junit.Test;
// import org.junit.runner.RunWith;
// import org.mockito.ArgumentCaptor;
// import org.mockito.Mock;
// import org.mockito.junit.MockitoJUnitRunner;
//
// @RunWith(MockitoJUnitRunner.class)
// public class DirectServerListTest {
//  private static final int MASSIVE_TEST_ACTIONS = 50;
//  private static final int ADD_BACKEND_SERVER = 0;
//  private static final int REMOVE_BACKEND_SERVER = 1;
//  private final Random random = new Random();
//  @Mock private LoadBalanceService lbService;
//  private DirectServerList serverListFetcher;
//  private Set<InetSocketAddress> backendServerAddresses;
//  private int expectedUpdatesCalls;
//  private ArgumentCaptor<ServerList> updateCaptor;
//  private List<String> services = new ArrayList<>();
//
//  @Before
//  public void beforeEachTest() {
//    services.add("rtbhouse.services.com");
//
//    serverListFetcher = new DirectServerList(lbService);
//    backendServerAddresses = new HashSet<>();
//    expectedUpdatesCalls = 0;
//    updateCaptor = ArgumentCaptor.forClass(ServerList.class);
//  }
//
//  @Test
//  public void shouldGracefullyShutdown() {}
//
//  @Test
//  public void shouldNoticeAdd() {
//    addBackendServer();
//    assertEquals(updateCaptor.getValue(), getExpectedServerList());
//  }
//
//  @Test
//  public void shouldNoticeDelete() {
//    addBackendServer();
//    deleteBackendServer();
//    assertEquals(updateCaptor.getValue(), getExpectedServerList());
//  }
//
//  @Test
//  public void massiveTest() {
//    for (int i = 0; i < MASSIVE_TEST_ACTIONS; ++i) {
//      int action = random.nextInt(2);
//      switch (action) {
//        case ADD_BACKEND_SERVER:
//          addBackendServer();
//          break;
//
//        case REMOVE_BACKEND_SERVER:
//          deleteBackendServer();
//          break;
//      }
//    }
//
//    assert (equalContent(updateCaptor.getValue(), getExpectedServerList()));
//  }
//
//  private boolean equalContent(ServerList a, ServerList b) {
//    return a.getServersList().containsAll(b.getServersList())
//        && b.getServersList().containsAll(a.getServersList());
//  }
//
//  private void deleteBackendServer() {
//    if (backendServerAddresses.isEmpty()) {
//      return;
//    }
//    InetSocketAddress toDeleteBackendServerAddress = getRandomBackendAddress();
//    backendServerAddresses.remove(toDeleteBackendServerAddress);
//
//    serverListFetcher.dropServer(services, toDeleteBackendServerAddress);
//    verify(lbService, times(++expectedUpdatesCalls)).update(any(), updateCaptor.capture());
//  }
//
//  private void addBackendServer() {
//    InetSocketAddress newBackendServerAddress;
//    do {
//      newBackendServerAddress = generateRandomAddress();
//    } while (backendServerAddresses.contains(newBackendServerAddress));
//    backendServerAddresses.add(newBackendServerAddress);
//
//    serverListFetcher.addOrRefreshServer(services, newBackendServerAddress);
//    verify(lbService, times(++expectedUpdatesCalls)).update(any(), updateCaptor.capture());
//  }
//
//  private InetSocketAddress getRandomBackendAddress() {
//    int index = random.nextInt(backendServerAddresses.size());
//    Iterator<InetSocketAddress> iter = backendServerAddresses.iterator();
//    for (int i = 0; i < index; i++) {
//      iter.next();
//    }
//    return iter.next();
//  }
//
//  private BackendServer generateRandomBackendServer() {
//
//  }
//
//  private InetSocketAddress generateRandomAddress() {
//    InetAddress inetAddress = InetAddresses.fromInteger(random.nextInt());
//    int port = random.nextInt(65535 - 1024 + 1) + 1025;
//    return new InetSocketAddress(inetAddress, port);
//  }
//
//  private ServerList getExpectedServerList() {
//    return buildServerList(backendServerAddresses);
//  }
//
//  private Server buildServer(InetSocketAddress serverAddress) {
//    return Server.newBuilder()
//        .setIpAddress(ByteString.copyFrom(serverAddress.getAddress().getAddress()))
//        .setPort(serverAddress.getPort())
//        .build();
//  }
//
//  private ServerList buildServerList(Collection<InetSocketAddress> servers) {
//    ServerList.Builder builder = ServerList.newBuilder();
//    for (InetSocketAddress server : servers) {
//      builder.addServers(buildServer(server));
//    }
//    return builder.build();
//  }
// }
