# gRPC-LB load balancer
Server-side gRPC-LB implementation, LBConnector for backend servers

### Artifacts' quick description:

| Name | Description |
| ---- | ----------- |
| loadbalancer-standalone | Loadbalancer implementation, which requires backend servers to register through `SignupService` (defined in `common` package). After registration, server has to start sending heartbeats, in order to stay on the list of active servers. This list is kept in memory. |
| basic-loadbalancer-aware-server | Contains `LBConnector` class, which allows server to register in LB through `SignupService`. You may use it in any gRPC server, by invoking `start()` and `stop()` on it. If you have healthchecks in your application, you may also use `pause()` and `resume()` methods, to be temporarily removed from LB server list. In addition, we have `BasicLoadbalancerAwareServer` class here, which is a simple wrapper for gRPC server and LBConnector at the same time. You can inherit it and have communication with LB done automatically.
| loadbalancer-zookeeper | Simple load balancer implementation, which reads server list from ZooKeeper cluster and serves it through gRPC-LB protocol. |
| basic-zookeeper-aware-server | Wrapper for gRPC server, that you can inherit and have a server which registers (creates an ephemeral node with its name) itself in ZooKeeper automatically. |
| common | Contains classes and .proto files common for the whole project. |
| common-lb | Contains classes common for `loadbalacer-standalone` and `loadbalancer-zookeeper`. | 
| examples | Example servers and client for simple testing. |

#### Healthchecks
Both `basic-loadbalancer-aware-server` and `basic-zookeeper-aware-server` can use your custom healthcheck, provided as [Health service](https://github.com/grpc/grpc/blob/master/doc/health-checking.md) implementation. Server will call `Check` every 5s (can be configured) and inform LB about status changes (by `pause()` and `resume()` in `LBConnector` or by creating/removing node in ZooKeeper).

#### Building
There is a parent-project in root directory, so you can simply build the whole project by `mvn install` there.

#### Usage examples

TBD soon.
