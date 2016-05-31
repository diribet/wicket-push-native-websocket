package cz.diribet.push.ws;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.wicket.Application;
import org.apache.wicket.Component;
import org.apache.wicket.Page;
import org.apache.wicket.protocol.ws.WebSocketSettings;
import org.apache.wicket.protocol.ws.api.IWebSocketConnection;
import org.apache.wicket.protocol.ws.api.registry.IKey;
import org.apache.wicket.protocol.ws.api.registry.IWebSocketConnectionRegistry;
import org.apache.wicket.protocol.ws.api.registry.PageIdKey;
import org.apache.wicket.util.lang.Args;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wicketstuff.push.AbstractPushService;
import org.wicketstuff.push.IPushChannel;
import org.wicketstuff.push.IPushEventHandler;
import org.wicketstuff.push.IPushNode;
import org.wicketstuff.push.IPushNodeDisconnectedListener;
import org.wicketstuff.push.IPushService;

import com.google.common.util.concurrent.ThreadFactoryBuilder;


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
	private final Map<WebSocketPushNode<?>, Component> componentsByNodes = new ConcurrentHashMap<>();

	private final ConcurrentMap<WebSocketPushNode<?>, PushNodeInstallationState> nodeInstallationStates =
			new ConcurrentHashMap<WebSocketPushNode<?>, PushNodeInstallationState>();

	/**
	 * Max duration between installNode and establishing websocket connection (onConnect)
	 */
	private Duration maxConnectionLag = Duration.ofMinutes(10);

	private final ThreadFactory threadFactory;
	private final ExecutorService queuedEventsExecutorService;
	private ScheduledExecutorService cleanupExecutorService;

	//*******************************************
	// Constructors
	//*******************************************

	public WebSocketPushService() {
		threadFactory = new ThreadFactoryBuilder().setNameFormat("wicket-websocket-push-service-%d").build();
		queuedEventsExecutorService = Executors.newCachedThreadPool(threadFactory);

		setCleanupInterval(Duration.ofHours(1));
	}

	//*******************************************
	// Methods
	//*******************************************

	private void cleanUp() {
		LOG.debug("Starting cleaning task...");

		int counter = 0;
		connectionsByNodes.keySet().forEach(node -> {
			if (Thread.currentThread().isInterrupted()) {
				return;
			}
			if (!isConnected(node)) {

				Component component = componentsByNodes.get(node);
				if (component != null) {
					uninstallNode(component, node);
				}
			}
		});

		nodeInstallationStates.values().removeIf(state -> state.isTimedOut());

		LOG.debug("Cleaning task finished with {} zombie nodes removed.", counter);
	}

	/**
	 * Returns push service for current application.
	 *
	 * @return push service or {@code null}
	 */
	public static WebSocketPushService get() {
		return get(Application.get());
	}

	/**
	 * Returns push service for provided application.
	 *
	 * @param application
	 *            an application, must not be {@code null}
	 * @return push service or {@code null}
	 */
	public static WebSocketPushService get(Application application) {
		Args.notNull(application, "application");

		return get(application, a -> new WebSocketPushService());
	}

	/**
	 * Returns push service for provided application or create one.
	 *
	 * @param application
	 *            an application, must not be {@code null}
	 * @param mappingFunction
	 *            mapping function for creating push service if none for the
	 *            provided application exists, must not be {@code null}
	 * @return push service or {@code null}
	 */
	public static WebSocketPushService get(Application application, Function<Application, WebSocketPushService> mappingFunction) {
		Args.notNull(application, "application");
		Args.notNull(mappingFunction, "function");

		return INSTANCES.computeIfAbsent(application, mappingFunction);
	}

	static void onApplicationShutdown(Application application) {
		Args.notNull(application, "application");

		WebSocketPushService service = INSTANCES.remove(application);
		if (service != null) {

			LOG.info("Shutting down {}...", service);
			service.cleanupExecutorService.shutdownNow();
		}
	}

	@Override
	public <EventType> IPushNode<EventType> installNode(Component component, IPushEventHandler<EventType> handler) {
		Args.notNull(component, "component");
		Args.notNull(handler, "handler");

		WebSocketPushBehavior behavior = findWebSocketBehavior(component);

		if (behavior == null) {
			behavior = createWebSocketPushBehavior();
			Args.notNull(behavior, "behavior");

			component.add(behavior);
		}

		WebSocketPushNode<EventType> node = behavior.addNode(handler);
		nodeInstallationStates.put(node, new PushNodeInstallationState());
		componentsByNodes.put(node, component);

		// When using ajax to replace a component containing an abstract repeater,
		// new items may be created for every render.
		// If those items install a push node, we have to connect them manually
		// because no WebSocketPushBehavior#onConnect will be called - the webSocket connection is already established.
		autoConnect(component, node);

		return node;
	}

	private <EventType> void autoConnect(Component component, WebSocketPushNode<EventType> node) {
		Args.notNull(component, "component");
		Args.notNull(node, "node");

		Page page = component.getPage();

		if (page != null) {
			Application application = INSTANCES.entrySet().stream()
														  .filter(e -> this == e.getValue())
														  .findFirst()
														  .get()
														  .getKey();
			String sessionId = page.getSession().getId();
			IKey key = new PageIdKey(page.getPageId());

			WebSocketSettings webSocketSettings = WebSocketSettings.Holder.get(application);
			IWebSocketConnectionRegistry webSocketConnectionRegistry = webSocketSettings.getConnectionRegistry();
			IWebSocketConnection webSocketConnection = webSocketConnectionRegistry.getConnection(application, sessionId, key);

			if (webSocketConnection != null) {
				onConnect(node, webSocketConnection);
			}
		}
	}

	/**
	 * Creates a new instance of {@link WebSocketPushBehavior}
	 *
	 * @return new instance of {@link WebSocketPushBehavior}, never returns
	 *         {@code null}
	 */
	protected WebSocketPushBehavior createWebSocketPushBehavior() {
		return new WebSocketPushBehavior();
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
				WebSocketPushEventContext<EventType> context = new WebSocketPushEventContext<>(event, channel, this);
				boolean success = publishToNode(node, Collections.singletonList(context));
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
		publishToNode(node, Collections.singletonList(context));
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private boolean publishToNode(IPushNode<?> node, List<WebSocketPushEventContext<?>> contexts) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("Publishing events {} to node {}.", eventsToString(contexts), node);
		}

		if (node instanceof WebSocketPushNode) {
			if (isConnected(node)) {

				// node is connected - so there should be an opened connection
				IWebSocketConnection webSocketConnection = connectionsByNodes.get(node);

				try {
					webSocketConnection.sendMessage(new WebSocketPushMessage(contexts));
				} catch (Throwable t) {
					LOG.error("An error occured when sending a WebSocket message", t);
				}

				// return true regardless of an exception was thrown or not
				// because it just means "the node was served"
				return true;

			} else {
				synchronized (nodeInstallationStates) {
					PushNodeInstallationState nodeInstallationState = nodeInstallationStates.get(node);

					if (nodeInstallationState != null) {
						// websocket connection was not yet established
						for (WebSocketPushEventContext<?> context : contexts) {
							nodeInstallationState.queuedEvents.add(context);
						}
					} else {

						if (isConnected(node)) {
							// websocket connection was established while waiting on locked nodeInstallationStates
							publishToNode(node, contexts);
						} else {
							// otherwise we are publishing to a disconnected node
							LOG.debug("Events were not published to a node {} because the node is disconnected. Events: {} ", node, eventsToString(contexts));
						}
					}
				}
			}
		} else {
			LOG.warn("Unsupported node type {}", node);
		}

		return false;
	}

	private String eventsToString(List<WebSocketPushEventContext<?>> contexts) {
		return contexts
					.stream()
					.map(context -> context.getEvent().toString())
					.collect(Collectors.joining(", "));
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
		LOG.debug("Associating a webSocket connection with node {}", node);

		connectionsByNodes.put(node, webSocketConnection);

		synchronized (nodeInstallationStates) {
			PushNodeInstallationState nodeInstallationState = nodeInstallationStates.remove(node);

			if (nodeInstallationState != null && !nodeInstallationState.queuedEvents.isEmpty()) {
				queuedEventsExecutorService.submit(() -> {
					publishToNode(node, nodeInstallationState.queuedEvents);
				});
			}
		}
	}

	protected <EventType> void onDisconnect(WebSocketPushNode<EventType> node) {
		LOG.debug("Disconnecting node {}", node);

		disconnectFromAllChannels(node);
		connectionsByNodes.remove(node);
		componentsByNodes.remove(node);
		nodeInstallationStates.remove(node);

		for (IPushNodeDisconnectedListener listener : disconnectListeners) {
			try {
				listener.onDisconnect(node);
			} catch (RuntimeException ex) {
				LOG.error("Failed to notify " + listener, ex);
			}
		}
	}

	/**
	 * Sets the interval in which the clean up task will be executed that
	 * removes information about disconnected push nodes. Default is one hour.
	 *
	 * @param interval
	 *            clean up interval, can't bew {@code null}
	 */
	public void setCleanupInterval(Duration interval) {
		Args.notNull(interval, "interval");

		if (cleanupExecutorService != null) {
			cleanupExecutorService.shutdownNow();
		}

		cleanupExecutorService = Executors.newSingleThreadScheduledExecutor(threadFactory);
		cleanupExecutorService.scheduleAtFixedRate(this::cleanUp, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
	}

	//*******************************************
	// Inner classes
	//*******************************************

	private final class PushNodeInstallationState {
		private LocalDateTime installedAt = LocalDateTime.now();
		private List<WebSocketPushEventContext<?>> queuedEvents = new LinkedList<WebSocketPushEventContext<?>>();

		PushNodeInstallationState() {
		}

		boolean isTimedOut() {
			return Duration.between(installedAt, LocalDateTime.now()).compareTo(maxConnectionLag) > 0;
		}
	}

}
