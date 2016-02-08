package cz.diribet.push.ws;

import java.util.UUID;

import org.wicketstuff.push.IPushNode;

/**
 * @author Honza Krakora
 *
 * @param <EventType>
 *            type of message event
 */
class WebSocketPushNode<EventType> implements IPushNode<EventType> {

	//*******************************************
	// Attributes
	//*******************************************

	private final UUID id = UUID.randomUUID();

	//*******************************************
	// Constructors
	//*******************************************

	//*******************************************
	// Methods
	//*******************************************

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((id == null) ? 0 : id.hashCode());
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
		if (!(obj instanceof WebSocketPushNode)) {
			return false;
		}
		WebSocketPushNode<?> other = (WebSocketPushNode<?>) obj;
		if (id == null) {
			if (other.id != null) {
				return false;
			}
		} else if (!id.equals(other.id)) {
			return false;
		}
		return true;
	}

	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append("WebSocketPushNode [id=");
		builder.append(id);
		builder.append("]");

		return builder.toString();
	}

	//*******************************************
	// Getters / setters
	//*******************************************

	public UUID getId() {
		return id;
	}

}
