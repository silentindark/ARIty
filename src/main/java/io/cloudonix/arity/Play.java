package io.cloudonix.arity;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.loway.oss.ari4java.generated.models.Channel;
import ch.loway.oss.ari4java.generated.models.Playback;
import ch.loway.oss.ari4java.generated.models.PlaybackFinished;
import io.cloudonix.arity.errors.PlaybackException;

/**
 * The class represents a Play operation (plays a playback and cancels the
 * playback if needed)
 *
 * @author naamag
 *
 */
public class Play extends CancelableOperations {
	private final static Logger logger = LoggerFactory.getLogger(Play.class);

	private String language="en";
	private String uriScheme = "sound";
	private String playFileName;
	private AtomicInteger timesToPlay = new AtomicInteger(1);
	private AtomicBoolean cancelled = new AtomicBoolean(false);
	private AtomicReference<Playback> playback = new AtomicReference<>();
	private volatile String currentPlaybackId;

	private Bridge playBridge;

	/**
	 * Play the specified content using the specified scheme (default "sound")
	 *
	 * @param callController controller for the channel
	 * @param filename       content to play
	 */
	public Play(CallController callController, String filename) {
		super(callController.getChannelId(), callController.getARIty());
		this.playFileName = filename;
		initLanguage(callController);
	}

	private void initLanguage(CallController callController) {
		Channel chan = callController.getChannel();
		if (Objects.nonNull(chan) && Objects.nonNull(chan.getLanguage())) {
			this.language = chan.getLanguage();
			return;
		}
		if (Objects.nonNull(callController.getCallState()) && Objects.nonNull(callController.getCallState().getChannel()))
			this.language = callController.getCallState().getChannel().getLanguage();
		else
			logger.debug("Using default language: {}", language);
	}
	
	public Play withBridge(Bridge bridge) {
		this.playBridge = bridge;
		return this;
	}

	/**
	 * The method changes the uri scheme to recording and plays the stored recored
	 *
	 * @return
	 */
	public CompletableFuture<Play> playRecording() {
		setUriScheme("recording:");
		return run();
	}

	/**
	 * The method plays a playback of a specific ARI channel
	 *
	 * @return
	 */
	public CompletableFuture<Play> run() {
		String fullPath = uriScheme +":"+ playFileName;
		logger.debug("Play::run ({})", fullPath);
		return startPlay(fullPath)
				.thenCompose(v -> {
					logger.info(currentPlaybackId+"|startPlay finished (" + fullPath + ")");
					if (cancelled() || timesToPlay.decrementAndGet() <= 0)
						return CompletableFuture.completedFuture(this);
					return run();
				})
				.whenComplete((v,t) -> { logger.debug("{}|Play::run ({})", currentPlaybackId, fullPath); });
	}

	protected CompletableFuture<Play> startPlay(String path) {
		if (cancelled()) // if we're already cancelled, make any additional iteration a no-op
			return CompletableFuture.completedFuture(null);

		currentPlaybackId = UUID.randomUUID().toString();
		CompletableFuture<Play> playbackFinished = new CompletableFuture<>();
		getArity().addEventHandler(PlaybackFinished.class, getChannelId(), (finished, se) -> {
			String finishId = finished.getPlayback().getId();
			if (!Objects.equals(finishId, currentPlaybackId))
				return;
			currentPlaybackId = null;
			logger.info(finishId + "|Finished playback: {}", finished.getPlayback().getState());
			playback.set(null);
			playbackFinished.complete(this);
			se.unregister();
		});
		
		return executePlayOperation(path)
		.thenCompose(playback -> {
			this.playback.set(playback); // store ongoing playback for cancelling
			logger.info(currentPlaybackId + "|Playback started! Playing: " + playFileName + " and playback id is: " + playback.getId());
			return playbackFinished;
		})
		.exceptionally(e -> {
			logger.warn("Failed in playing playback", e);
			throw new CompletionException(new PlaybackException(path, e));
		});
	}

	private CompletableFuture<Playback> executePlayOperation(String path) {
		if (this.playBridge != null)
			return this.retryOperation(h -> bridges().play(this.playBridge.getId(), path).setLang(language).setPlaybackId(currentPlaybackId).execute(h));
		return this.retryOperation(h -> channels().play(getChannelId(), path).setLang(language).setPlaybackId(currentPlaybackId).execute(h));
	}

	/**
	 * set how many times to play the play-back
	 *
	 * @param times
	 * @return
	 */
	public Play loop(int times) {
		timesToPlay.set(times);
		return this;
	}

	/**
	 * Get the play-back
	 *
	 * @return
	 */
	public Playback getPlayback() {
		return playback.get();
	}

	@Override
	public CompletableFuture<Void> cancel() {
		cancelled.set(true);
		if (currentPlaybackId == null)
			return CompletableFuture.completedFuture(null); // no need to cancel, before startPlay is called again, cancelled() will be checked
		logger.info(currentPlaybackId + "|Trying to cancel a playback");
		return this.<Void>retryOperation(cb -> playbacks().stop(currentPlaybackId).execute(cb))
				.thenAccept(pb -> logger.info(currentPlaybackId + "|Playback canceled"));
	}
	
	public boolean cancelled() {
		return cancelled.get();
	}

	/**
	 * get the name of the file to play
	 *
	 * @return
	 */
	public String getPlayFileName() {
		return playFileName;
	}

	/**
	 * set the file to be played
	 *
	 * @param playFileName
	 */
	public void setPlayFileName(String playFileName) {
		this.playFileName = playFileName;
	}

	public String getUriScheme() {
		return uriScheme;
	}

	/**
	 * set the uri scheme before playing and get the update Play operation
	 *
	 * @param uriScheme
	 * @return
	 */
	public Play setUriScheme(String uriScheme) {
		this.uriScheme = uriScheme;
		return this;
	}

	public String getLanguage() {
		return language;
	}

	/**
	 * set the playback language before playing
	 *
	 * @param channelLanguage language to set
	 * @return
	 */
	public Play setLanguage(String channelLanguage) {
		this.language = channelLanguage;
		return this;
	}

}
