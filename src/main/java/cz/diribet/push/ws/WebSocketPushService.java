package cz.diribet.push.ws;

import static java.util.stream.Collectors.toList;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.wicket.Application;
import org.apache.wicket.Component;
import org.apache.wicket.Page;
import org.apache.wicket.ThreadContext;
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

	/**
	 * Max duration between installNode and establishing websocket connection (onConnect)
	 */
	private static final Duration MAX_CONNECTION_LAG = Duration.ofMinutes(10);

	private static final int PUBLISH_THREAD_POOL_MIN_SIZE = 0;
	private static final int PUBLISH_THREAD_POOL_MAX_SIZE = 40;
	private static final int PUBLISH_THREAD_POOL_QUEUE_CAPACITY = 10000;
	private static final int PUBLISH_THREAD_POOL_KEEP_ALIVE_SECONDS = 60;

	private final Map<WebSocketPushNode<?>, IWebSocketConnection> connectionsByNodes = new ConcurrentHashMap<>();
	private final Map<WebSocketPushNode<?>, Component> componentsByNodes = new ConcurrentHashMap<>();
	private final Map<WebSocketPushNode<?>, PushNodeInstallationState> nodeInstallationStates = new ConcurrentHashMap<>();

	private final ExecutorService publishExecutorService;
	private ScheduledExecutorService cleanupExecutorService;

	//*******************************************
	// Constructors
	//*******************************************

	public WebSocketPushService(Application application) {
		application.getRequestCycleListeners().add(new WebSocketRequestCycleListener(application));

		ThreadFactory threadFactory = new ThreadFactoryBuilder().setNameFormat("websocket-push-service-publish-%d").build();

		publishExecutorService = new ThreadPoolExecutor(PUBLISH_THREAD_POOL_MIN_SIZE,
														PUBLISH_THREAD_POOL_MAX_SIZE,
														PUBLISH_THREAD_POOL_KEEP_ALIVE_SECONDS,
														TimeUnit.SECONDS,
														new LinkedBlockingQueue<>(PUBLISH_THREAD_POOL_QUEUE_CAPACITY),
														threadFactory);

		setCleanupInterval(Duration.ofHours(1));
	}

	//*******************************************
	// Methods
	//*******************************************

	private void cleanUp() {
		LOG.debug("Starting WebSocket push service cleaning task...");

		int unconnectedNodeCount = 0;
		for (Entry<WebSocketPushNode<?>, PushNodeInstallationState> entry : nodeInstallationStates.entrySet()) {
			if (Thread.currentThread().isInterrupted()) {
				return;
			}

			WebSocketPushNode<?> node = entry.getKey();

			if (!isConnected(node)) {
				PushNodeInstallationState installationState = entry.getValue();
				if (installationState != null && !installationState.isTimedOut()) {
					// the node is not connected, but also it's not yet timed out
					// so give it a little time to live
					continue;
				}

				Component component = componentsByNodes.get(node);
				if (component != null) {

					uninstallNode(component, node);
					unconnectedNodeCount++;
				}
			}
		}
		LOG.debug("WebSocket push service cleaning task removed {} zombie nodes (nodes that were installed on server, but never connected with client).", unconnectedNodeCount);

		// nodes should be cleaned-up when disconnected - this is leak prevention when node clean-up fails on disconnect
		int disconnectedNodeCount = 0;
		for (WebSocketPushNode<?> node : connectionsByNodes.keySet()) {
			if (!isConnected(node)) {
				Component component = componentsByNodes.get(node);

				if (component != null) {
					uninstallNode(component, node);
				} else {
					onDisconnect(node);
				}
				disconnectedNodeCount++;
			}
		}
		if (disconnectedNodeCount > 0) {
			LOG.warn("WebSocket push service cleaning task removed {} disconnected nodes (nodes that are disconnected from client, but not removed from service by onDisconnect).", disconnectedNodeCount);
		}
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

		return get(application, WebSocketPushService::new);
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

			if (service.cleanupExecutorService != null) {
				service.cleanupExecutorService.shutdownNow();
			}

			service.publishExecutorService.shutdownNow();
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

		// When using ajax to replace or add a component, new components may be created.
		// If those components install a push node, we have to connect them manually
		// because no WebSocketPushBehavior#onConnect will be called - the webSocket connection is already established.
		autoConnect(component, node);

		return node;
	}

	private <EventType> void autoConnect(Component component, WebSocketPushNode<EventType> node) {
		Args.notNull(component, "component");
		Args.notNull(node, "node");

		Page page = component.getPage();

		if (page != null) {
			Application application = getApplication();
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

	private Application getApplication() {
		return INSTANCES.entrySet().stream()
				  				   .filter(e -> this == e.getValue())
				  				   .findFirst()
				  				   .get()
				  				   .getKey();
	}

	@Override
	public <EventType> void publish(IPushChannel<EventType> channel, EventType event) {
		Args.notNull(channel, "channel");

		Set<IPushNode<?>> nodes = nodesByChannels.get(channel);
		if (nodes == null) {
			throw new IllegalArgumentException("Unknown channel " + channel);
		}

		executePublishTask(() -> {
			// every node registered on the same behavior belongs to the same connetion,
			// so we need to be sure we publish to each connection only once
			Set<IWebSocketConnection> usedConnections = new HashSet<>();

			for (IPushNode<?> node: nodes) {
				IWebSocketConnection connection = connectionsByNodes.get(node);

				if (!usedConnections.contains(connection)) {
					WebSocketPushEventContext<EventType> context = new WebSocketPushEventContext<>(event, channel, this);
					if (publishToNode(node, Collections.singletonList(context))) {
						usedConnections.add(connection);
					}
				}
			}
		});
	}

	@Override
	public <EventType> void publish(IPushNode<EventType> node, EventType event) {
		Args.notNull(node, "node");

		WebSocketPushEventContext<EventType> context = new WebSocketPushEventContext<>(event, null, this);

		executePublishTask(() -> {
			publishToNode(node, Collections.singletonList(context));
		});
	}

	private void executePublishTask(Runnable task) {
		try {

			publishExecutorService.submit(task);

		} catch (RejectedExecutionException e) {
			if (!publishExecutorService.isShutdown()) {
				throw e;
			}
		}
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
				List<WebSocketPushEventContext<?>> events = nodeInstallationState.queuedEvents.stream().collect(toList());

				// publish all stored events
				publishToNode(node, events);
				nodeInstallationState.queuedEvents.removeAll(events);
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

		ThreadFactory threadFactory = new ThreadFactoryBuilder().setNameFormat("websocket-push-service-clean-%d").build();
		cleanupExecutorService = Executors.newSingleThreadScheduledExecutor(threadFactory);
		cleanupExecutorService.scheduleAtFixedRate(() -> {

			try {
				ThreadContext.setApplication(getApplication());

				cleanUp();

			} catch (Throwable t) {
				LOG.error("An error occurred while running cleanup task.", t);
			}

		}, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
	}

	//*******************************************
	// Inner classes
	//*******************************************

	private static final class PushNodeInstallationState {

		private final LocalDateTime installedAt = LocalDateTime.now();
		private final List<WebSocketPushEventContext<?>> queuedEvents = new LinkedList<>();

		boolean isTimedOut() {
			return Duration.between(installedAt, LocalDateTime.now()).compareTo(MAX_CONNECTION_LAG) > 0;
		}

	}

}
