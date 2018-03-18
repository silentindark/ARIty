package io.cloudonix.arity;


import java.util.logging.Logger;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

class DialTest {
	
	public class Application extends CallController {

		@Override
		public void run() {
			// call scenario - voice application
			answer().run().thenCompose(v -> play("hello-world").loop(2).run()).thenCompose(pb -> {
				logger.info("finished playback! id: " + pb.getPlayback().getId());
				return hangup().run();
			}).handle(this::endCall).exceptionally(t -> {
				logger.severe(t.toString());
				return null;
			});
		}
	}

	static AsteriskContainer asterisk = new AsteriskContainer();

	private final static Logger logger = Logger.getLogger(DialTest.class.getName());

	@BeforeClass
	static void beforeTesting() {
		asterisk.start();
	}
	
	@Test
	void testRun() {
		
	}
	
	
	@AfterClass
	static void afterTesting() {
		asterisk.stop();
	}

}