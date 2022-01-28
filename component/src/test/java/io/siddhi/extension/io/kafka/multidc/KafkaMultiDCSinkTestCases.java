/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.siddhi.extension.io.kafka.multidc;

import io.siddhi.core.SiddhiAppRuntime;
import io.siddhi.core.SiddhiManager;
import io.siddhi.core.event.Event;
import io.siddhi.core.stream.input.InputHandler;
import io.siddhi.core.stream.output.StreamCallback;
import io.siddhi.core.util.SiddhiTestHelper;
import io.siddhi.extension.io.kafka.KafkaTestUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.rmi.RemoteException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Class implementing the Test cases for KafkaMultiDCSink.
 */
public class KafkaMultiDCSinkTestCases {
    private static final Logger LOG = LogManager.getLogger(KafkaMultiDCSinkTestCases.class);
    private static ExecutorService executorService;
    private AtomicInteger count;

    @BeforeClass
    public static void init() throws Exception {
        try {
            executorService = Executors.newFixedThreadPool(5);
            KafkaTestUtil.cleanLogDir();
            KafkaTestUtil.setupKafkaBroker();
            Thread.sleep(1000);
            KafkaTestUtil.cleanLogDir2();
            KafkaTestUtil.setupKafkaBroker2();
            Thread.sleep(1000);
        } catch (Exception e) {
            throw new RemoteException("Exception caught when starting server", e);
        }
    }

    @AfterClass
    public static void stopKafkaBroker() throws InterruptedException {
        KafkaTestUtil.stopKafkaBroker();
        Thread.sleep(1000);
        KafkaTestUtil.stopKafkaBroker2();
        Thread.sleep(1000);
        while (!executorService.isShutdown() || !executorService.isTerminated()) {
            executorService.shutdown();
        }
    }

    @BeforeMethod
    public void reset() {
        count = new AtomicInteger(0);
    }

    @Test
    public void testMultiDCSinkWithBothBrokersRunning() throws InterruptedException {
        LOG.info("Creating test for publishing events for static topic without a partition");
        String[] topics = new String[]{"myTopic"};
        KafkaTestUtil.createTopic(KafkaTestUtil.ZK_SERVER_CON_STRING, topics, 1);
        KafkaTestUtil.createTopic(KafkaTestUtil.ZK_SERVER2_CON_STRING, topics, 1);
        Thread.sleep(4000);

        SiddhiManager sourceOneSiddhiManager = new SiddhiManager();
        SiddhiAppRuntime sourceOneApp = sourceOneSiddhiManager.createSiddhiAppRuntime(
                "@App:name('SourceOneSiddhiApp') " +
                        "define stream BarStream2 (symbol string, price float, volume long); " +
                        "@info(name = 'query1') " +
                        "@source(type='kafka', topic.list='myTopic', group.id='single_topic_test'," +
                        "partition.no.list='0', seq.enabled='true'," +
                        "threading.option='single.thread', bootstrap.servers='localhost:9092'," +
                        "@map(type='xml'))" +
                        "Define stream FooStream2 (symbol string, price float, volume long);" +
                        "from FooStream2 select symbol, price, volume insert into BarStream2;");

        sourceOneApp.addCallback("BarStream2", new StreamCallback() {
            @Override
            public void receive(Event[] events) {
                for (Event event : events) {
                    LOG.info(event);
                    count.getAndIncrement();
                }
            }
        });
        sourceOneApp.start();
        Thread.sleep(4000);

        SiddhiManager sourceTwoSiddhiManager = new SiddhiManager();
        SiddhiAppRuntime sourceTwoApp = sourceTwoSiddhiManager.createSiddhiAppRuntime(
                "@App:name('SourceTwoSiddhiApp') " +
                        "define stream BarStream2 (symbol string, price float, volume long); " +
                        "@info(name = 'query1') " +
                        "@source(type='kafka', topic.list='myTopic', group.id='single_topic_test'," +
                        "partition.no.list='0', seq.enabled='true'," +
                        "threading.option='single.thread', bootstrap.servers='localhost:9093'," +
                        "@map(type='xml'))" +
                        "Define stream FooStream2 (symbol string, price float, volume long);" +
                        "from FooStream2 select symbol, price, volume insert into BarStream2;");

        sourceTwoApp.addCallback("BarStream2", new StreamCallback() {
            @Override
            public void receive(Event[] events) {
                for (Event event : events) {
                    LOG.info(event);
                    count.getAndIncrement();
                }
            }
        });
        sourceTwoApp.start();
        Thread.sleep(4000);

        String sinkApp = "@App:name('SinkSiddhiApp') \n"
                + "define stream FooStream (symbol string, price float, volume long); \n"
                + "@info(name = 'query1') \n"
                + "@sink("
                + "type='kafkaMultiDC', "
                + "topic='myTopic', "
                + "partition='0',"
                + "bootstrap.servers='localhost:9092,localhost:9093', "
                + "@map(type='xml'))" +
                "Define stream BarStream (symbol string, price float, volume long);\n" +
                "from FooStream select symbol, price, volume insert into BarStream;\n";

        SiddhiManager siddhiManager = new SiddhiManager();
        SiddhiAppRuntime siddhiAppRuntimeSink = siddhiManager.createSiddhiAppRuntime(sinkApp);
        InputHandler fooStream = siddhiAppRuntimeSink.getInputHandler("BarStream");
        siddhiAppRuntimeSink.start();
        Thread.sleep(4000);
        fooStream.send(new Object[]{"WSO2", 55.6f, 100L});
        fooStream.send(new Object[]{"WSO2", 75.6f, 102L});
        fooStream.send(new Object[]{"WSO2", 57.6f, 103L});

        SiddhiTestHelper.waitForEvents(8000, 2, count, 40000);
        Assert.assertEquals(6, count.get());
        sourceOneApp.shutdown();
        sourceTwoApp.shutdown();
        siddhiAppRuntimeSink.shutdown();
        Thread.sleep(1000);

    }

    /*
    Even if one of the brokers are failing publishing should not be stopped for the other broker. Therefore, one
    siddhi app must receive events.
     */
    @Test (dependsOnMethods = "testMultiDCSinkWithBothBrokersRunning")
    public void testMultiDCSinkWithOneBrokersFailing() throws InterruptedException {
        LOG.info("Creating test for publishing events for static topic without a partition");
        String topics[] = new String[]{"myTopic"};
        KafkaTestUtil.createTopic(topics, 1);

        // Stopping 2nd Kafka broker to mimic a broker failure
        KafkaTestUtil.stopKafkaBroker2();
        Thread.sleep(4000);

        SiddhiManager sourceOneSiddhiManager = new SiddhiManager();
        SiddhiAppRuntime sourceOneApp = sourceOneSiddhiManager.createSiddhiAppRuntime(
                "@App:name('SourceSiddhiApp') " +
                        "define stream BarStream2 (symbol string, price float, volume long); " +
                        "@info(name = 'query1') " +
                        "@source(type='kafka', topic.list='myTopic', group.id='single_topic_test'," +
                        "partition.no.list='0', seq.enabled='true'," +
                        "threading.option='single.thread', bootstrap.servers='localhost:9092'," +
                        "@map(type='xml'))" +
                        "Define stream FooStream2 (symbol string, price float, volume long);" +
                        "from FooStream2 select symbol, price, volume insert into BarStream2;");

        sourceOneApp.addCallback("BarStream2", new StreamCallback() {
            @Override
            public synchronized void receive(Event[] events) {
                for (Event event : events) {
                    LOG.info(event);
                    count.getAndIncrement();
                }
            }
        });
        sourceOneApp.start();
        Thread.sleep(4000);

        String sinkApp = "@App:name('SinkSiddhiApp') \n"
                + "define stream FooStream (symbol string, price float, volume long); \n"
                + "@info(name = 'query1') \n"
                + "@sink("
                + "type='kafkaMultiDC', "
                + "topic='myTopic', "
                + "partition='0',"
                + "bootstrap.servers='localhost:9092,localhost:9093', "
                + "@map(type='xml'))" +
                "Define stream BarStream (symbol string, price float, volume long);\n" +
                "from FooStream select symbol, price, volume insert into BarStream;\n";

        SiddhiManager siddhiManager = new SiddhiManager();
        SiddhiAppRuntime siddhiAppRuntimeSink = siddhiManager.createSiddhiAppRuntime(sinkApp);
        InputHandler fooStream = siddhiAppRuntimeSink.getInputHandler("BarStream");
        siddhiAppRuntimeSink.start();
        Thread.sleep(4000);
        fooStream.send(new Object[]{"WSO2", 55.6f, 100L});
        fooStream.send(new Object[]{"WSO2", 75.6f, 102L});
        fooStream.send(new Object[]{"WSO2", 57.6f, 103L});
        Thread.sleep(4000);

        Assert.assertEquals(3, count.get());
        sourceOneApp.shutdown();
        siddhiAppRuntimeSink.shutdown();

    }

}
