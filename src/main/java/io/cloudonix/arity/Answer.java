package io.cloudonix.arity;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * The class represents the Answer operation (handle answering a call)
 * 
 * @author naamag
 *
 */
public class Answer extends Operation {

	private final static Logger logger = Logger.getLogger(Answer.class.getName());

	public Answer(CallController callController) {
		super(callController.getChannelId(), callController.getARIty());
	}

	/**
	 * The method answers a call that was received from an ARI channel
	 * 
	 * @return
	 */
	public CompletableFuture<Answer> run() {
		return Operation.<Void>retryOperation(cb -> channels().answer(getChannelId(), cb)).thenApply(res -> {
			logger.info("Channel with id: " + getChannelId() + " was answered");
			return this;
		});
	}
}
