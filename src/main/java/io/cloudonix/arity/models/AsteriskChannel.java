package io.cloudonix.arity.models;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import ch.loway.oss.ari4java.generated.actions.ActionChannels;
import ch.loway.oss.ari4java.generated.models.Channel;
import ch.loway.oss.ari4java.generated.models.LiveRecording;
import io.cloudonix.arity.ARIty;
import io.cloudonix.arity.Operation;

public class AsteriskChannel {

	private ARIty arity;
	private Channel channel;
	private ActionChannels api;

	public enum HangupReasons {
		NORMAL("normal"),
		BUSY("busy"),
		CONGESTION("congestion"),
		NO_ANSWER("no_answer");

		private String reason;

		HangupReasons(String reason) {
			this.reason = reason;
		}

		public String toString() {
			return reason;
		}
	}

	public AsteriskChannel(ARIty arity, Channel channel) {
		this.arity = arity;
		this.channel = channel;
		this.api = arity.getAri().channels();
	}

	public CompletableFuture<AsteriskChannel> hangup() {
		return hangup(HangupReasons.NORMAL);
	}

	private CompletableFuture<AsteriskChannel> hangup(HangupReasons reason) {
		return arity.channels().hangup(channel.getId(), reason).thenApply(v -> this);
	}

	public String getId() {
		return channel.getId();
	}

	/* Recording */
	
	public CompletableFuture<AsteriskRecording> record() {
		return record(b -> {});
	}

	public CompletableFuture<AsteriskRecording> record(boolean playBeep, int maxDuration, int maxSilence, String terminateOnDTMF) {
		return record(b -> b.withPlayBeep(playBeep).withMaxDuration(maxDuration).withMaxSilence(maxSilence).withTerminateOn(Objects.requireNonNull(terminateOnDTMF)));
	}
	
	public CompletableFuture<AsteriskRecording> record(Consumer<AsteriskRecording.Builder> withBuilder) {
		return Operation.<LiveRecording>retry(cb ->  AsteriskRecording.build(withBuilder).build(api.record(getId(), null, null), arity).execute(cb), this::mapExceptions)
				.thenApply(rec -> new AsteriskRecording(arity, rec));
	}

	private Exception mapExceptions(Throwable ariError) {
		switch (ariError.getMessage()) {
		case "Channel not in Stasis application": return new io.cloudonix.arity.errors.ChannelInInvalidState(ariError);
		}
		return null;
	}

}
