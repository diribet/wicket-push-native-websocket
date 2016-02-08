package cz.diribet.push.ws;

import org.apache.wicket.util.lang.Args;
import org.wicketstuff.push.AbstractPushEventContext;
import org.wicketstuff.push.IPushChannel;
import org.wicketstuff.push.IPushEventContext;

/**
 * {@link IPushEventContext} for use in the {@link WebSocketPushService}.
 *
 * @author Honza Krakora
 *
 * @param <EventType>
 *            type of event
 */
class WebSocketPushEventContext<EventType> extends AbstractPushEventContext<EventType> {

	//*******************************************
	// Attributes
	//*******************************************

	private final WebSocketPushService pushService;

	//*******************************************
	// Constructors
	//*******************************************

	WebSocketPushEventContext(EventType event, IPushChannel<EventType> channel, WebSocketPushService pushService) {
		super(event, channel);

		Args.notNull(pushService, "pushService");
		this.pushService = pushService;
	}

	//*******************************************
	// Methods
	//*******************************************

	//*******************************************
	// Getters/Setters
	//*******************************************

	@Override
	public WebSocketPushService getService() {
		return pushService;
	}

}
