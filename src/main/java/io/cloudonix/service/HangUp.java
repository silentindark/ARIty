package io.cloudonix.service;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import ch.loway.oss.ari4java.tools.RestException;
import io.cloudonix.service.errors.HangUpException;

public class HangUp  extends Verb{
	private CompletableFuture<Void> compFuture;
	private final static Logger logger = Logger.getLogger(HangUp.class.getName());
	
	/**
	 * Constructor
	 * @param call
	 */
	public HangUp(Call call) {
		super(call.getChannelID(), call.getService(), call.getAri());
		compFuture = new CompletableFuture<>();
	}
	
	/**
	 * The method hangs up a call
	 * 
	 * @return
	 */
	public CompletableFuture<Void> run (){
		try {
			// hang up the call
			getAri().channels().hangup(getChanneLID(), "normal");
		} catch (RestException e) {
			logger.severe("failed hang up the call");
			compFuture.completeExceptionally(new HangUpException(e));
			return compFuture;
		}
		return CompletableFuture.completedFuture(null);
		
	}

}
