package com.instrumentalapp;

import org.junit.*;
import java.io.*;
import java.util.Scanner;
import java.util.Random;
import java.util.concurrent.Callable;

public class AgentTest {

    private static long start = System.currentTimeMillis();

    private static Random r = new Random();
    private static String apiKey;
    private Agent agent;

    @BeforeClass
    public static void setUp() throws Exception {
        try {
            Scanner scanner = new Scanner( new File("test_key") );
            apiKey = scanner.useDelimiter("\\A").next();
        } catch(FileNotFoundException e) {
            Assert.assertTrue("Please put the test project key into file 'test_key' in the project root", false);
        }
    }

    @Before
    public void initializeAgent() {
        agent = new Agent(new AgentOptions().setApiKey(apiKey));
    }

    @After
    public void waitForAgentFlush() {
        while (agent.getPending() > 0) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {}
        }
    }

    @Test
    public void gaugeTest() {
        for (int i = 0; i < 20; i++) {
            float val = r.nextFloat() * 100;
            agent.gauge("test.gauge", val);
        }
        Assert.assertEquals(20, agent.getPending());
        Assert.assertTrue(agent.nextMetricToSubmit().startsWith("gauge test.gauge"));
    }

    @Test
    public void gaugeBackfillTest() {
        long timestamp = 1514764800000L; // 2018-01-01 00:00:00 GMT
        agent.gauge("test.gauge", 1, timestamp, 1);
        Assert.assertEquals(
            "gauge test.gauge 1.0 1514764800 1",
            agent.nextMetricToSubmit()
        );
    }

    @Test
    public void timeTest() throws Exception {
        Assert.assertEquals("test string", agent.time("test.time", new Callable() {
                    @Override
                    public String call() throws Exception{
                        Thread.sleep(100);
                        return "test string";
                    }
                }));
    }

    @Test
    public void timeMsTest() throws Exception {
        Assert.assertEquals("test string", agent.timeMs("test.timeMs", new Callable() {
                    @Override
                    public String call() throws Exception{
                        Thread.sleep(100);
                        return "test string";
                    }
                }));
    }

    @Test
    public void incrementTest() {
        for (int i = 0; i < 20; i++) {
            agent.increment("test.increment");
        }
        Assert.assertEquals(20, agent.getPending());
    }

    @Test
    public void noticeTest() throws Exception {
        agent.notice("This is a 2 minutes notice from Java", start - 120000, 120000);
        Assert.assertEquals(1, agent.getPending());
    }

    @Test
    public void nonblockingTest() {
        for (int i = 1; i < (Connection.MAX_QUEUE_SIZE + 1); i++) {
            agent.increment("test.increment.nonblocking");
        }
        Assert.assertFalse("Queue buffer overrun when it shouldn't", agent.isQueueOverflowing());
    }
}
