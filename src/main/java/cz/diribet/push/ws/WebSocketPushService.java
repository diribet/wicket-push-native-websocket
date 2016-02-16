package cz.diribet.push.ws;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.wicket.Application;
import org.apache.wicket.Component;
import org.apache.wicket.protocol.ws.api.IWebSocketConnection;
import org.apache.wicket.util.lang.Args;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wicketstuff.push.AbstractPushService;
import org.wicketstuff.push.IPushChannel;
import org.wicketstuff.push.IPushEventHandler;
import org.wicketstuff.push.IPushNode;
import org.wicketstuff.push.IPushNodeDisconnectedListener;
import org.wicketstuff.push.IPushService;

/**
 * Native WebSocket based implementation of {@link IPushService}.
 *
 * @author Honza Krakora
 *
 */
public class WebSocketPushService extends AbstractPushService {

	//*******************************************
	// Attributes
	//*******************************************

	private static final Logger LOG = LoggerFactory.getLogger(WebSocketPushService.class);

	private static final Map<Application, WebSocketPushService> INSTANCES = new ConcurrentHashMap<>();

	private final Map<WebSocketPushNode<?>, IWebSocketConnection> connectionsByNodes = new ConcurrentHashMap<>();

	//*******************************************
	// Constructors
	//*******************************************

	//*******************************************
	// Methods
	//*******************************************

	public static WebSocketPushService get() {
		return get(Application.get());
	}

	public static WebSocketPushService get(Application application) {
		Args.notNull(application, "application");

		return INSTANCES.computeIfAbsent(application, a -> new WebSocketPushService());
	}

	@Override
	public <EventType> IPushNode<EventType> installNode(Component component, IPushEventHandler<EventType> handler) {
		Args.notNull(component, "component");
		Args.notNull(handler, "handler");

		LOG.debug("Installing node on {}", component.getClass().getName());

		WebSocketPushBehavior behavior = findWebSocketBehavior(component);

		if (behavior == null) {
			behavior = new WebSocketPushBehavior();
			component.add(behavior);
		}

		return behavior.addNode(handler);
	}

	private WebSocketPushBehavior findWebSocketBehavior(Component component) {
		Args.notNull(component, "component");
		return component.getBehaviors(WebSocketPushBehavior.class).stream().findFirst().orElse(null);
	}

	@Override
	public boolean isConnected(IPushNode<?> node) {
		Args.notNull(node, "node");

		IWebSocketConnection webSocketConnection = connectionsByNodes.get(node);
		return webSocketConnection != null && webSocketConnection.isOpen();
	}

	@Override
	public <EventType> void publish(IPushChannel<EventType> channel, EventType event) {
		Args.notNull(channel, "channel");

		Set<IPushNode<?>> nodes = nodesByChannels.get(channel);
		if (nodes == null) {
			throw new IllegalArgumentException("Unknown channel " + channel);
		}

		// every node registered on the same behavior belongs to the same connetion,
		// so we need to be sure we publish to each connection only once
		Set<IWebSocketConnection> usedConnections = new HashSet<>();

		for (IPushNode<?> node: nodes) {
			IWebSocketConnection connection = connectionsByNodes.get(node);

			if (!usedConnections.contains(connection)) {
				boolean success = publishToNode(node, new WebSocketPushEventContext<>(event, channel, this));
				if (success) {
					usedConnections.add(connection);
				}
			}
		}
	}

	@Override
	public <EventType> void publish(IPushNode<EventType> node, EventType event) {
		Args.notNull(node, "node");

		WebSocketPushEventContext<EventType> context = new WebSocketPushEventContext<>(event, null, this);
		publishToNode(node, context);
	}

	private boolean publishToNode(IPushNode<?> node, WebSocketPushEventContext<?> context) {
		LOG.debug("Publishing an event {}", context.getEvent());

		if (node instanceof WebSocketPushNode) {
			if (isConnected(node)) {
				IWebSocketConnection webSocketConnection = connectionsByNodes.get(node);
				webSocketConnection.sendMessage(new WebSocketPushMessage<>(context));

				return true;
			}
		} else {
			LOG.warn("Unsupported node type {}", node);
		}

		return false;
	}

	@Override
	public void uninstallNode(Component component, IPushNode<?> node) {
		Args.notNull(component, "component");
		Args.notNull(node, "node");

		if (node instanceof WebSocketPushNode) {
			WebSocketPushBehavior behavior = findWebSocketBehavior(component);
			if (behavior == null) {
				return;
			}
			behavior.removeNode((WebSocketPushNode<?>) node);
		} else {
			LOG.warn("Unsupported node type {}", node);
		}
	}

	protected <EventType> void onConnect(WebSocketPushNode<EventType> node, IWebSocketConnection webSocketConnection) {
		connectionsByNodes.put(node, webSocketConnection);
	}

	protected <EventType> void onDisconnect(WebSocketPushNode<EventType> node) {
		disconnectFromAllChannels(node);
		connectionsByNodes.remove(node);

		for (IPushNodeDisconnectedListener listener : disconnectListeners) {
			try {
				listener.onDisconnect(node);
			} catch (RuntimeException ex) {
				LOG.error("Failed to notify " + listener, ex);
			}
		}
	}

}
