package cz.diribet.push.ws;

import org.apache.wicket.protocol.ws.api.message.IWebSocketPushMessage;

class WebSocketPushMessage<EventType> implements IWebSocketPushMessage {

	//*******************************************
	// Attributes
	//*******************************************

	private final WebSocketPushEventContext<EventType> context;

	//*******************************************
	// Constructors
	//*******************************************

	WebSocketPushMessage(WebSocketPushEventContext<EventType> context) {
		this.context = context;
	}

	//*******************************************
	// Methods
	//*******************************************

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((context == null) ? 0 : context.hashCode());
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
		if (context == null) {
			if (other.context != null) {
				return false;
			}
		} else if (!context.equals(other.context)) {
			return false;
		}
		return true;
	}

	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append("WebSocketPushMessage [context=");
		builder.append(context);
		builder.append("]");
		return builder.toString();
	}

	//*******************************************
	// Getters/Setters
	//*******************************************

	public WebSocketPushEventContext<EventType> getContext() {
		return context;
	}

}
