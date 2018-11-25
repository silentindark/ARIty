package io.cloudonix.arity;

import java.util.concurrent.CompletableFuture;

import ch.loway.oss.ari4java.ARI;

/**
 * This class is for operations that can be cancelled, such as Dial, Play etc.
 * 
 * @author naamag
 *
 */
public abstract class CancelableOperations extends Operation {

	public CancelableOperations(String chanId, ARIty s, ARI a) {

		super(chanId, s, a);
	}

	/**
	 * cancel this operation
	 * 
	 * @return
	 */
	abstract CompletableFuture<Void> cancel();

}
