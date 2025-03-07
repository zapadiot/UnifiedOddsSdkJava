/*
 * Copyright (C) Sportradar AG. See LICENSE for full license governing this code
 */

package com.sportradar.unifiedodds.sdk.impl.recovery;

import com.google.common.collect.ImmutableMap;
import com.rabbitmq.client.Recoverable;
import com.rabbitmq.client.ShutdownSignalException;
import com.sportradar.unifiedodds.sdk.MessageInterest;
import com.sportradar.unifiedodds.sdk.SDKEventRecoveryStatusListener;
import com.sportradar.unifiedodds.sdk.SDKInternalConfiguration;
import com.sportradar.unifiedodds.sdk.SDKProducerStatusListener;
import com.sportradar.unifiedodds.sdk.caching.NamedValuesProvider;
import com.sportradar.unifiedodds.sdk.impl.*;
import com.sportradar.unifiedodds.sdk.impl.apireaders.HttpHelper;
import com.sportradar.unifiedodds.sdk.impl.apireaders.WhoAmIReader;
import com.sportradar.unifiedodds.sdk.impl.oddsentities.FeedMessageFactoryImpl;
import com.sportradar.unifiedodds.sdk.impl.oddsentities.markets.MarketFactory;
import com.sportradar.unifiedodds.sdk.oddsentities.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Created on 09/04/2018.
 * // TODO @eti: Javadoc
 */
public class RecoveryManagerTests {

    private RecoveryManagerImpl recoveryManager;
    private SDKProducerManager producerManager;
    private ProducerStatusListener producerStatusListener;
    private TaskScheduler taskScheduler;
    private MockedExecutor mockedExecutor;

    private SDKEventRecoveryStatusListener mockedEventRecoveryListener;
    private HttpHelper mockedHttpHelper;
    private TimeUtils mockedTimeUtils;
    private SequenceGenerator mockedSequenceGenerator;

    @Before
    public void beforeTest() {
        SDKInternalConfiguration cfg = mock(SDKInternalConfiguration.class);
        when(cfg.isReplaySession()).thenReturn(false);
        when(cfg.getMaxRecoveryExecutionMinutes()).thenReturn(60);
        when(cfg.getLongestInactivityInterval()).thenReturn(20);
        when(cfg.getSdkNodeId()).thenReturn(null);

        ProducerDataProvider producerDataProvider = mock(ProducerDataProvider.class);
        when(producerDataProvider.getAvailableProducers()).thenReturn(getAvailableProducerData());
        producerManager = new ProducerManagerImpl(cfg, producerDataProvider);

        mockedEventRecoveryListener = mock(SDKEventRecoveryStatusListener.class);
        mockedHttpHelper = mock(HttpHelper.class);
        mockedTimeUtils = mock(TimeUtils.class);
        mockedSequenceGenerator = mock(SequenceGenerator.class);

        Instant now = Instant.now();
        when(mockedTimeUtils.now()).thenReturn(now.toEpochMilli());
        when(mockedTimeUtils.nowInstant()).thenReturn(now);

        taskScheduler = new TaskScheduler();
        mockedExecutor = new MockedExecutor();
        producerStatusListener = new ProducerStatusListener();

        FeedMessageFactory feedMessageFactory = new FeedMessageFactoryImpl(
                mock(MarketFactory.class),
                mock(NamedValuesProvider.class),
                producerManager
        );

        WhoAmIReader whoAmIReader = mock(WhoAmIReader.class);
        ImmutableMap<String, String> testMdcContext = ImmutableMap.<String, String>builder()
                .put("uf-sdk-tag", "uf-sdk-tests-context")
                .build();
        when(whoAmIReader.getAssociatedSdkMdcContextMap()).thenReturn(testMdcContext);

        SingleRecoveryManagerSupervisor supervisor = new SingleRecoveryManagerSupervisor(
                cfg,
                producerManager,
                producerStatusListener,
                mockedEventRecoveryListener,
                new DefaultSnapshotRequestManager(),
                taskScheduler,
                mockedExecutor,
                mockedHttpHelper,
                feedMessageFactory,
                whoAmIReader,
                mockedSequenceGenerator,
                mockedTimeUtils
        );

        recoveryManager = supervisor.getRecoveryManager();
        supervisor.startSupervising();
    }

    @After
    public void afterTestCleanup() {
        producerManager = null;
        producerStatusListener = null;
        mockedEventRecoveryListener = null;
        mockedHttpHelper = null;
        mockedTimeUtils = null;
        recoveryManager = null;
        mockedSequenceGenerator = null;
        taskScheduler = null;
    }

    @Test
    public void recoveryManagerInitCheckupTest() {
        mockedExecutor.execRecoveryManagerTimer();

        assertEquals(0, taskScheduler.getOneTimeTaskRuns());
        assertEquals(1, mockedExecutor.getTimerRuns());
        assertTrue(producerManager.isProducerDown(1));
        assertTrue(producerManager.isProducerDown(3));
    }

    @Test
    public void handleKnownSnapshotCompleteTest() {
        Producer producer = producerManager.getProducer(1);
        assertTrue(producer.isFlaggedDown());

        int recoveryId = 55;
        when(mockedSequenceGenerator.getNext()).thenReturn(recoveryId);
        recoveryManager.onAliveReceived(1, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), false, true);
        assertTrue(producer.isFlaggedDown());

        adjustMockedTimeUtils(30);
        recoveryManager.onSnapshotCompleteReceived(1, mockedTimeUtils.now(), recoveryId, MessageInterest.AllMessages);
        assertFalse(producer.isFlaggedDown());
        producerStatusListener.assertProducerUpInvoked(1, ProducerUpReason.FirstRecoveryCompleted);
        producerStatusListener.assertProducerStatusChangeInvoked(1, ProducerStatusReason.FirstRecoveryCompleted);

        adjustMockedTimeUtils(10);
        recoveryManager.onAliveReceived(1, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), true, true);
        assertFalse(producer.isFlaggedDown());
        assertEquals(1, taskScheduler.getOneTimeTaskRuns());

        // sanity check
        assertTrue(producerManager.isProducerDown(3));
    }

    @Test
    public void handleUnknownSnapshotCompleteTest() {
        Producer producer = producerManager.getProducer(1);
        assertTrue(producer.isFlaggedDown());

        int recoveryId = 55;
        when(mockedSequenceGenerator.getNext()).thenReturn(recoveryId);
        recoveryManager.onAliveReceived(1, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), false, true);
        assertTrue(producer.isFlaggedDown());

        adjustMockedTimeUtils(30);
        recoveryManager.onSnapshotCompleteReceived(1, mockedTimeUtils.now(), 66, MessageInterest.AllMessages);
        assertTrue(producer.isFlaggedDown());
        producerStatusListener.assertProducerUpInvoked(0);

        adjustMockedTimeUtils(10);
        long aliveGenTimestamp = getAdjustedMilliseconds(-3);
        recoveryManager.onAliveReceived(1, aliveGenTimestamp, mockedTimeUtils.now(), true, true);
        assertTrue(producer.isFlaggedDown());

        assertEquals(1, taskScheduler.getOneTimeTaskRuns());
    }

    @Test
    public void interruptedRecoveryRequestRepeatTest() {
        Producer producer = producerManager.getProducer(1);
        assertTrue(producer.isFlaggedDown());

        int recoveryId = 55;
        when(mockedSequenceGenerator.getNext()).thenReturn(recoveryId);
        recoveryManager.onAliveReceived(1, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), false, true);
        assertTrue(producer.isFlaggedDown());

        adjustMockedTimeUtils(10);
        mockedExecutor.execRecoveryManagerTimer(); // interrupted, no alive
        adjustMockedTimeUtils(10);

        adjustMockedTimeUtils(10);
        int repeatRecoveryId = 66;
        when(mockedSequenceGenerator.getNext()).thenReturn(repeatRecoveryId);
        recoveryManager.onSnapshotCompleteReceived(1, mockedTimeUtils.now(), recoveryId, MessageInterest.AllMessages);
        assertTrue(producer.isFlaggedDown());
        producerStatusListener.assertProducerUpInvoked(0);

        recoveryManager.onSnapshotCompleteReceived(1, mockedTimeUtils.now(), repeatRecoveryId, MessageInterest.AllMessages);
        assertFalse(producer.isFlaggedDown());
        producerStatusListener.assertProducerUpInvoked(1, ProducerUpReason.FirstRecoveryCompleted);
        producerStatusListener.assertProducerStatusChangeInvoked(1, ProducerStatusReason.FirstRecoveryCompleted);

        assertEquals(2, taskScheduler.getOneTimeTaskRuns());
    }

    @Test
    public void systemSessionAliveIntervalViolation() {
        Producer producer = producerManager.getProducer(3);
        assertTrue(producer.isFlaggedDown());

        String eventId = "sr:match:1";
        int recoveryId = 55;
        when(mockedSequenceGenerator.getNext()).thenReturn(recoveryId);
        recoveryManager.onAliveReceived(3, getAdjustedMilliseconds(-1), mockedTimeUtils.now(), true, true);
        assertTrue(producer.isFlaggedDown());

        adjustMockedTimeUtils(1);
        recoveryManager.onSnapshotCompleteReceived(3, mockedTimeUtils.now(), recoveryId, MessageInterest.AllMessages);
        assertFalse(producer.isFlaggedDown());
        producerStatusListener.assertProducerUpInvoked(1, ProducerUpReason.FirstRecoveryCompleted);
        producerStatusListener.assertProducerStatusChangeInvoked(1, ProducerStatusReason.FirstRecoveryCompleted);

        adjustMockedTimeUtils(1);
        recoveryManager.onMessageProcessingStarted(1, 3, Long.valueOf(recoveryId), mockedTimeUtils.now());
        recoveryManager.onMessageProcessingEnded(1, 3, getAdjustedMilliseconds(-1), eventId);
        recoveryManager.onAliveReceived(3, getAdjustedMilliseconds(-1), mockedTimeUtils.now(), true, false);

        adjustMockedTimeUtils(1);
        recoveryManager.onAliveReceived(3, getAdjustedMilliseconds(-1), mockedTimeUtils.now(), true, true);

        mockedExecutor.execRecoveryManagerTimer();
        assertFalse(producer.isFlaggedDown());

        adjustMockedTimeUtils(12);
        recoveryManager.onMessageProcessingStarted(1, 3, Long.valueOf(recoveryId), mockedTimeUtils.now());
        recoveryManager.onMessageProcessingEnded(1, 3, getAdjustedMilliseconds(-1), eventId);
        recoveryManager.onAliveReceived(3, getAdjustedMilliseconds(-1), mockedTimeUtils.now(), true, false);

        adjustMockedTimeUtils(10);
        recoveryManager.onMessageProcessingStarted(1, 3, Long.valueOf(recoveryId), mockedTimeUtils.now());
        recoveryManager.onMessageProcessingEnded(1, 3, getAdjustedMilliseconds(-1), eventId);
        recoveryManager.onAliveReceived(3, getAdjustedMilliseconds(-1), mockedTimeUtils.now(), true, false);

        mockedExecutor.execRecoveryManagerTimer();
        assertTrue(producer.isFlaggedDown());
        producerStatusListener.assertProducerDownInvoked(1, ProducerDownReason.AliveIntervalViolation);
        producerStatusListener.assertProducerStatusChangeInvoked(2, ProducerStatusReason.AliveIntervalViolation);
    }

    @Test
    public void userSessionQueueDelayStabilisedTest() {
        Producer producer = producerManager.getProducer(1);
        assertTrue(producer.isFlaggedDown());

        int recoveryId = 55;
        when(mockedSequenceGenerator.getNext()).thenReturn(recoveryId);
        recoveryManager.onAliveReceived(1, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), false, true);
        assertTrue(producer.isFlaggedDown());

        adjustMockedTimeUtils(10);
        recoveryManager.onSnapshotCompleteReceived(1, mockedTimeUtils.now(), recoveryId, MessageInterest.AllMessages);
        assertFalse(producer.isFlaggedDown());
        producerStatusListener.assertProducerUpInvoked(1, ProducerUpReason.FirstRecoveryCompleted);
        producerStatusListener.assertProducerStatusChangeInvoked(1, ProducerStatusReason.FirstRecoveryCompleted);

        // delayed message
        adjustMockedTimeUtils(1);
        recoveryManager.onMessageProcessingStarted(-33, 1, Long.valueOf(recoveryId), mockedTimeUtils.now());
        adjustMockedTimeUtils(1);
        recoveryManager.onMessageProcessingEnded(-33, 1, getAdjustedMilliseconds(-22), null);

        adjustMockedTimeUtils(1);
        recoveryManager.onAliveReceived(1, mockedTimeUtils.now(), mockedTimeUtils.now(), true, true);
        recoveryManager.onAliveReceived(1, mockedTimeUtils.now(), mockedTimeUtils.now(), true, false);
        mockedExecutor.execRecoveryManagerTimer();
        assertTrue(producer.isFlaggedDown());
        producerStatusListener.assertProducerDownInvoked(1, ProducerDownReason.ProcessingQueueDelayViolation);
        producerStatusListener.assertProducerStatusChangeInvoked(2, ProducerStatusReason.ProcessingQueueDelayViolation);

        // ok message
        adjustMockedTimeUtils(1);
        recoveryManager.onMessageProcessingStarted(-33, 1, Long.valueOf(recoveryId), mockedTimeUtils.now());
        adjustMockedTimeUtils(1);
        recoveryManager.onMessageProcessingEnded(-33, 1, mockedTimeUtils.now(), null);

        adjustMockedTimeUtils(1);
        recoveryManager.onAliveReceived(1, mockedTimeUtils.now(), mockedTimeUtils.now(), true, true);
        recoveryManager.onAliveReceived(1, mockedTimeUtils.now(), mockedTimeUtils.now(), true, false);
        assertFalse(producer.isFlaggedDown());
        producerStatusListener.assertProducerUpInvoked(2, ProducerUpReason.ProcessingQueDelayStabilized);
        producerStatusListener.assertProducerStatusChangeInvoked(3, ProducerStatusReason.ProcessingQueDelayStabilized);

        assertEquals(1, taskScheduler.getOneTimeTaskRuns());
    }

    @Test
    public void userSessionQueueDelayToAliveViolationTransitionTest() {
        Producer producer = producerManager.getProducer(1);
        assertTrue(producer.isFlaggedDown());

        int recoveryId = 55;
        when(mockedSequenceGenerator.getNext()).thenReturn(recoveryId);
        recoveryManager.onAliveReceived(1, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), false, true);
        assertTrue(producer.isFlaggedDown());

        adjustMockedTimeUtils(10);
        recoveryManager.onSnapshotCompleteReceived(1, mockedTimeUtils.now(), recoveryId, MessageInterest.AllMessages);
        assertFalse(producer.isFlaggedDown());
        producerStatusListener.assertProducerUpInvoked(1, ProducerUpReason.FirstRecoveryCompleted);
        producerStatusListener.assertProducerStatusChangeInvoked(1, ProducerStatusReason.FirstRecoveryCompleted);

        // delayed message
        adjustMockedTimeUtils(1);
        recoveryManager.onMessageProcessingStarted(-33, 1, Long.valueOf(recoveryId), mockedTimeUtils.now());
        adjustMockedTimeUtils(1);
        recoveryManager.onMessageProcessingEnded(-33, 1, getAdjustedMilliseconds(-22), null);

        adjustMockedTimeUtils(1);
        recoveryManager.onAliveReceived(1, mockedTimeUtils.now(), mockedTimeUtils.now(), true, true);
        mockedExecutor.execRecoveryManagerTimer();
        assertTrue(producer.isFlaggedDown());
        producerStatusListener.assertProducerDownInvoked(1, ProducerDownReason.ProcessingQueueDelayViolation);
        producerStatusListener.assertProducerStatusChangeInvoked(2, ProducerStatusReason.ProcessingQueueDelayViolation);

        // no alive received
        adjustMockedTimeUtils(25);
        mockedExecutor.execRecoveryManagerTimer();
        assertTrue(producer.isFlaggedDown());
        producerStatusListener.assertProducerDownInvoked(1, ProducerDownReason.ProcessingQueueDelayViolation);
        producerStatusListener.assertProducerStatusChangeInvoked(3, ProducerStatusReason.AliveIntervalViolation);

        // ok message
        adjustMockedTimeUtils(1);
        recoveryManager.onMessageProcessingStarted(-33, 1, Long.valueOf(recoveryId), mockedTimeUtils.now());
        adjustMockedTimeUtils(1);
        recoveryManager.onMessageProcessingEnded(-33, 1, mockedTimeUtils.now(), null);
        adjustMockedTimeUtils(1);
        mockedExecutor.execRecoveryManagerTimer(); // queue delay stabilised but the producer needs to remain down - alive interval violation
        assertTrue(producer.isFlaggedDown());

        assertEquals(1, taskScheduler.getOneTimeTaskRuns());

        adjustMockedTimeUtils(1);
        recoveryManager.onAliveReceived(1, mockedTimeUtils.now(), mockedTimeUtils.now(), true, true);
        adjustMockedTimeUtils(5);

        int recoveryId1 = 55;
        when(mockedSequenceGenerator.getNext()).thenReturn(recoveryId1);
        mockedExecutor.execRecoveryManagerTimer();
        assertTrue(producer.isFlaggedDown());

        assertEquals(2, taskScheduler.getOneTimeTaskRuns());

        adjustMockedTimeUtils(10);
        recoveryManager.onSnapshotCompleteReceived(1, mockedTimeUtils.now(), recoveryId1, MessageInterest.AllMessages);
        assertFalse(producer.isFlaggedDown());
        producerStatusListener.assertProducerUpInvoked(2, ProducerUpReason.ReturnedFromInactivity);
        producerStatusListener.assertProducerStatusChangeInvoked(5, ProducerStatusReason.ReturnedFromInactivity);
    }

    @Test
    public void userSessionAliveIntervalViolationTest() {
        Producer producer = producerManager.getProducer(1);
        assertTrue(producer.isFlaggedDown());

        int recoveryId = 55;
        when(mockedSequenceGenerator.getNext()).thenReturn(recoveryId);
        recoveryManager.onAliveReceived(1, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), false, true);
        assertTrue(producer.isFlaggedDown());

        adjustMockedTimeUtils(30);
        recoveryManager.onSnapshotCompleteReceived(1, mockedTimeUtils.now(), recoveryId, MessageInterest.AllMessages);
        assertFalse(producer.isFlaggedDown());
        producerStatusListener.assertProducerUpInvoked(1, ProducerUpReason.FirstRecoveryCompleted);
        producerStatusListener.assertProducerStatusChangeInvoked(1, ProducerStatusReason.FirstRecoveryCompleted);

        adjustMockedTimeUtils(10);
        long aliveGenTimestamp = getAdjustedMilliseconds(-3);
        long userSessionAliveGenTimestamp = getAdjustedMilliseconds(-25);
        recoveryManager.onAliveReceived(1, aliveGenTimestamp, mockedTimeUtils.now(), true, true);
        assertFalse(producer.isFlaggedDown());
        assertEquals(1, taskScheduler.getOneTimeTaskRuns());

        adjustMockedTimeUtils(5);
        recoveryManager.onAliveReceived(1, userSessionAliveGenTimestamp, mockedTimeUtils.now(), true, false);
        mockedExecutor.execRecoveryManagerTimer();
        assertTrue(producer.isFlaggedDown());
        producerStatusListener.assertProducerDownInvoked(1, ProducerDownReason.ProcessingQueueDelayViolation);
        producerStatusListener.assertProducerStatusChangeInvoked(2, ProducerStatusReason.ProcessingQueueDelayViolation);
    }

    @Test
    public void receivedAliveMessageWithSubscribedFalseTest() {
        Producer producer = producerManager.getProducer(1);
        assertTrue(producer.isFlaggedDown());

        int recoveryId = 55;
        when(mockedSequenceGenerator.getNext()).thenReturn(recoveryId);
        recoveryManager.onAliveReceived(1, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), false, true);
        assertTrue(producer.isFlaggedDown());

        adjustMockedTimeUtils(30);
        recoveryManager.onSnapshotCompleteReceived(1, mockedTimeUtils.now(), recoveryId, MessageInterest.AllMessages);
        assertFalse(producer.isFlaggedDown());
        producerStatusListener.assertProducerUpInvoked(1, ProducerUpReason.FirstRecoveryCompleted);
        producerStatusListener.assertProducerStatusChangeInvoked(1, ProducerStatusReason.FirstRecoveryCompleted);

        adjustMockedTimeUtils(5);
        recoveryManager.onAliveReceived(1, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), false, true);
        assertTrue(producer.isFlaggedDown());

        assertEquals(2, taskScheduler.getOneTimeTaskRuns());
    }

    @Test
    public void recoveryRestartAfterTimeOutTest() {
        Producer producer = producerManager.getProducer(1);
        assertTrue(producer.isFlaggedDown());

        int recoveryId = 55;
        when(mockedSequenceGenerator.getNext()).thenReturn(recoveryId);
        recoveryManager.onAliveReceived(1, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), false, true);
        assertEquals(1, taskScheduler.getOneTimeTaskRuns());
        assertTrue(producer.isFlaggedDown());

        adjustMockedTimeUtils(65 * 60);
        recoveryManager.onAliveReceived(1, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), true, true);
        assertEquals(2, taskScheduler.getOneTimeTaskRuns());
        assertTrue(producer.isFlaggedDown());
    }

    @Test
    public void handleChannelRecoveryWhileInRecoveryTest() {
        Producer producer = producerManager.getProducer(1);
        assertTrue(producer.isFlaggedDown());

        int recoveryId = 55;
        when(mockedSequenceGenerator.getNext()).thenReturn(recoveryId);
        recoveryManager.onAliveReceived(1, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), false, true);
        assertEquals(1, taskScheduler.getOneTimeTaskRuns());
        assertTrue(producer.isFlaggedDown());

        adjustMockedTimeUtils(5);

        recoveryManager.shutdownCompleted(mock(ShutdownSignalException.class));
        recoveryManager.handleRecovery(mock(Recoverable.class));

        adjustMockedTimeUtils(5);

        recoveryManager.onAliveReceived(1, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), true, true);
        assertEquals(2, taskScheduler.getOneTimeTaskRuns());
        assertTrue(producer.isFlaggedDown());
    }

    @Test
    public void handleChannelRecoveryWhileProducerIsUpTest() {
        Producer producer = producerManager.getProducer(1);
        assertTrue(producer.isFlaggedDown());

        int recoveryId = 55;
        when(mockedSequenceGenerator.getNext()).thenReturn(recoveryId);
        recoveryManager.onAliveReceived(1, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), false, true);
        assertTrue(producer.isFlaggedDown());

        adjustMockedTimeUtils(10);
        recoveryManager.onSnapshotCompleteReceived(1, mockedTimeUtils.now(), recoveryId, MessageInterest.AllMessages);
        assertFalse(producer.isFlaggedDown());
        producerStatusListener.assertProducerUpInvoked(1, ProducerUpReason.FirstRecoveryCompleted);
        producerStatusListener.assertProducerStatusChangeInvoked(1, ProducerStatusReason.FirstRecoveryCompleted);

        adjustMockedTimeUtils(5);

        recoveryManager.shutdownCompleted(mock(ShutdownSignalException.class));
        recoveryManager.handleRecovery(mock(Recoverable.class));
        assertTrue(producer.isFlaggedDown());

        adjustMockedTimeUtils(22);

        mockedExecutor.execRecoveryManagerTimer();
        assertTrue(producer.isFlaggedDown());

        adjustMockedTimeUtils(5);

        recoveryManager.onAliveReceived(1, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), true, true);
        assertEquals(2, taskScheduler.getOneTimeTaskRuns());
    }

    @Test
    public void handleChannelRecoveryFollowedByAliveMessageWhileProducerIsUpTest() {
        Producer producer = producerManager.getProducer(1);
        assertTrue(producer.isFlaggedDown());

        int recoveryId = 55;
        when(mockedSequenceGenerator.getNext()).thenReturn(recoveryId);
        recoveryManager.onAliveReceived(1, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), false, true);
        assertTrue(producer.isFlaggedDown());

        adjustMockedTimeUtils(10);
        recoveryManager.onSnapshotCompleteReceived(1, mockedTimeUtils.now(), recoveryId, MessageInterest.AllMessages);
        assertFalse(producer.isFlaggedDown());
        producerStatusListener.assertProducerUpInvoked(1, ProducerUpReason.FirstRecoveryCompleted);
        producerStatusListener.assertProducerStatusChangeInvoked(1, ProducerStatusReason.FirstRecoveryCompleted);

        adjustMockedTimeUtils(5);

        recoveryManager.shutdownCompleted(mock(ShutdownSignalException.class));
        recoveryManager.handleRecovery(mock(Recoverable.class));
        assertTrue(producer.isFlaggedDown());

        adjustMockedTimeUtils(22);

        recoveryManager.onAliveReceived(1, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), true, true);
        assertTrue(producer.isFlaggedDown());
        assertEquals(2, taskScheduler.getOneTimeTaskRuns());

        recoveryManager.onSnapshotCompleteReceived(1, mockedTimeUtils.now(), recoveryId, MessageInterest.AllMessages);
        assertFalse(producer.isFlaggedDown());
    }

    @Test
    public void handleChannelRecoveryWhileProducerIsDownTest() {
        Producer producer = producerManager.getProducer(1);
        assertTrue(producer.isFlaggedDown());

        int recoveryId = 55;
        when(mockedSequenceGenerator.getNext()).thenReturn(recoveryId);
        recoveryManager.onAliveReceived(1, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), false, true);
        assertTrue(producer.isFlaggedDown());

        adjustMockedTimeUtils(5);

        recoveryManager.shutdownCompleted(mock(ShutdownSignalException.class));
        recoveryManager.handleRecovery(mock(Recoverable.class));
        assertTrue(producer.isFlaggedDown());

        adjustMockedTimeUtils(22);

        mockedExecutor.execRecoveryManagerTimer();
        assertTrue(producer.isFlaggedDown());
        producerStatusListener.assertProducerUpInvoked(0);
        producerStatusListener.assertProducerStatusChangeInvoked(0);

        adjustMockedTimeUtils(5);

        recoveryManager.onAliveReceived(1, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), true, true);
        assertEquals(2, taskScheduler.getOneTimeTaskRuns());

        recoveryManager.onSnapshotCompleteReceived(1, mockedTimeUtils.now(), recoveryId, MessageInterest.AllMessages);
        assertFalse(producer.isFlaggedDown());
    }

    @Test
    public void handleChannelDisconnectWhileInRecoveryTest() {
        Producer producer = producerManager.getProducer(1);
        assertTrue(producer.isFlaggedDown());

        int recoveryId = 55;
        when(mockedSequenceGenerator.getNext()).thenReturn(recoveryId);
        recoveryManager.onAliveReceived(1, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), false, true);
        assertEquals(1, taskScheduler.getOneTimeTaskRuns());
        assertTrue(producer.isFlaggedDown());

        adjustMockedTimeUtils(5);

        recoveryManager.shutdownCompleted(mock(ShutdownSignalException.class));

        adjustMockedTimeUtils(5);

        recoveryManager.onAliveReceived(1, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), true, true);
        assertEquals(2, taskScheduler.getOneTimeTaskRuns());
        assertTrue(producer.isFlaggedDown());
    }

    @Test
    public void handleChannelDisconnectWhileProducerIsUpTest() {
        Producer producer = producerManager.getProducer(1);
        assertTrue(producer.isFlaggedDown());

        int recoveryId = 55;
        when(mockedSequenceGenerator.getNext()).thenReturn(recoveryId);
        recoveryManager.onAliveReceived(1, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), false, true);
        assertTrue(producer.isFlaggedDown());

        adjustMockedTimeUtils(10);
        recoveryManager.onSnapshotCompleteReceived(1, mockedTimeUtils.now(), recoveryId, MessageInterest.AllMessages);
        assertFalse(producer.isFlaggedDown());
        producerStatusListener.assertProducerUpInvoked(1, ProducerUpReason.FirstRecoveryCompleted);
        producerStatusListener.assertProducerStatusChangeInvoked(1, ProducerStatusReason.FirstRecoveryCompleted);

        adjustMockedTimeUtils(5);

        recoveryManager.shutdownCompleted(mock(ShutdownSignalException.class));

        adjustMockedTimeUtils(5);

        mockedExecutor.execRecoveryManagerTimer();
        assertTrue(producer.isFlaggedDown());

        adjustMockedTimeUtils(5);

        recoveryManager.onAliveReceived(1, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), true, true);
        assertEquals(2, taskScheduler.getOneTimeTaskRuns());
    }

    @Test
    public void handleMultipleScopeProducerRecoveryOnSingleSessionTest() {
        Producer producer = producerManager.getProducer(5);
        assertTrue(producer.isFlaggedDown());

        int recoveryId = 55;
        when(mockedSequenceGenerator.getNext()).thenReturn(recoveryId);
        recoveryManager.onAliveReceived(5, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), true, true);
        assertTrue(producer.isFlaggedDown());

        adjustMockedTimeUtils(10);
        recoveryManager.onSnapshotCompleteReceived(5, mockedTimeUtils.now(), recoveryId, MessageInterest.AllMessages);
        assertFalse(producer.isFlaggedDown());
        producerStatusListener.assertProducerUpInvoked(1, ProducerUpReason.FirstRecoveryCompleted);
        producerStatusListener.assertProducerStatusChangeInvoked(1, ProducerStatusReason.FirstRecoveryCompleted);
    }

    @Test
    public void producerDownReasonOtherTest() {
        Producer producer = producerManager.getProducer(3);
        assertTrue(producer.isFlaggedDown());

        int recoveryId = 55;
        when(mockedSequenceGenerator.getNext()).thenReturn(recoveryId);
        recoveryManager.onAliveReceived(3, getAdjustedMilliseconds(-1), mockedTimeUtils.now(), true, true);
        assertTrue(producer.isFlaggedDown());

        adjustMockedTimeUtils(1);
        recoveryManager.onSnapshotCompleteReceived(3, mockedTimeUtils.now(), recoveryId, MessageInterest.AllMessages);
        assertFalse(producer.isFlaggedDown());
        producerStatusListener.assertProducerUpInvoked(1, ProducerUpReason.FirstRecoveryCompleted);
        producerStatusListener.assertProducerStatusChangeInvoked(1, ProducerStatusReason.FirstRecoveryCompleted);

        adjustMockedTimeUtils(1);
        recoveryManager.onMessageProcessingStarted(1, 3, Long.valueOf(recoveryId), mockedTimeUtils.now());
        recoveryManager.onMessageProcessingEnded(1, 3, getAdjustedMilliseconds(-1), null);
        recoveryManager.onAliveReceived(3, getAdjustedMilliseconds(-1), mockedTimeUtils.now(), true, false);

        adjustMockedTimeUtils(1);
        recoveryManager.onAliveReceived(3, getAdjustedMilliseconds(-1), mockedTimeUtils.now(), true, true);

        mockedExecutor.execRecoveryManagerTimer();
        assertFalse(producer.isFlaggedDown());

        adjustMockedTimeUtils(5);
        recoveryManager.onAliveReceived(3, getAdjustedMilliseconds(-1), mockedTimeUtils.now(), false, true);
        assertTrue(producer.isFlaggedDown());
        producerStatusListener.assertProducerDownInvoked(1, ProducerDownReason.Other);
        producerStatusListener.assertProducerStatusChangeInvoked(2, ProducerStatusReason.Other);
    }

    @Test
    public void producerStatusChangeFromDelayedToAliveViolation() {
        Producer producer = producerManager.getProducer(1);
        assertTrue(producer.isFlaggedDown());

        int recoveryId = 55;
        when(mockedSequenceGenerator.getNext()).thenReturn(recoveryId);
        recoveryManager.onAliveReceived(1, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), false, true);
        assertTrue(producer.isFlaggedDown());

        adjustMockedTimeUtils(30);
        recoveryManager.onSnapshotCompleteReceived(1, mockedTimeUtils.now(), recoveryId, MessageInterest.AllMessages);
        assertFalse(producer.isFlaggedDown());
        producerStatusListener.assertProducerUpInvoked(1, ProducerUpReason.FirstRecoveryCompleted);
        producerStatusListener.assertProducerStatusChangeInvoked(1, ProducerStatusReason.FirstRecoveryCompleted);

        adjustMockedTimeUtils(10);
        recoveryManager.onAliveReceived(1, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), true, true);
        assertFalse(producer.isFlaggedDown());
        assertEquals(1, taskScheduler.getOneTimeTaskRuns());

        // delayed message
        adjustMockedTimeUtils(1);
        recoveryManager.onMessageProcessingStarted(-33, 1, Long.valueOf(recoveryId), mockedTimeUtils.now());
        adjustMockedTimeUtils(1);
        recoveryManager.onMessageProcessingEnded(-33, 1, getAdjustedMilliseconds(-22), null);

        adjustMockedTimeUtils(1);
        recoveryManager.onAliveReceived(1, mockedTimeUtils.now(), mockedTimeUtils.now(), true, true);
        recoveryManager.onAliveReceived(1, mockedTimeUtils.now(), mockedTimeUtils.now(), true, false);
        mockedExecutor.execRecoveryManagerTimer();
        assertTrue(producer.isFlaggedDown());
        producerStatusListener.assertProducerDownInvoked(1, ProducerDownReason.ProcessingQueueDelayViolation);
        producerStatusListener.assertProducerStatusChangeInvoked(2, ProducerStatusReason.ProcessingQueueDelayViolation);

        adjustMockedTimeUtils(12);
        recoveryManager.onMessageProcessingStarted(1, 3, Long.valueOf(recoveryId), mockedTimeUtils.now());
        recoveryManager.onMessageProcessingEnded(1, 3, getAdjustedMilliseconds(-1), null);
        recoveryManager.onAliveReceived(3, getAdjustedMilliseconds(-1), mockedTimeUtils.now(), true, false);

        mockedExecutor.execRecoveryManagerTimer();
        assertTrue(producer.isFlaggedDown());
        producerStatusListener.assertProducerDownInvoked(1, ProducerDownReason.ProcessingQueueDelayViolation);
        producerStatusListener.assertProducerStatusChangeInvoked(2, ProducerStatusReason.ProcessingQueueDelayViolation);

        adjustMockedTimeUtils(10);
        recoveryManager.onMessageProcessingStarted(1, 3, Long.valueOf(recoveryId), mockedTimeUtils.now());
        recoveryManager.onMessageProcessingEnded(1, 3, getAdjustedMilliseconds(-1), null);
        recoveryManager.onAliveReceived(3, getAdjustedMilliseconds(-1), mockedTimeUtils.now(), true, false);

        mockedExecutor.execRecoveryManagerTimer();
        assertTrue(producer.isFlaggedDown());
        producerStatusListener.assertProducerDownInvoked(1, ProducerDownReason.ProcessingQueueDelayViolation);
        producerStatusListener.assertProducerStatusChangeInvoked(3, ProducerStatusReason.AliveIntervalViolation);
    }

    @Test
    public void handleMultipleScopeProducerRecoveryOnMultiSessionTest() {
        Producer producer = producerManager.getProducer(5);
        assertTrue(producer.isFlaggedDown());

        int recoveryId = 55;
        when(mockedSequenceGenerator.getNext()).thenReturn(recoveryId);
        recoveryManager.onAliveReceived(5, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), true, true);
        assertTrue(producer.isFlaggedDown());

        adjustMockedTimeUtils(5);
        recoveryManager.onSnapshotCompleteReceived(5, mockedTimeUtils.now(), recoveryId, MessageInterest.PrematchMessagesOnly);
        assertTrue(producer.isFlaggedDown());

        adjustMockedTimeUtils(5);
        recoveryManager.onSnapshotCompleteReceived(5, mockedTimeUtils.now(), recoveryId, MessageInterest.LiveMessagesOnly);
        assertFalse(producer.isFlaggedDown());
        producerStatusListener.assertProducerUpInvoked(1, ProducerUpReason.FirstRecoveryCompleted);
        producerStatusListener.assertProducerStatusChangeInvoked(1, ProducerStatusReason.FirstRecoveryCompleted);
    }

    @Test
    public void getTimestampForRecoveryUpdateTest() {
        Producer producer = producerManager.getProducer(3);
        assertTrue(producer.isFlaggedDown());

        int recoveryId = 55;
        when(mockedSequenceGenerator.getNext()).thenReturn(recoveryId);
        recoveryManager.onAliveReceived(3, getAdjustedMilliseconds(-3), mockedTimeUtils.now(), true, true);
        assertTrue(producer.isFlaggedDown());
        assertEquals(0, producer.getTimestampForRecovery());

        adjustMockedTimeUtils(3);
        recoveryManager.onSnapshotCompleteReceived(3, mockedTimeUtils.now(), recoveryId, MessageInterest.PrematchMessagesOnly);
        assertFalse(producer.isFlaggedDown());
        assertEquals(0, producer.getTimestampForRecovery());

        long adjustedAliveGenTimestamp = getAdjustedMilliseconds(-3);
        recoveryManager.onAliveReceived(3, adjustedAliveGenTimestamp, mockedTimeUtils.now(), true, true);
        assertFalse(producer.isFlaggedDown());
        assertEquals(adjustedAliveGenTimestamp, producer.getTimestampForRecovery());
    }

    @Test
    public void producerLastProcessedMessageTimestampsUpdateTest() {
        final int processorId = UUID.randomUUID().hashCode();

        Producer producer = producerManager.getProducer(3);
        assertEquals(0, producer.getLastMessageTimestamp());
        assertEquals(0, producer.getLastProcessedMessageGenTimestamp());

        adjustMockedTimeUtils(1);

        long onMessageReceivedTimestamp = mockedTimeUtils.now();
        long receivedMessageGenTimestamp = getAdjustedMilliseconds(-1);
        recoveryManager.onMessageProcessingStarted(processorId, 3, null, onMessageReceivedTimestamp);
        recoveryManager.onMessageProcessingEnded(processorId, 3, receivedMessageGenTimestamp, null);
        adjustMockedTimeUtils(1);

        assertEquals(onMessageReceivedTimestamp, producer.getLastMessageTimestamp());
        assertEquals(receivedMessageGenTimestamp, producer.getLastProcessedMessageGenTimestamp());
    }

    private void adjustMockedTimeUtils(int seconds) {
        Instant instant = mockedTimeUtils.nowInstant();

        Instant updatedInstant;
        if (seconds >= 0) {
            updatedInstant = instant.plus(seconds, ChronoUnit.SECONDS);
        } else {
            updatedInstant = instant.minus(-1 * seconds, ChronoUnit.SECONDS);
        }

        when(mockedTimeUtils.nowInstant()).thenReturn(updatedInstant);
        when(mockedTimeUtils.now()).thenReturn(updatedInstant.toEpochMilli());
    }

    private long getAdjustedMilliseconds(int seconds) {
        Instant instant = mockedTimeUtils.nowInstant();

        Instant updatedInstant;
        if (seconds >= 0) {
            updatedInstant = instant.plus(seconds, ChronoUnit.SECONDS);
        } else {
            updatedInstant = instant.minus(-1 * seconds, ChronoUnit.SECONDS);
        }

        return updatedInstant.toEpochMilli();
    }

    private static List<ProducerData> getAvailableProducerData() {
       return Arrays.asList(
                new ProducerData(
                        1,
                        "LiveOdds",
                        "LiveOdds description",
                        true,
                        "lo-api-url",
                        "live",
                        4320),
                new ProducerData(
                        3,
                        "Ctrl",
                        "Ctrl description",
                        true,
                        "ctrl-api-url",
                        "prematch",
                        4320),
               new ProducerData(
                       5,
                       "PremiumCricket",
                       "PremiumCricket description",
                       true,
                       "pc-api-url",
                       "prematch|live",
                       4320)
        );
    }

    private class TaskScheduler implements SDKTaskScheduler {

        private int oneTimeTaskRuns = 0;

        @Override
        public void open() {
            // no-op
        }

        @Override
        public void shutdownNow() {
            // no-op
        }

        @Override
        public void scheduleAtFixedRate(String name, Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new IllegalStateException("Recovery manager should use only the #startOneTimeTask");
        }

        @Override
        public void startOneTimeTask(String name, Runnable command) {
            oneTimeTaskRuns++;
        }

        int getOneTimeTaskRuns() {
            return oneTimeTaskRuns;
        }
    }

    private class ProducerStatusListener implements SDKProducerStatusListener {
        private int producerDownTriggered = 0;
        private int producerUpTriggered = 0;
        private int producerStatusChangeTriggered = 0;
        private ProducerUpReason lastProducerUpReason = null;
        private ProducerDownReason lastProducerDownReason = null;
        private ProducerStatusReason lastProducerStatusReason = null;

        @Override
        public void onProducerDown(ProducerDown producerDown) {
            producerDownTriggered++;
            lastProducerDownReason = producerDown.getReason();
        }

        @Override
        public void onProducerUp(ProducerUp producerUp) {
            producerUpTriggered++;
            lastProducerUpReason = producerUp.getReason();
        }

        @Override
        public void onProducerStatusChange(ProducerStatus producerStatus) {
            producerStatusChangeTriggered++;
            lastProducerStatusReason = producerStatus.getProducerStatusReason();
        }

        void assertProducerUpInvoked(int count) {
            assertEquals(count, producerUpTriggered);
        }

        void assertProducerUpInvoked(int count, ProducerUpReason reason) {
            assertEquals(count, producerUpTriggered);
            assertEquals(reason, lastProducerUpReason);
        }

        void assertProducerDownInvoked(int count, ProducerDownReason reason) {
            assertEquals(count, producerDownTriggered);
            assertEquals(reason, lastProducerDownReason);
        }

        void assertProducerStatusChangeInvoked(int count) {
            assertEquals(count, producerStatusChangeTriggered);
        }

        void assertProducerStatusChangeInvoked(int count, ProducerStatusReason reason) {
            assertEquals(count, producerStatusChangeTriggered);
            assertEquals(reason, lastProducerStatusReason);
        }
    }

    private class MockedExecutor implements ScheduledExecutorService {
        private int timerRuns;
        private boolean timerSet = false;
        private Runnable timerTask;

        void execRecoveryManagerTimer() {
            timerTask.run();
            timerRuns++;
        }

        int getTimerRuns() {
            return timerRuns;
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            if (timerSet) {
                throw new IllegalStateException("Recovery manager should set only 1 task - the timer checkup");
            }

            timerSet = true;
            timerTask = command;

            return Mockito.mock(ScheduledFuture.class);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            throw new IllegalStateException("Shouldn't used by the RecoveryManager");
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new IllegalStateException("Shouldn't used by the RecoveryManager");
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            throw new IllegalStateException("Shouldn't used by the RecoveryManager");
        }

        @Override
        public void shutdown() {
            throw new IllegalStateException("Shouldn't used by the RecoveryManager");
        }

        @Override
        public List<Runnable> shutdownNow() {
            throw new IllegalStateException("Shouldn't used by the RecoveryManager");
        }

        @Override
        public boolean isShutdown() {
            throw new IllegalStateException("Shouldn't used by the RecoveryManager");
        }

        @Override
        public boolean isTerminated() {
            throw new IllegalStateException("Shouldn't used by the RecoveryManager");
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            throw new IllegalStateException("Shouldn't used by the RecoveryManager");
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            throw new IllegalStateException("Shouldn't used by the RecoveryManager");
        }

        @Override
        public <T> Future<T> submit(Runnable task, T result) {
            throw new IllegalStateException("Shouldn't used by the RecoveryManager");
        }

        @Override
        public Future<?> submit(Runnable task) {
            throw new IllegalStateException("Shouldn't used by the RecoveryManager");
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
            throw new IllegalStateException("Shouldn't used by the RecoveryManager");
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
            throw new IllegalStateException("Shouldn't used by the RecoveryManager");
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks) {
            throw new IllegalStateException("Shouldn't used by the RecoveryManager");
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
            throw new IllegalStateException("Shouldn't used by the RecoveryManager");
        }

        @Override
        public void execute(Runnable command) {
            throw new IllegalStateException("Shouldn't used by the RecoveryManager");
        }
    }
}
