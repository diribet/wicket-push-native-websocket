package cz.diribet.push.ws;

import org.apache.wicket.Application;
import org.apache.wicket.IInitializer;

/**
 * @author Honza Krakora
 *
 */
public class ApplicationShutdownListener implements IInitializer {

	@Override
	public void init(Application application) {
		// no-op
	}

	@Override
	public void destroy(Application application) {
		WebSocketPushService.onApplicationShutdown(application);
	}

}
