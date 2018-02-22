package cz.diribet.push.ws;

import static java.util.Objects.requireNonNull;

import java.util.Collection;

import org.apache.wicket.Component;
import org.apache.wicket.MarkupContainer;
import org.apache.wicket.Page;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.protocol.ws.api.WebSocketRequestHandler;
import org.apache.wicket.request.ILogData;
import org.apache.wicket.request.IRequestCycle;
import org.apache.wicket.request.component.IRequestablePage;
import org.apache.wicket.request.mapper.parameter.PageParameters;

/**
 * An {@link AjaxRequestTarget} adapter for {@link WebSocketRequestHandler}.
 *
 * @author Honza Krakora
 *
 */
class WebSocketAjaxRequestTargetAdapter implements AjaxRequestTarget {

	//*******************************************
	// Attributes
	//*******************************************

	private final WebSocketRequestHandler handler;

	//*******************************************
	// Constructors
	//*******************************************

	WebSocketAjaxRequestTargetAdapter(WebSocketRequestHandler handler) {
		requireNonNull(handler, "handler");
		this.handler = handler;
	}

	//*******************************************
	// Methods
	//*******************************************

	@Override
	public int hashCode() {
		return handler.hashCode();
	}

	@Override
	public void add(Component component, String markupId) {
		handler.add(component, markupId);
	}

	@Override
	public void add(Component... components) {
		handler.add(components);
	}

	@Override
	public boolean equals(Object obj) {
		return handler.equals(obj);
	}

	@Override
	public final void addChildren(MarkupContainer parent, Class<?> childCriteria) {
		handler.addChildren(parent, childCriteria);
	}

	@Override
	public void appendJavaScript(CharSequence javascript) {
		handler.appendJavaScript(javascript);
	}

	@Override
	public void prependJavaScript(CharSequence javascript) {
		handler.prependJavaScript(javascript);
	}

	@Override
	public Collection<? extends Component> getComponents() {
		return handler.getComponents();
	}

	@Override
	public final void focusComponent(Component component) {
		handler.focusComponent(component);
	}

	@Override
	public IHeaderResponse getHeaderResponse() {
		return handler.getHeaderResponse();
	}

	@Override
	public Page getPage() {
		return handler.getPage();
	}

	@Override
	public Integer getPageId() {
		return handler.getPageId();
	}

	@Override
	public boolean isPageInstanceCreated() {
		return handler.isPageInstanceCreated();
	}

	@Override
	public Integer getRenderCount() {
		return handler.getRenderCount();
	}

	@Override
	public ILogData getLogData() {
		return handler.getLogData();
	}

	@Override
	public Class<? extends IRequestablePage> getPageClass() {
		return handler.getPageClass();
	}

	@Override
	public PageParameters getPageParameters() {
		return handler.getPageParameters();
	}

	@Override
	public void respond(IRequestCycle requestCycle) {
		handler.respond(requestCycle);
	}

	@Override
	public void detach(IRequestCycle requestCycle) {
		handler.detach(requestCycle);
	}

	@Override
	public String toString() {
		return handler.toString();
	}

	@Override
	public void addListener(IListener listener) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void registerRespondListener(ITargetRespondListener listener) {
		throw new UnsupportedOperationException();
	}

	@Override
	public String getLastFocusedElementId() {
		return null;
	}

}
