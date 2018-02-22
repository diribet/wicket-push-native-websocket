package cz.diribet.push.ws;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.wicket.Application;
import org.apache.wicket.Component;
import org.apache.wicket.protocol.ws.WebSocketSettings;
import org.apache.wicket.protocol.ws.api.IWebSocketConnection;
import org.apache.wicket.protocol.ws.api.WebSocketBehavior;
import org.apache.wicket.protocol.ws.api.WebSocketRequestHandler;
import org.apache.wicket.protocol.ws.api.message.AbortedMessage;
import org.apache.wicket.protocol.ws.api.message.AbstractClientMessage;
import org.apache.wicket.protocol.ws.api.message.ClosedMessage;
import org.apache.wicket.protocol.ws.api.message.ConnectedMessage;
import org.apache.wicket.protocol.ws.api.message.IWebSocketPushMessage;
import org.apache.wicket.protocol.ws.api.registry.IKey;
import org.apache.wicket.protocol.ws.api.registry.IWebSocketConnectionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wicketstuff.push.IPushEventHandler;

import com.google.common.reflect.TypeToken;

/**
 * {@link WebSocketBehavior} implementation for use in the
 * {@link WebSocketPushService}.
 *
 * @author Honza Krakora
 *
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
public class WebSocketPushBehavior extends WebSocketBehavior {

	//*******************************************
	// Attributes
	//*******************************************

	private static final Logger LOG = LoggerFactory.getLogger(WebSocketPushBehavior.class);

	private final Map<WebSocketPushNode, IPushEventHandler> handlers = new ConcurrentHashMap<>();
	private Component component;

	//*******************************************
	// Constructors
	//*******************************************

	//*******************************************
	// Methods
	//*******************************************

	<EventType> WebSocketPushNode<EventType> addNode(IPushEventHandler<EventType> pushEventHandler) {
		requireNonNull(pushEventHandler, "pushEventHandler");

		LOG.debug("Installing node on {}", component.getClass().getName());

		WebSocketPushNode<EventType> node = new WebSocketPushNode<>();
		handlers.put(node, pushEventHandler);

		return node;
	}

	<EventType> void removeNode(WebSocketPushNode<EventType> node) {
		requireNonNull(node, "node");

		LOG.debug("Removing node from {}", component.getClass().getName());

		handlers.remove(node);

		WebSocketPushService pushService = WebSocketPushService.get(component.getApplication());
		pushService.onDisconnect(node);
	}

	@Override
	public void bind(Component component) {
		super.bind(component);

		if (this.component != null) {
			StringBuilder messageBuilder = new StringBuilder();

			messageBuilder.append("This kind of handler cannot be attached to multiple components.");
			messageBuilder.append("It is already attached to component ");
			messageBuilder.append(this.component);
			messageBuilder.append(", but component ");
			messageBuilder.append(component);
			messageBuilder.append(" wants to be attached too");

			throw new IllegalStateException(messageBuilder.toString());
		}

		this.component = component;
	}

	@Override
	public void unbind(Component component) {
		LOG.debug("Unbinding behavior from {}", component);

		removeAllNodes();
		this.component = null;

		super.unbind(component);
	}

	@Override
	public void onRemove(Component component) {
		LOG.debug("Removing behavior from {}", component);

		removeAllNodes();
		this.component = null;

		super.onRemove(component);
	}

	@Override
	protected void onPush(WebSocketRequestHandler handler, IWebSocketPushMessage message) {
		super.onPush(handler, message);

		if (message instanceof WebSocketPushMessage) {
			WebSocketPushMessage pushMessage = (WebSocketPushMessage) message;
			List<WebSocketPushEventContext> contexts = pushMessage.getContexts();
			WebSocketAjaxRequestTargetAdapter ajaxRequestTarget = new WebSocketAjaxRequestTargetAdapter(handler);

			handlers.forEach((node, eventHandler) -> {
				for (WebSocketPushEventContext context : contexts) {
					Class<?> eventClass = context.getEvent().getClass();

					// do some reflection voodoo to check if the handler supports this message type
					TypeToken<? extends IPushEventHandler> handlerTypeToken = TypeToken.of(eventHandler.getClass());
					TypeToken<?> handlerEventTypeToken = handlerTypeToken.resolveType(IPushEventHandler.class.getTypeParameters()[0]);

					if (!handlerEventTypeToken.isSupertypeOf(eventClass)) {
						String logMessage = "Push skipped, reason: context message type {} is not compatible with EventHandler mesage type {}";
						LOG.debug(logMessage, eventClass.getName(), handlerEventTypeToken.getRawType().getName());
						return;
					}

					try {
						eventHandler.onEvent(ajaxRequestTarget, context.getEvent(), node, context);
					} catch (RuntimeException e) {
						LOG.error("Failed while processing event", e);
					}
				}
			});
		}
	}

	@Override
	protected void onConnect(ConnectedMessage message) {
		super.onConnect(message);

		LOG.debug("Connection on {} opened: {}", component.getClass().getName(), message);

		Application application = message.getApplication();
		IWebSocketConnection webSocketConnection = getConnection(message);

		WebSocketPushService pushService = WebSocketPushService.get(application);
		handlers.keySet().forEach(node -> pushService.onConnect(node, webSocketConnection));
	}

	@Override
	protected void onAbort(AbortedMessage message) {
		LOG.debug("Connection on {} aborted: {}", component.getClass().getName(), message);
		removeAllNodes();

		super.onAbort(message);
	}

	@Override
	protected void onClose(ClosedMessage message) {
		LOG.debug("Connection on {} closed: {}", component.getClass().getName(), message);
		removeAllNodes();

		super.onClose(message);
	}

	private void removeAllNodes() {
		handlers.keySet().stream().collect(toList()).forEach(this::removeNode);
	}

	private IWebSocketConnection getConnection(AbstractClientMessage message) {
		requireNonNull(message, "message");

		Application application = message.getApplication();
		String sessionId = message.getSessionId();
		IKey key = message.getKey();

		WebSocketSettings webSocketSettings = WebSocketSettings.Holder.get(application);
		IWebSocketConnectionRegistry webSocketConnectionRegistry = webSocketSettings.getConnectionRegistry();
		return webSocketConnectionRegistry.getConnection(application, sessionId, key);
	}

}
