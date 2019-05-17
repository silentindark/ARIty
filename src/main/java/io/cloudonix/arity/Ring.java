package io.cloudonix.arity;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import ch.loway.oss.ari4java.tools.AriCallback;
import ch.loway.oss.ari4java.tools.RestException;

/**
 * Class that execute channel ring operation
 * 
 * @author naamag
 *
 */
public class Ring extends CancelableOperations {

	private final static Logger logger = Logger.getLogger(Answer.class.getName());
	private String channelId;

	/**
	 * Constructor 
	 * 
	 * @param callController instance of CallController
	 */
	public Ring(CallController callController) {
		super(callController.getChannelId(), callController.getARIty());
		this.channelId = callController.getChannelId();
	}

	/**
	 * The method ring to channel
	 */
	@Override
	public CompletableFuture<Ring> run() {
		CompletableFuture<Void> future = new CompletableFuture<Void>();
		channels().ring(channelId, new AriCallback<Void>() {

			@Override
			public void onSuccess(Void result) {
				logger.fine("Ringing");
				future.complete(result);
			}

			@Override
			public void onFailure(RestException e) {
				logger.warning("Failed ringing");
				future.completeExceptionally(e);
			}
		});
		return future.thenApply(v -> this);
	}

	@Override
	public CompletableFuture<Void> cancel() {
		CompletableFuture<Void> future = new CompletableFuture<Void>();
		channels().ringStop(channelId, new AriCallback<Void>() {

			@Override
			public void onSuccess(Void result) {
				logger.info("Stoped ringing to channel with id: " + channelId);
				future.complete(result);
			}

			@Override
			public void onFailure(RestException e) {
				logger.warning("Failed to stop ringing to channel with id: " + channelId);
				future.completeExceptionally(e);
			}
		});
		return future;
	}
}
