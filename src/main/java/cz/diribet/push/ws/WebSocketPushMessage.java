package cz.diribet.push.ws;

import java.util.List;

import org.apache.wicket.protocol.ws.api.message.IWebSocketPushMessage;

class WebSocketPushMessage<EventType> implements IWebSocketPushMessage {

	//*******************************************
	// Attributes
	//*******************************************

	private final List<WebSocketPushEventContext<EventType>> contexts;

	//*******************************************
	// Constructors
	//*******************************************

	WebSocketPushMessage(List<WebSocketPushEventContext<EventType>> contexts) {
		this.contexts = contexts;
	}

	//*******************************************
	// Methods
	//*******************************************

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((contexts == null) ? 0 : contexts.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (!(obj instanceof WebSocketPushMessage)) {
			return false;
		}
		WebSocketPushMessage<?> other = (WebSocketPushMessage<?>) obj;
		if (contexts == null) {
			if (other.contexts != null) {
				return false;
			}
		} else if (!contexts.equals(other.contexts)) {
			return false;
		}
		return true;
	}

	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append("WebSocketPushMessage [contexts=");
		builder.append(contexts);
		builder.append("]");
		return builder.toString();
	}

	//*******************************************
	// Getters/Setters
	//*******************************************

	public List<WebSocketPushEventContext<EventType>> getContexts() {
		return contexts;
	}

}
