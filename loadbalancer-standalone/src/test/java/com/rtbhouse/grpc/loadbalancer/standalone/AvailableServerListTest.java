package com.rtbhouse.grpc.loadbalancer.standalone;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.google.common.net.InetAddresses;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/** Evaluate whether number of server list updates called on mocked lbService was as expected. */
@RunWith(MockitoJUnitRunner.class)
public class AvailableServerListTest {
  private final Random random = new Random();
  /* Make it small, as no internet connection involved. */
  private final int timeBetweenUpToDateChecks = 100;
  private final int timeToEvict = 120;

  @Mock private LoadBalanceService lbService;
  private AvailableServerList availableServerList;
  /* Count number of update calls per service. */
  private Map<String, Integer> servicesToUpdates = new HashMap<>();

  @Before
  public void beforeEachTest() {
    servicesToUpdates.clear();
    availableServerList =
        new AvailableServerList(lbService, timeBetweenUpToDateChecks, timeToEvict);
  }

  @Test
  public void shouldNoticeAdd() {
    List<String> services = Arrays.asList("service1", "service2");
    AvailableServerList.BackendServer bs = generateRandomBackendServer(services);

    availableServerList.addOrRefreshServer(bs);
    verifyUpdateReceived(bs);
  }

  @Test
  public void shouldNoticeDelete() {
    List<String> services = Arrays.asList("service1", "service2");
    AvailableServerList.BackendServer bs = generateRandomBackendServer(services);

    availableServerList.addOrRefreshServer(bs);
    verifyUpdateReceived(bs);

    availableServerList.dropServer(bs);
    verifyUpdateReceived(bs);
  }

  @Test
  public void shouldDeleteWhenNoHeartbeat() throws InterruptedException {
    List<String> services = Arrays.asList("service1", "service2");
    AvailableServerList.BackendServer bs = generateRandomBackendServer(services);

    availableServerList.addOrRefreshServer(bs);
    verifyUpdateReceived(bs);

    /* Give some more time, to be sure.*/
    Thread.sleep(2 * timeToEvict);
    verifyUpdateReceived(bs);
  }

  @Test
  public void shouldRemainOnList() throws InterruptedException {
    List<String> services = Arrays.asList("service1", "service2");
    AvailableServerList.BackendServer bs = generateRandomBackendServer(services);

    availableServerList.addOrRefreshServer(bs);
    verifyUpdateReceived(bs);

    /* Simulate backend server sending heartbeats. */
    for (int i = 0; i < 3; ++i) {
      Thread.sleep(timeBetweenUpToDateChecks);
      availableServerList.addOrRefreshServer(bs);
    }

    verifyNoUpdateReceived(bs);
  }

  private void verifyUpdateReceived(AvailableServerList.BackendServer bs) {
    for (String service : bs.getServices()) {
      servicesToUpdates.putIfAbsent(service, 0);
      int expectedUpdatesCalls = servicesToUpdates.get(service) + 1;
      verify(lbService, times(expectedUpdatesCalls)).update(eq(service), any());
      servicesToUpdates.put(service, expectedUpdatesCalls);
    }
  }

  private void verifyNoUpdateReceived(AvailableServerList.BackendServer bs) {
    for (String service : bs.getServices()) {
      servicesToUpdates.putIfAbsent(service, 0);
      int expectedUpdatesCalls = servicesToUpdates.get(service);
      verify(lbService, times(expectedUpdatesCalls)).update(eq(service), any());
    }
  }

  private AvailableServerList.BackendServer generateRandomBackendServer(List<String> services) {
    return new AvailableServerList.BackendServer(generateRandomAddress(), services);
  }

  private InetSocketAddress generateRandomAddress() {
    InetAddress inetAddress = InetAddresses.fromInteger(random.nextInt());
    int port = random.nextInt(65535 - 1024 + 1) + 1025;
    return new InetSocketAddress(inetAddress, port);
  }
}
