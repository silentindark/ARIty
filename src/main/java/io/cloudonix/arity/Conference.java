package io.cloudonix.arity;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import ch.loway.oss.ari4java.generated.Bridge;
import ch.loway.oss.ari4java.generated.ChannelLeftBridge;
import ch.loway.oss.ari4java.generated.ChannelTalkingFinished;
import ch.loway.oss.ari4java.generated.ChannelTalkingStarted;
import ch.loway.oss.ari4java.generated.LiveRecording;
import ch.loway.oss.ari4java.generated.Playback;
import io.cloudonix.arity.errors.ConferenceException;
import io.cloudonix.future.helper.FutureHelper;
import io.cloudonix.lib.Futures;

/**
 * The class handles and saves all needed information for a conference call
 * 
 * @author naamag
 *
 */
public class Conference {
	private CallController callController;
	private final static Logger logger = Logger.getLogger(Conference.class.getName());
	private Runnable handleChannelLeftConference = () -> {
	};
	private String recordName = null;
	private LiveRecording conferenceRecord;
	private BridgeOperations bridgeOperations;
	private String musicOnHoldClassName = "default";
	private ARIty arity;
	private String conferenceName;
	private boolean mohStarted = false;
	private boolean mohStopped = false;
	private Runnable talkingStatedHandler = ()->{};
	private Runnable talkingFinishedEvent = ()->{};

	public Conference(CallController callController) {
		this.arity = callController.getARItyService();
		this.callController = callController;
		this.bridgeOperations = new BridgeOperations(arity);
		callController.setTalkingInChannel("set", "1500,750");
		arity.addFutureEvent(ChannelTalkingStarted.class, callController.getChannelID(),this::memberTalkingStartedEvent);
		arity.addFutureEvent(ChannelTalkingFinished.class, callController.getChannelID(),this::memberTalkingFinishedEvent);
		
	}
	
	public void memberTalkingStartedEvent(ChannelTalkingStarted talkingStarted, SavedEvent<ChannelTalkingStarted>se) {
		this.talkingStatedHandler.run();
	}
	
	public void memberTalkingFinishedEvent(ChannelTalkingFinished talkingStarted, SavedEvent<ChannelTalkingFinished>se) {
		this.talkingFinishedEvent .run();
	}
	
	public Conference registerMemberStartedTalkingHandler(Runnable talkingStartedH) {
		this.talkingStatedHandler = talkingStartedH;
		return this;
	}
	
	public Conference registerMemberFinishedTalkingHandler(Runnable talkingFinishedH) {
		this.talkingFinishedEvent = talkingFinishedH;
		return this;
	}

	/**
	 * close conference
	 * 
	 * @return
	 */
	public CompletableFuture<Void> closeConference() {
		logger.info("Closing conference");
		return bridgeOperations.destroyBridge();
	}

	/**
	 * add channel to the conference
	 * 
	 * @param beep         true if need to play 'beep' sound when channel joins to
	 *                     conference, false otherwise
	 * @param mute         true if the channel should only listen to conference
	 *                     without talking, false otherwise
	 * @param needToRecord true if need to record the conference, false otherwise
	 * @return
	 */
	public CompletableFuture<Conference> addChannelToConf(boolean beep, boolean mute, boolean needToRecord) {
		CompletableFuture<Answer> answer = new CompletableFuture<Answer>();
		if (callController.getCallMonitor().wasAnswered()) {
			logger.info("Channel with id: " + callController.getChannelID() + " was already answered");
			answer.complete(null);
		} else {
			logger.fine("Need to answer the channel with id: " + callController.getChannelID());
			answer = callController.answer().run();
		}
		return answer.thenCompose(answerRes -> bridgeOperations.addChannelToBridge(callController.getChannelID()))
				.thenCompose(v -> {
					logger.fine("Channel was added to the bridge");
					return beep ? playMedia("beep") : FutureHelper.completedSuccessfully(null);
				}).thenCompose(beepRes -> {
					arity.addFutureOneTimeEvent(ChannelLeftBridge.class, callController.getChannelID(),
							this::channelLeftConference);
					return mute ? callController.mute(callController.getChannelID(), "out").run()
							: FutureHelper.completedSuccessfully(null);
				}).thenCompose(muteRes -> annouceUser("joined")).exceptionally(Futures.on(Exception.class, t -> {
					logger.info("Unable to add channel to conference: " + t);
					throw new ConferenceException(t);
				})).thenApply(v -> this);
	}

	/**
	 * record the conference
	 * 
	 * @return
	 */
	public CompletableFuture<Void> recordConference() {
		logger.info("Start recording conference " + conferenceName);
		if (Objects.isNull(recordName))
			recordName = UUID.randomUUID().toString();
		return bridgeOperations.recordBridge(recordName).thenAccept(recored -> {
			conferenceRecord = recored;
			logger.info("Done recording");
		});
	}

	/**
	 * register handler to execute when channel left conference
	 * 
	 * @param handler runnable function that will be running when channel left
	 *                bridge occurs
	 * @return
	 */
	public Conference registerChannelLeftConferenceHandler(Runnable handler) {
		handleChannelLeftConference = handler;
		return this;
	}

	/**
	 * handle when a channel left conference bridge
	 * 
	 * @param channelLeftBridge channelLeftBridge event instance
	 */
	private void channelLeftConference(ChannelLeftBridge channelLeftBridge) {
		handleChannelLeftConference.run();
		logger.info("Channel " + channelLeftBridge.getChannel().getId() + " left conference: " + conferenceName);
		annouceUser("left").thenAccept(pb -> {
			bridgeOperations.getNumberOfChannelsInBridge().thenAccept(numberOfChannelsInConf -> {
				if (numberOfChannelsInConf == 1) {
					logger.info("Only one channel left in bridge, play music on hold");
					bridgeOperations.startMusicOnHold(musicOnHoldClassName);
				}
				if (numberOfChannelsInConf == 0) {
					closeConference().thenAccept(
							v2 -> logger.info("Nobody in the conference, closed the conference" + conferenceName))
							.exceptionally(t -> {
								logger.warning("Conference bridge was already destroyed");
								return null;
							});
				}

			});
		});
	}

	/**
	 * Announce new channel joined/left a conference
	 * 
	 * @param status 'joined' or 'left' conference
	 */
	public CompletableFuture<Playback> annouceUser(String status) {
		return (Objects.equals(status, "joined")) ? playMedia("confbridge-has-joined") : playMedia("conf-hasleft");
	}

	/**
	 * get conference name
	 * 
	 * @return
	 */
	public String getConfName() {
		return conferenceName;
	}

	/**
	 * get number of channels in conference
	 * 
	 * @return
	 */
	public CompletableFuture<Integer> getCount() {
		return bridgeOperations.getNumberOfChannelsInBridge();
	}

	/**
	 * get list of channels connected to the conference bridge
	 * 
	 * @return
	 */
	public CompletableFuture<List<String>> getChannelsInConf() {
		return bridgeOperations.getChannelsInBridge();
	}

	/**
	 * get recording name of the conference
	 * 
	 * @return
	 */
	public String getRecordName() {
		return recordName;
	}

	/**
	 * set recording name of the conference
	 * 
	 * @return
	 */
	public void setRecordName(String recordName) {
		this.recordName = recordName;
	}

	/**
	 * get the recording of the conference
	 * 
	 * @return
	 */
	public LiveRecording getConferenceRecord() {
		return conferenceRecord;
	}

	/**
	 * get music on hold file name of the conference
	 * 
	 * @return
	 */
	public String getMusicOnHoldFileName() {
		return musicOnHoldClassName;
	}

	/**
	 * set music on hold class for conference, defining the needed configuration for
	 * playing music on hold
	 * 
	 * @return
	 */
	public void setMusicOnHoldClassName(String musicOnHoldClassName) {
		this.musicOnHoldClassName = musicOnHoldClassName;
	}

	/**
	 * get recording start time
	 * 
	 * @return
	 */
	public String getRecordingStartTime() {
		RecordingData recordData = bridgeOperations.getRecodingByName(recordName);
		return Objects.nonNull(recordData) ? recordData.getStartingTime() : null;
	}

	/**
	 * check if there is a bridge for the conference
	 * 
	 * @return true if there is a bridge, false otherwise
	 */
	public CompletableFuture<Boolean> isConfereBridgeExists() {
		return bridgeOperations.getBridge().thenApply(bridgeRes -> true).exceptionally(t -> false);
	}

	/**
	 * create a bridge for the conference with a known id of bridge
	 * 
	 * @param conferenceName name of the conference
	 * @param bridgeId id of we want to set to conference bridge
	 * 
	 * @return the conference bridge
	 */
	public CompletableFuture<Bridge> createConferenceBridge(String conferenceName, String bridgeId) {
		this.conferenceName = conferenceName;
		bridgeOperations.setBridgeId(bridgeId);
		return bridgeOperations.createBridge(conferenceName).thenApply(bridgeRes -> {
			logger.info("Created a conference bridge");
			return bridgeRes;
		});
	}

	/**
	 * create a bridge for the conference without selecting the bridge id
	 * 
	 * @param conferenceName name of the conference
	 * 
	 * @return the conference bridge
	 */
	public CompletableFuture<Bridge> createConferenceBridge(String conferenceName) {
		this.conferenceName = conferenceName;
		return bridgeOperations.createBridge(conferenceName).thenApply(bridgeRes -> {
			logger.info("Created a conference bridge");
			return bridgeRes;
		});
	}

	/**
	 * remove a channel from this conference
	 * 
	 * @param channelId the id of the channel we want to remove
	 * @return
	 */
	public CompletableFuture<Void> removeChannelFromConf(String channelId) {
		return bridgeOperations.removeChannelFromBridge(channelId);
	}

	public CompletableFuture<Bridge> getBridge(String bridgeId) {
		bridgeOperations.setBridgeId(bridgeId);
		return bridgeOperations.getBridge().thenApply(bridgeRes -> {
			this.conferenceName = bridgeRes.getName();
			return bridgeRes;
		});
	}

	public String getConferenceName() {
		return conferenceName;
	}

	/**
	 * play media to the bridge
	 * 
	 * @param mediaToPlay name of the media to play
	 * @return promise to a Playback
	 */
	public CompletableFuture<Playback> playMedia(String mediaToPlay) {
		return bridgeOperations.playMediaToBridge(mediaToPlay);
	}

	/**
	 * remove channel from conference
	 * 
	 * @param channelID id of the channel we want to remove
	 * @return
	 */
	public CompletableFuture<Void> removeChannel(String channelID) {
		return bridgeOperations.removeChannelFromBridge(channelID);
	}

	/**
	 * start playing music on hold to conference bridge
	 * @return
	 */
	public CompletableFuture<Void> startMusicOnHold() {
		return bridgeOperations.startMusicOnHold(musicOnHoldClassName)
				.thenApply(v->{
					logger.fine("Started playing music on hold to conference bridge");
					mohStarted = true;
					return v;
				}).exceptionally(t->{
					if(t.getMessage().contains("Bridge not in Stasis application")) {
						logger.fine("Music on hold already started, can't start it again");
						mohStarted = false;
					}
					return null;
				});
	}

	/**
	 * stop playing to music on hold to conference bridge
	 * @return
	 */
	public CompletableFuture<Void> stopMusicOnHold() {
		return bridgeOperations.stopMusicOnHold()
				.thenApply(v->{
					logger.fine("Stoped playing music on hold to conference bridge");
					mohStopped = true;
					return v;
				}).exceptionally(t->{
					if(t.getMessage().contains("Bridge not in Stasis application")) {
						logger.fine("Music on hold already stoped, can't stop it again");
						mohStopped = false;
					}
					if(t.getMessage().contains("Bridge isn't playing music")) {
						logger.fine("Bridge isn't playing music, so can't stop music on hold");
						mohStopped = false;
					}
					return null;
				});
	}

	public boolean isMohStarted() {
		return mohStarted;
	}

	public void setMohStarted(boolean mohStarted) {
		this.mohStarted = mohStarted;
	}

	public boolean isMohStopped() {
		return mohStopped;
	}

	public void setMohStopped(boolean monStopped) {
		this.mohStopped = monStopped;
	}
}
