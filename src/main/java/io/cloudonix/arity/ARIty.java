package io.cloudonix.arity;

import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

import ch.loway.oss.ari4java.ARI;
import ch.loway.oss.ari4java.AriVersion;
import ch.loway.oss.ari4java.generated.models.BridgeCreated;
import ch.loway.oss.ari4java.generated.models.BridgeDestroyed;
import ch.loway.oss.ari4java.generated.models.Channel;
import ch.loway.oss.ari4java.generated.models.DeviceStateChanged;
import ch.loway.oss.ari4java.generated.models.Message;
import ch.loway.oss.ari4java.generated.models.PlaybackFinished;
import ch.loway.oss.ari4java.generated.models.PlaybackStarted;
import ch.loway.oss.ari4java.generated.models.RecordingFinished;
import ch.loway.oss.ari4java.generated.models.RecordingStarted;
import ch.loway.oss.ari4java.generated.models.StasisStart;
import ch.loway.oss.ari4java.tools.ARIException;
import ch.loway.oss.ari4java.tools.AriCallback;
import ch.loway.oss.ari4java.tools.RestException;
import io.cloudonix.arity.errors.ConnectionFailedException;
import io.cloudonix.arity.errors.ErrorStream;
import io.cloudonix.arity.helpers.Lazy;

/**
 * The class represents the creation of ARI and websocket service that handles
 * the incoming events
 *
 * @author naamag
 *
 */
public class ARIty implements AriCallback<Message> {
	private final static Logger logger = Logger.getLogger(ARIty.class.getName());
	private Queue<EventHandler<?>> eventHandlers = new ConcurrentLinkedQueue<EventHandler<?>>();
	private ARI ari;
	private String appName;
	private Supplier<CallController> callSupplier = this::hangupDefault;
	private ConcurrentHashMap<String, Consumer<CallState>> stasisStartListeners = new ConcurrentHashMap<>();
	private ExecutorService executor = ForkJoinPool.commonPool();
	private Consumer<Exception> ce;
	private Lazy<Channels> channels = new Lazy<>(() -> new Channels(this));

	/**
	 * Create and connect ARIty to Asterisk
	 *
	 * @param uri     Asterisk ARI URI
	 * @param appName name of the stasis application
	 * @param login   user name
	 * @param pass    password
	 *
	 * @throws ConnectionFailedException
	 * @throws URISyntaxException
	 */
	public ARIty(String uri, String appName, String login, String pass)
			throws ConnectionFailedException, URISyntaxException {
		this(uri, appName, login, pass, true, null);
	}

	/**
	 * Create and connect ARIty to Asterisk
	 *
	 * @param uri           Asterisk ARI
	 * @param appName       name of the stasis application
	 * @param login         user name
	 * @param pass          password
	 * @param openWebSocket if need to open web socket in order to process events
	 *                      true, false otherwise
	 *
	 * @throws ConnectionFailedException
	 * @throws URISyntaxException
	 */
	public ARIty(String uri, String appName, String login, String pass, boolean openWebSocket)
			throws ConnectionFailedException, URISyntaxException {
		this(uri, appName, login, pass, openWebSocket, null);
	}

	/**
	 * Create and connect ARIty to Asterisk
	 *
	 * @param uri           Asterisk ARI
	 * @param appName       name of the stasis application
	 * @param login         user name
	 * @param pass          password
	 * @param openWebSocket if need to open web socket in order to process events
	 *                      true, false otherwise
	 * @param ce            Handler to report connection exceptions to (set to null to ignore)
	 *
	 * @throws ConnectionFailedException
	 * @throws URISyntaxException
	 */
	public ARIty(String uri, String appName, String login, String pass, boolean openWebSocket, Consumer<Exception> ce)
			throws ConnectionFailedException, URISyntaxException {
		this(uri, appName, login, pass, openWebSocket, AriVersion.IM_FEELING_LUCKY, ce);
	}

	/**
	 * Create and connect ARIty to Asterisk
	 *
	 * @param uri           Asterisk ARI
	 * @param appName       name of the stasis application
	 * @param login         user name
	 * @param pass          password
	 * @param openWebSocket if need to open web socket in order to process events
	 *                      true, false otherwise
	 * @param version       ARI version to enforce
	 * @param ce            Handler to report connection exceptions to (set to null to ignore)
	 *
	 * @throws ConnectionFailedException
	 * @throws URISyntaxException
	 */
	public ARIty(String uri, String appName, String login, String pass, boolean openWebSocket, AriVersion version, Consumer<Exception> ce)
			throws ConnectionFailedException, URISyntaxException {
		this.appName = appName;
		this.ce = (Objects.isNull(ce)) ? e -> {
		} : ce;
		if (!uri.endsWith("/"))
			uri += "/";

		try {
			ari = ARI.build(uri, appName, login, pass, version);
			logger.info("Ari created");
			logger.info("Ari version: " + ari.getVersion());
			if (openWebSocket) {
				ari.events().eventWebsocket(appName).setSubscribeAll(true).execute(this);
				logger.info("Websocket is open");
			}
		} catch (ARIException e) {
			logger.severe("Connection failed: " + ErrorStream.fromThrowable(e));
			throw new ConnectionFailedException(e);
		}
	}

	/**
	 * The method register a new application to be executed according to the class
	 * of the voice application
	 *
	 * @param class instance of the class that contains the voice application
	 *        (extends from callController)
	 */
	public void registerVoiceApp(Class<? extends CallController> controllerClass) {
		callSupplier = new Supplier<CallController>() {

			@Override
			public CallController get() {
				try {
					return controllerClass.getConstructor().newInstance();
				} catch (Throwable e) {
					logger.severe("Failed to instantiate call controller from no-args c'tor of " + controllerClass
							+ ": " + ErrorStream.fromThrowable(e));
					return hangupDefault();
				}
			}
		};
	}

	/**
	 * The method register the voice application (the supplier that has a
	 * CallController, meaning the application)
	 *
	 * @param controllorSupplier the supplier that has the CallController (the voice
	 *                           application)
	 */
	public void registerVoiceApp(Supplier<CallController> controllorSupplier) {
		if (Objects.isNull(controllorSupplier))
			return;
		callSupplier = controllorSupplier;
	}

	/**
	 * The method register the voice application and execute it
	 *
	 * @param cc
	 */
	public void registerVoiceApp(Consumer<CallController> cc) {
		callSupplier = () -> {
			return new CallController() {

				@Override
				public CompletableFuture<Void> run() {
					return CompletableFuture.runAsync(() -> {
						cc.accept(this);
					});
				}
			};
		};
	}

	/**
	 * The method hangs up the call if we can't create an instance of the class that
	 * contains the voice application
	 *
	 * @param e
	 * @return
	 */
	protected CallController hangupDefault() {
		return new CallController() {
			public CompletableFuture<Void> run() {
				return hangup().run().thenAccept(hangup -> {
					logger.severe("Your Application is not registered!");
				});
			}
		};
	}

	/**
	 * Initialize an existing call controller for an existing channel
	 *
	 * Use this to have ARIty set up the ARIty call state and call monitor for an existing channel. ARIty will retrieve the channel
	 * from ARI and then initialize the provided Call Controller instance, eventually calling {@link CallController#init()}.
	 *
	 * It is highly recommended to use a new call controller instance with this method and not an instance that has already been run.
	 *
	 * @param controller Call controller to set up
	 * @param channelId Asterisk channel ID to request from Asterisk
	 */
	public <T extends CallController> CompletableFuture<T> initFromChannel(T controller, String channelId) {
		return getCallState(channelId)
				.thenAccept(controller::init)
				.thenApply(v -> controller);
	}

	/**
	 * Generate a new call state for an existing channel.
	 *
	 * Useful for applications that create new channels and want to monitor them. Please note that the retrieved
	 * call state instance does not share any data with other {@link CallState} instances that monitor the same
	 * channel.
	 * @param channelId ID of channel to monitor
	 * @return A promise for a new call state instance for that channel
	 */
	public CompletableFuture<CallState> getCallState(String channelId) {
		return Operation.<Channel>retry(h -> ari.channels().get(channelId).execute(h))
				.thenApply(chan -> new CallState(chan, this));
	}

	@Override
	public void onSuccess(Message event) {
		if (event instanceof StasisStart) {
			executor.submit(() -> handleStasisStart(event));
			return;
		}

		String channelId = getEventChannelId(event);
		logger.finest("Received event " + event.getClass().getSimpleName() + " on channel " + channelId);
		if (Objects.isNull(channelId))
			return;

		Iterator<EventHandler<?>> itr = eventHandlers.iterator();
		while (itr.hasNext()) {
			EventHandler<?> currEntry = itr.next();
			if (!Objects.equals(currEntry.getChannelId(), channelId))
				continue;
			currEntry.accept(event);
		}
	}

	private void handleStasisStart(Message event) {
		StasisStart ss = (StasisStart) event;
		if ("h".equals(ss.getChannel().getDialplan().getExten())) {
			logger.finer("Ignoring Stasis Start with 'h' extension, listen on channel hangup event if you want to handle hangups");
			return;
		}

		CallState callState = new CallState(ss, this);

		// see if an application waits for this channel
		Consumer<CallState> channelHandler = stasisStartListeners.remove(ss.getChannel().getId());
		if (Objects.nonNull(channelHandler)) {
			logger.fine("Sending stasis start for " + ss.getChannel().getId() + " to event handler " + channelHandler);
			channelHandler.accept(callState);
			return;
		}

		logger.fine("Stasis started with asterisk id: " + event.getAsterisk_id() + " and channel id is: " + ss.getChannel().getId());
		CallController cc = callSupplier.get();
		cc.init(callState);
		CompletableFuture.completedFuture(null).thenComposeAsync(v -> cc.run()).whenComplete((v,t) -> {
			if (Objects.nonNull(t)) {
				logger.severe("Completation error while running the application " + ErrorStream.fromThrowable(t));
				cc.hangup().run();
			}
		});
	}

	/**
	 * get the channel id of the current event. if no channel id to this event, null
	 * is returned
	 *
	 * @param event event message that we are checking
	 * @return
	 */
	private String getEventChannelId(Message event) {
		if (event instanceof DeviceStateChanged || event instanceof BridgeCreated || event instanceof BridgeDestroyed)
			return null; // skip this, it never has a channel

		if (event instanceof ch.loway.oss.ari4java.generated.models.Dial)
			return ((ch.loway.oss.ari4java.generated.models.Dial) event).getPeer().getId();

		if (event instanceof PlaybackStarted)
			return ((PlaybackStarted) event).getPlayback().getTarget_uri()
					.substring(((PlaybackStarted) event).getPlayback().getTarget_uri().indexOf(":") + 1);

		if (event instanceof PlaybackFinished)
			return ((PlaybackFinished) event).getPlayback().getTarget_uri()
					.substring(((PlaybackFinished) event).getPlayback().getTarget_uri().indexOf(":") + 1);

		if (event instanceof RecordingStarted)
			return ((RecordingStarted) event).getRecording().getTarget_uri()
					.substring(((RecordingStarted) event).getRecording().getTarget_uri().indexOf(":") + 1);
		if (event instanceof RecordingFinished)
			return ((RecordingFinished) event).getRecording().getTarget_uri()
					.substring(((RecordingFinished) event).getRecording().getTarget_uri().indexOf(":") + 1);

		try {
			Class<?> msgClass = event.getClass();
			Object chan = msgClass.getMethod("getChannel").invoke(event);
			if (Objects.nonNull(chan))
				return ((Channel) chan).getId();
		} catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException
				| InvocationTargetException e) {
			logger.warning("Can not get channel id for event " + event + ": " + e);
		}
		return null;
	}

	@Override
	public void onFailure(RestException e) {
		logger.warning(e.getMessage());
		ce.accept(e);
	}

	/**
	 * Register an event handler for a specific message on a specific channel
	 * @param type          type of message to listen to (example: PlaybackFinished)
	 * @param channelId     id of the channel to listen on
	 * @param eventHandler  handler to call when the event arrives
	 */
	public <T extends Message> EventHandler<T> addEventHandler(Class<T> type, String channelId, BiConsumer<T,EventHandler<T>> eventHandler) {
		logger.finer("Registering for " + type + " events on channel " + channelId);
		EventHandler<T> se = new EventHandler<T>(channelId, eventHandler, type,this);
		eventHandlers.add(se);
		return se;
	}

	/**
	 * remove event handler when no need to listen to it anymore
	 * @param handler the event handler to be removed
	 */
	public <T extends Message> void removeEventHandler(EventHandler<T>handler) {
		if(eventHandlers.remove(handler))
			logger.finer("Event "+handler.getClass1().getName()+" was removed for channel: "+handler.getChannelId());
	}

	/**
	 * Register a one-off event handler for a specific message on a specific channel.
	 *
	 * After the event is triggered once, the event handler is automatically unregistererd.
	 * @param type          type of message to listen to (example: PlaybackFinished)
	 * @param channelId     id of the channel to listen on
	 * @param eventHandler  handler to call when the event arrives
	 */
	public <T extends Message> void listenForOneTimeEvent(Class<T> type, String channelId, Consumer<T> eventHandler) {
		addEventHandler(type, channelId, (t,se) -> {
			se.unregister();
			eventHandler.accept(t);
		});
	}

	/**
	 * get the name of the application
	 *
	 * @return
	 */
	public String getAppName() {
		return appName;
	}

	/**
	 * Allow an ARIty application to take control of a known channel, before it enters ARI.
	 *
	 * This is useful with the ARIty application creates managed channels by itself
	 * @param id Known channel ID to wait for
	 * @param eventHandler handle that will receive the {@link CallState} object for the channel
	 * when it enters ARI
	 */
	public void registerApplicationStartHandler(String id, Consumer<CallState> eventHandler) {
		stasisStartListeners.put(id, eventHandler);
	}

	/**
	 * disconnect from the websocket (user's choice if to call it or not)
	 */
	public void disconnect() {
		ari.cleanup();
	}

	/**
	 * Initiate an unsolicited dial
	 * @param callerId Caller ID to be published to the destination
	 * @param destination Asterisk endpoint to be dialed to (including technology and URL)
	 * @return a Dial operation to configure further and run
	 */
	public Dial dial(String callerId, String destination) {
		return new Dial(this, callerId, destination);
	}

	/**
	 * Get the url that we are connected to
	 *
	 * @return
	 */
	public String getConnetion() {
		return ari.getUrl();
	}

	/**
	 * get call supplier
	 *
	 * @return
	 */
	public Supplier<CallController> getCallSupplier() {
		return callSupplier;
	}

	/**
	 * set call supplier
	 *
	 * @param callSupplier
	 */
	public void setCallSupplier(Supplier<CallController> callSupplier) {
		this.callSupplier = callSupplier;
	}

	/**
	 * get ARI instance
	 *
	 * @return
	 */
	public ARI getAri() {
		return ari;
	}

	/**
	 * get all active channels
	 *
	 * @return
	 */
	public CompletableFuture<List<Channel>> getActiveChannels(){
		return Operation.retry(cb -> ari.channels().list().execute(cb));
	}

	public Channels channels() {
		return channels.get();
	}
}
