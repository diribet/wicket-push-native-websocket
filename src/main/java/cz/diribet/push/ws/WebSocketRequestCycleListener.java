package cz.diribet.push.ws;

import static org.apache.wicket.request.RequestHandlerExecutor.ReplaceHandlerException;

import org.apache.wicket.Application;
import org.apache.wicket.Session;
import org.apache.wicket.core.request.handler.IPageProvider;
import org.apache.wicket.core.request.handler.PageProvider;
import org.apache.wicket.core.request.handler.RenderPageRequestHandler;
import org.apache.wicket.protocol.ws.WebSocketSettings;
import org.apache.wicket.protocol.ws.api.IWebSocketConnection;
import org.apache.wicket.protocol.ws.api.registry.IWebSocketConnectionRegistry;
import org.apache.wicket.protocol.ws.api.registry.PageIdKey;
import org.apache.wicket.request.IRequestHandler;
import org.apache.wicket.request.cycle.IRequestCycleListener;
import org.apache.wicket.request.cycle.RequestCycle;

/**
 * Handles situations when the same page is opened in multiple tabs.
 * It will redirect new tab to a new page instance.
 *
 * @author vlasta
 *
 */
public class WebSocketRequestCycleListener implements IRequestCycleListener {

	private final Application application;

	public WebSocketRequestCycleListener(Application application) {
		this.application = application;
	}

	@Override
	public void onRequestHandlerResolved(RequestCycle cycle, IRequestHandler handler) {
		if (handler instanceof RenderPageRequestHandler) {
			RenderPageRequestHandler renderPageHandler = (RenderPageRequestHandler) handler;

			IPageProvider pageProvider = renderPageHandler.getPageProvider();
			if (pageProvider.hasPageInstance()) {
				// existing page instance is rendered - check if it has WebSocket connection
				String sessionId = Session.get().getId();
				Integer pageId = renderPageHandler.getPageId();
				PageIdKey key = new PageIdKey(pageId);

				WebSocketSettings webSocketSettings = WebSocketSettings.Holder.get(application);
				IWebSocketConnectionRegistry connectionRegistry = webSocketSettings.getConnectionRegistry();
				IWebSocketConnection connection = connectionRegistry.getConnection(application, sessionId, key);

				if (connection != null && connection.isOpen()) {
					// page has open connection - probably is opened in second tab
					// sometimes the page refresh (F5) can trigger this code, because request is handled faster than websocket is closed
					IPageProvider newPageProvider = new PageProvider(pageProvider.getPageClass(), pageProvider.getPageParameters());
					RenderPageRequestHandler newHandler = new RenderPageRequestHandler(newPageProvider);
					throw new ReplaceHandlerException(newHandler, true);
				}
			}
		}
	}

}
