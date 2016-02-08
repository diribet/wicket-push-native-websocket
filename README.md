# wicket-push-websocket

Native WebSockets implementation of the Wicketstuff Push service.

### How to use it

We are not publish to any Maven repository, so you need to install it to your local Maven repository.
Then add dependency in your maven pom file as follows:

```xml
<dependency>
  	<groupId>cz.diribet</groupId>
  	<artifactId>wicket-push-websocket</artifactId>
  	<version>1.0.0</version>
</dependency>
```

and add a specific wicket-websocket implementation for your need e.g.

```xml
<dependency>
	<groupId>org.apache.wicket</groupId>
  	<artifactId>wicket-native-websocket-javax</artifactId>
  	<version>${wicket.version}</version>
</dependency>
```

You also need to set a correct filter in your web.xml e.g.

```xml
<filter-class>org.apache.wicket.protocol.ws.javax.JavaxWebSocketFilter</filter-class>
```

Take a look at the [Wicket documentation](https://ci.apache.org/projects/wicket/guide/7.x/guide/nativewebsockets.html) for more information about native WebSockets, supported browsers and servlet containers.

### API

Install a node to any component you need to push to. This should be done in its constructor or onInitialize method.

```java
IPushChannel<EventType> channel = ...
IPushEventHandler<EventType> handler = ...

IPushService pushService = WebSocketPushService.get();
IPushNode<EventType> pushNode = pushService.installNode(this, handler);
pushService.connectToChannel(pushNode, channel);
```

later, you can push a message to all nodes from a channel or to a specific node

```java
EventType message = ...

IPushService pushService = WebSocketPushService.get();
pushService.publish(channel, message);
```

#### Java 8 is required