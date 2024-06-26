/*
 * Copyright (c) 2020-2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.internal.bookkeeping;

import static io.airbyte.protocol.models.AirbyteStreamStatusTraceMessage.AirbyteStreamStatus.COMPLETE;
import static io.airbyte.protocol.models.AirbyteStreamStatusTraceMessage.AirbyteStreamStatus.INCOMPLETE;
import static io.airbyte.protocol.models.AirbyteStreamStatusTraceMessage.AirbyteStreamStatus.RUNNING;
import static io.airbyte.protocol.models.AirbyteStreamStatusTraceMessage.AirbyteStreamStatus.STARTED;
import static io.airbyte.workers.test_utils.TestConfigHelpers.DESTINATION_IMAGE;
import static io.airbyte.workers.test_utils.TestConfigHelpers.SOURCE_IMAGE;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.airbyte.api.client.AirbyteApiClient;
import io.airbyte.api.client.generated.StreamStatusesApi;
import io.airbyte.api.client.model.generated.StreamStatusCreateRequestBody;
import io.airbyte.api.client.model.generated.StreamStatusIncompleteRunCause;
import io.airbyte.api.client.model.generated.StreamStatusJobType;
import io.airbyte.api.client.model.generated.StreamStatusRateLimitedMetadata;
import io.airbyte.api.client.model.generated.StreamStatusRead;
import io.airbyte.api.client.model.generated.StreamStatusRunState;
import io.airbyte.api.client.model.generated.StreamStatusUpdateRequestBody;
import io.airbyte.featureflag.FeatureFlagClient;
import io.airbyte.featureflag.TestClient;
import io.airbyte.protocol.models.AirbyteMessage;
import io.airbyte.protocol.models.AirbyteMessage.Type;
import io.airbyte.protocol.models.AirbyteStreamStatusRateLimitedReason;
import io.airbyte.protocol.models.AirbyteStreamStatusReason;
import io.airbyte.protocol.models.AirbyteStreamStatusTraceMessage;
import io.airbyte.protocol.models.AirbyteStreamStatusTraceMessage.AirbyteStreamStatus;
import io.airbyte.protocol.models.AirbyteTraceMessage;
import io.airbyte.protocol.models.StreamDescriptor;
import io.airbyte.workers.context.ReplicationContext;
import io.airbyte.workers.internal.bookkeeping.events.ReplicationAirbyteMessageEvent;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

/**
 * Test suite for the {@link OldStreamStatusTracker} class.
 */
@ExtendWith(MockitoExtension.class)
class StreamStatusTrackerTest {

  private static final Integer ATTEMPT = 1;
  private static final UUID CONNECTION_ID = UUID.randomUUID();
  private static final UUID DESTINATION_ID = UUID.randomUUID();
  private static final Long JOB_ID = 1L;
  private static final UUID SOURCE_ID = UUID.randomUUID();
  private static final UUID STREAM_ID = UUID.randomUUID();
  private static final UUID WORKSPACE_ID = UUID.randomUUID();
  private static final UUID SOURCE_DEFINITION_ID = UUID.randomUUID();
  private static final UUID DESTINATION_DEFINITION_ID = UUID.randomUUID();
  private static final Duration TIMESTAMP = Duration.of(12345L, ChronoUnit.MILLIS);

  private AirbyteApiClient airbyteApiClient;
  private StreamDescriptor streamDescriptor;
  private StreamStatusesApi streamStatusesApi;
  private OldStreamStatusTracker streamStatusTracker;
  private FeatureFlagClient featureFlagClient;
  @Captor
  private ArgumentCaptor<StreamStatusUpdateRequestBody> updateArgumentCaptor;

  @BeforeEach
  void setup() {
    streamStatusesApi = mock(StreamStatusesApi.class);
    airbyteApiClient = mock(AirbyteApiClient.class);
    featureFlagClient = mock(TestClient.class);
    when(featureFlagClient.boolVariation(any(), any())).thenReturn(true);
    streamDescriptor = new StreamDescriptor().withName("name").withNamespace("namespace");
    streamStatusTracker = new OldStreamStatusTracker(airbyteApiClient, featureFlagClient);
  }

  @AfterEach
  void resetMocks() {
    Mockito.reset(streamStatusesApi, airbyteApiClient, featureFlagClient);
  }

  @Test
  void testLazyMdc() {
    MDC.put("foo", "bar");
    // streamStatusTracker.postConstruct();
    assertEquals(MDC.getCopyOfContextMap(), streamStatusTracker.getMdc());
  }

  @Test
  void testCurrentStatusNoStatus() {
    final StreamStatusKey streamStatusKey =
        new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(), WORKSPACE_ID, CONNECTION_ID, JOB_ID, ATTEMPT);
    assertNull(streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testTrackingStartedStatus(final boolean isReset) throws IOException {
    final AirbyteMessageOrigin airbyteMessageOrigin = AirbyteMessageOrigin.SOURCE;
    final AirbyteMessage airbyteMessage = createAirbyteMessage(streamDescriptor, STARTED, TIMESTAMP);
    final ReplicationContext replicationContext = getDefaultContext(isReset);
    final ReplicationAirbyteMessageEvent event = new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, airbyteMessage, replicationContext);
    final StreamStatusCreateRequestBody expected = new StreamStatusCreateRequestBody(
        ATTEMPT,
        CONNECTION_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.RUNNING,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        null,
        streamDescriptor.getNamespace(),
        null);
    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext.getWorkspaceId(), replicationContext.getConnectionId(), replicationContext.getJobId(), replicationContext.getAttempt());
    final StreamStatusRead streamStatusRead = new StreamStatusRead(
        ATTEMPT,
        CONNECTION_ID,
        STREAM_ID,
        JOB_ID,
        StreamStatusJobType.SYNC,
        StreamStatusRunState.RUNNING,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        null,
        streamDescriptor.getNamespace(),
        null);

    when(streamStatusesApi.createStreamStatus(any())).thenReturn(streamStatusRead);
    when(airbyteApiClient.getStreamStatusesApi()).thenReturn(streamStatusesApi);

    streamStatusTracker.track(event);

    assertEquals(STARTED, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    verify(streamStatusesApi, times(1)).createStreamStatus(expected);
    verify(streamStatusesApi, times(0)).updateStreamStatus(any(StreamStatusUpdateRequestBody.class));
  }

  @Test
  void testTrackingRateLimitedStatus() throws IOException {
    final AirbyteMessageOrigin airbyteMessageOrigin = AirbyteMessageOrigin.SOURCE;
    final ReplicationContext replicationContext = getDefaultContext(false);

    final Instant quotaReset = Instant.now();
    final AirbyteMessage startedAirbyteMessage = createAirbyteMessage(streamDescriptor, STARTED, TIMESTAMP);
    final AirbyteMessage rateLimitedMessage = createAirbyteMessageWithRateLimitedInfo(streamDescriptor, TIMESTAMP, quotaReset.toEpochMilli());

    final ReplicationAirbyteMessageEvent startedEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, startedAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent rateLimitedEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, rateLimitedMessage, replicationContext);

    final StreamStatusUpdateRequestBody expected = new StreamStatusUpdateRequestBody(
        ATTEMPT,
        CONNECTION_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.RATE_LIMITED,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        STREAM_ID,
        null,
        streamDescriptor.getNamespace(),
        new StreamStatusRateLimitedMetadata(quotaReset.toEpochMilli()));

    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext.getWorkspaceId(), replicationContext.getConnectionId(), replicationContext.getJobId(), replicationContext.getAttempt());

    when(streamStatusesApi.createStreamStatus(any())).thenReturn(new StreamStatusRead(
        ATTEMPT,
        CONNECTION_ID,
        STREAM_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.RATE_LIMITED,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        null,
        streamDescriptor.getNamespace(),
        new StreamStatusRateLimitedMetadata(quotaReset.toEpochMilli())));
    when(airbyteApiClient.getStreamStatusesApi()).thenReturn(streamStatusesApi);

    streamStatusTracker.track(startedEvent);
    streamStatusTracker.track(rateLimitedEvent);

    assertEquals(RUNNING, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    verify(streamStatusesApi, times(1)).createStreamStatus(any(StreamStatusCreateRequestBody.class));
    verify(streamStatusesApi, times(1)).updateStreamStatus(expected);
    assertTrue(streamStatusTracker.getStreamsInRateLimitedStatus().contains(streamDescriptor));
  }

  @Test
  void testTrackingRateLimitedAndRunningStatusTransition() throws IOException {
    final AirbyteMessageOrigin airbyteMessageOrigin = AirbyteMessageOrigin.SOURCE;
    final ReplicationContext replicationContext = getDefaultContext(false);

    final Instant quotaReset = Instant.now();
    final AirbyteMessage startedAirbyteMessage = createAirbyteMessage(streamDescriptor, STARTED, TIMESTAMP);
    final AirbyteMessage runningAirbyteMessage1 = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.RUNNING, TIMESTAMP);
    final AirbyteMessage rateLimitedMessage = createAirbyteMessageWithRateLimitedInfo(streamDescriptor, TIMESTAMP, quotaReset.toEpochMilli());
    final AirbyteMessage runningAirbyteMessage2 =
        createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.RUNNING, TIMESTAMP.plus(Duration.ofSeconds(1)));

    final ReplicationAirbyteMessageEvent startedEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, startedAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent runningEvent1 =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, runningAirbyteMessage1, replicationContext);
    final ReplicationAirbyteMessageEvent rateLimitedEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, rateLimitedMessage, replicationContext);
    final ReplicationAirbyteMessageEvent runningEvent2 =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, runningAirbyteMessage2, replicationContext);

    final StreamStatusUpdateRequestBody runningExpected = new StreamStatusUpdateRequestBody(
        ATTEMPT,
        CONNECTION_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.RUNNING,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        STREAM_ID,
        null,
        streamDescriptor.getNamespace(),
        null);

    final StreamStatusUpdateRequestBody runningExpected2 = new StreamStatusUpdateRequestBody(
        ATTEMPT,
        CONNECTION_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.RUNNING,
        streamDescriptor.getName(),
        TIMESTAMP.plus(Duration.ofSeconds(1)).toMillis(),
        WORKSPACE_ID,
        STREAM_ID,
        null,
        streamDescriptor.getNamespace(),
        null);

    final StreamStatusUpdateRequestBody rateLimitedExpected = new StreamStatusUpdateRequestBody(
        ATTEMPT,
        CONNECTION_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.RATE_LIMITED,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        STREAM_ID,
        null,
        streamDescriptor.getNamespace(),
        new StreamStatusRateLimitedMetadata(quotaReset.toEpochMilli()));

    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext.getWorkspaceId(), replicationContext.getConnectionId(), replicationContext.getJobId(), replicationContext.getAttempt());

    when(streamStatusesApi.createStreamStatus(any())).thenReturn(new StreamStatusRead(
        ATTEMPT,
        CONNECTION_ID,
        STREAM_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.RATE_LIMITED,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        null,
        streamDescriptor.getNamespace(),
        new StreamStatusRateLimitedMetadata(quotaReset.toEpochMilli())));
    when(airbyteApiClient.getStreamStatusesApi()).thenReturn(streamStatusesApi);

    streamStatusTracker.track(startedEvent);
    streamStatusTracker.track(runningEvent1);
    streamStatusTracker.track(rateLimitedEvent);
    streamStatusTracker.track(runningEvent2);

    assertEquals(RUNNING, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    verify(streamStatusesApi, times(1)).createStreamStatus(any(StreamStatusCreateRequestBody.class));
    verify(streamStatusesApi).updateStreamStatus(runningExpected);
    verify(streamStatusesApi).updateStreamStatus(rateLimitedExpected);
    verify(streamStatusesApi).updateStreamStatus(runningExpected2);
    verify(streamStatusesApi, times(3)).updateStreamStatus(any(StreamStatusUpdateRequestBody.class));
    assertFalse(streamStatusTracker.getStreamsInRateLimitedStatus().contains(streamDescriptor));
  }

  @Test
  void testTrackingRunningStatus() throws IOException {
    final AirbyteMessageOrigin airbyteMessageOrigin = AirbyteMessageOrigin.SOURCE;
    final AirbyteMessage startedAirbyteMessage = createAirbyteMessage(streamDescriptor, STARTED, TIMESTAMP);
    final AirbyteMessage runningAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.RUNNING, TIMESTAMP);
    final ReplicationContext replicationContext = getDefaultContext(false);
    final ReplicationAirbyteMessageEvent startedEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, startedAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent runningEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, runningAirbyteMessage, replicationContext);
    final StreamStatusUpdateRequestBody expected = new StreamStatusUpdateRequestBody(
        ATTEMPT,
        CONNECTION_ID,
        JOB_ID,
        StreamStatusJobType.SYNC,
        StreamStatusRunState.RUNNING,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        STREAM_ID,
        null,
        streamDescriptor.getNamespace(),
        null);
    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext.getWorkspaceId(), replicationContext.getConnectionId(), replicationContext.getJobId(), replicationContext.getAttempt());

    when(streamStatusesApi.createStreamStatus(any())).thenReturn(new StreamStatusRead(
        ATTEMPT,
        CONNECTION_ID,
        STREAM_ID,
        JOB_ID,
        StreamStatusJobType.SYNC,
        StreamStatusRunState.RUNNING,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        null,
        streamDescriptor.getNamespace(),
        null));
    when(airbyteApiClient.getStreamStatusesApi()).thenReturn(streamStatusesApi);

    streamStatusTracker.track(startedEvent);
    streamStatusTracker.track(runningEvent);

    assertEquals(AirbyteStreamStatus.RUNNING, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    verify(streamStatusesApi, times(1)).createStreamStatus(any(StreamStatusCreateRequestBody.class));
    verify(streamStatusesApi, times(1)).updateStreamStatus(expected);
  }

  @Test
  void testTrackingCompleteSourceOnly() throws IOException {
    final AirbyteMessageOrigin airbyteMessageOrigin = AirbyteMessageOrigin.SOURCE;
    final AirbyteMessage startedAirbyteMessage = createAirbyteMessage(streamDescriptor, STARTED, TIMESTAMP);
    final AirbyteMessage runningAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.RUNNING, TIMESTAMP);
    final AirbyteMessage sourceCompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, COMPLETE, TIMESTAMP);
    final ReplicationContext replicationContext = getDefaultContext(false);
    final ReplicationAirbyteMessageEvent startedEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, startedAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent runningEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, runningAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent sourceEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, sourceCompleteAirbyteMessage, replicationContext);
    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext.getWorkspaceId(), replicationContext.getConnectionId(), replicationContext.getJobId(), replicationContext.getAttempt());

    when(streamStatusesApi.createStreamStatus(any())).thenReturn(new StreamStatusRead(
        ATTEMPT,
        CONNECTION_ID,
        STREAM_ID,
        JOB_ID,
        StreamStatusJobType.SYNC,
        StreamStatusRunState.RUNNING,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        null,
        streamDescriptor.getNamespace(),
        null));
    when(airbyteApiClient.getStreamStatusesApi()).thenReturn(streamStatusesApi);

    streamStatusTracker.track(startedEvent);
    streamStatusTracker.track(runningEvent);
    streamStatusTracker.track(sourceEvent);
    assertEquals(COMPLETE, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    verify(streamStatusesApi, times(1)).createStreamStatus(any(StreamStatusCreateRequestBody.class));
    verify(streamStatusesApi, times(1)).updateStreamStatus(any(StreamStatusUpdateRequestBody.class));
  }

  @Test
  void testTrackingCompleteDestinationOnly() throws IOException {
    final AirbyteMessageOrigin airbyteMessageOrigin = AirbyteMessageOrigin.SOURCE;
    final AirbyteMessage startedAirbyteMessage = createAirbyteMessage(streamDescriptor, STARTED, TIMESTAMP);
    final AirbyteMessage runningAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.RUNNING, TIMESTAMP);
    final AirbyteMessage destinationCompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, COMPLETE, TIMESTAMP);
    final ReplicationContext replicationContext = getDefaultContext(false);
    final ReplicationAirbyteMessageEvent startedEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, startedAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent runningEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, runningAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent destinationEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.DESTINATION, destinationCompleteAirbyteMessage, replicationContext);
    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext.getWorkspaceId(), replicationContext.getConnectionId(), replicationContext.getJobId(), replicationContext.getAttempt());

    when(streamStatusesApi.createStreamStatus(any())).thenReturn(new StreamStatusRead(
        ATTEMPT,
        CONNECTION_ID,
        STREAM_ID,
        JOB_ID,
        StreamStatusJobType.SYNC,
        StreamStatusRunState.COMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        null,
        streamDescriptor.getNamespace(),
        null));
    when(airbyteApiClient.getStreamStatusesApi()).thenReturn(streamStatusesApi);

    streamStatusTracker.track(startedEvent);
    streamStatusTracker.track(runningEvent);
    streamStatusTracker.track(destinationEvent);
    assertEquals(COMPLETE, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    verify(streamStatusesApi, times(1)).createStreamStatus(any(StreamStatusCreateRequestBody.class));
    verify(streamStatusesApi, times(1)).updateStreamStatus(any(StreamStatusUpdateRequestBody.class));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testTrackingCompleteSourceAndCompleteDestination(final boolean isReset) throws IOException {
    final AirbyteMessageOrigin airbyteMessageOrigin = AirbyteMessageOrigin.SOURCE;
    final AirbyteMessage startedAirbyteMessage = createAirbyteMessage(streamDescriptor, STARTED, TIMESTAMP);
    final AirbyteMessage runningAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.RUNNING, TIMESTAMP);
    final AirbyteMessage destinationCompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, COMPLETE, TIMESTAMP);
    final AirbyteMessage sourceCompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, COMPLETE, TIMESTAMP);
    final ReplicationContext replicationContext = getDefaultContext(isReset);
    final ReplicationAirbyteMessageEvent startedEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, startedAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent runningEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, runningAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent destinationEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.DESTINATION, destinationCompleteAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent sourceEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, sourceCompleteAirbyteMessage, replicationContext);
    final StreamStatusUpdateRequestBody expected = new StreamStatusUpdateRequestBody(
        ATTEMPT,
        CONNECTION_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.COMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        STREAM_ID,
        null,
        streamDescriptor.getNamespace(),
        null);
    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext.getWorkspaceId(), replicationContext.getConnectionId(), replicationContext.getJobId(), replicationContext.getAttempt());

    when(streamStatusesApi.createStreamStatus(any())).thenReturn(new StreamStatusRead(
        ATTEMPT,
        CONNECTION_ID,
        STREAM_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.COMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        null,
        streamDescriptor.getNamespace(),
        null));
    when(airbyteApiClient.getStreamStatusesApi()).thenReturn(streamStatusesApi);

    streamStatusTracker.track(startedEvent);
    streamStatusTracker.track(runningEvent);
    streamStatusTracker.track(sourceEvent);
    assertEquals(COMPLETE, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    streamStatusTracker.track(destinationEvent);
    assertEquals(COMPLETE, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    verify(streamStatusesApi, times(1)).createStreamStatus(any(StreamStatusCreateRequestBody.class));
    verify(streamStatusesApi, times(2)).updateStreamStatus(updateArgumentCaptor.capture());

    final StreamStatusUpdateRequestBody result = updateArgumentCaptor.getAllValues().get(updateArgumentCaptor.getAllValues().size() - 1);
    assertEquals(expected, result);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testTrackingCompleteDestinationAndCompleteSource(final boolean isReset) throws IOException {
    final AirbyteMessageOrigin airbyteMessageOrigin = AirbyteMessageOrigin.SOURCE;
    final AirbyteMessage startedAirbyteMessage = createAirbyteMessage(streamDescriptor, STARTED, TIMESTAMP);
    final AirbyteMessage runningAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.RUNNING, TIMESTAMP);
    final AirbyteMessage destinationCompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, COMPLETE, TIMESTAMP);
    final AirbyteMessage sourceCompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, COMPLETE, TIMESTAMP);
    final ReplicationContext replicationContext = getDefaultContext(isReset);
    final ReplicationAirbyteMessageEvent startedEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, startedAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent runningEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, runningAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent destinationEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.DESTINATION, destinationCompleteAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent sourceEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, sourceCompleteAirbyteMessage, replicationContext);
    final StreamStatusUpdateRequestBody expected = new StreamStatusUpdateRequestBody(
        ATTEMPT,
        CONNECTION_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.COMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        STREAM_ID,
        null,
        streamDescriptor.getNamespace(),
        null);
    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext.getWorkspaceId(), replicationContext.getConnectionId(), replicationContext.getJobId(), replicationContext.getAttempt());

    when(streamStatusesApi.createStreamStatus(any())).thenReturn(new StreamStatusRead(
        ATTEMPT,
        CONNECTION_ID,
        STREAM_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.COMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        null,
        streamDescriptor.getNamespace(),
        null));
    when(airbyteApiClient.getStreamStatusesApi()).thenReturn(streamStatusesApi);

    streamStatusTracker.track(startedEvent);
    assertEquals(STARTED, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));

    streamStatusTracker.track(runningEvent);
    assertEquals(RUNNING, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));

    streamStatusTracker.track(destinationEvent);
    assertEquals(COMPLETE, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));

    streamStatusTracker.track(sourceEvent);
    assertEquals(COMPLETE, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    verify(streamStatusesApi, times(1)).createStreamStatus(any(StreamStatusCreateRequestBody.class));
    verify(streamStatusesApi, times(2)).updateStreamStatus(updateArgumentCaptor.capture());

    final StreamStatusUpdateRequestBody result = updateArgumentCaptor.getValue();
    assertEquals(expected, result);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testTrackingIncompleteSourceOnly(final boolean isReset) throws IOException {
    final AirbyteMessageOrigin airbyteMessageOrigin = AirbyteMessageOrigin.SOURCE;
    final AirbyteMessage startedAirbyteMessage = createAirbyteMessage(streamDescriptor, STARTED, TIMESTAMP);
    final AirbyteMessage runningAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.RUNNING, TIMESTAMP);
    final AirbyteMessage sourceIncompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.INCOMPLETE, TIMESTAMP);
    final ReplicationContext replicationContext = getDefaultContext(isReset);
    final var incompleteRunCause = StreamStatusIncompleteRunCause.FAILED;
    final ReplicationAirbyteMessageEvent startedEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, startedAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent runningEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, runningAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent sourceEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, sourceIncompleteAirbyteMessage, replicationContext, incompleteRunCause);
    final StreamStatusUpdateRequestBody expected = new StreamStatusUpdateRequestBody(
        ATTEMPT,
        CONNECTION_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.INCOMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        STREAM_ID,
        incompleteRunCause,
        streamDescriptor.getNamespace(),
        null);
    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext.getWorkspaceId(), replicationContext.getConnectionId(), replicationContext.getJobId(), replicationContext.getAttempt());

    when(streamStatusesApi.createStreamStatus(any())).thenReturn(new StreamStatusRead(
        ATTEMPT,
        CONNECTION_ID,
        STREAM_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.INCOMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        incompleteRunCause,
        streamDescriptor.getNamespace(),
        null));
    when(airbyteApiClient.getStreamStatusesApi()).thenReturn(streamStatusesApi);

    streamStatusTracker.track(startedEvent);
    streamStatusTracker.track(runningEvent);
    streamStatusTracker.track(sourceEvent);
    assertEquals(INCOMPLETE, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    verify(streamStatusesApi, times(1)).createStreamStatus(any(StreamStatusCreateRequestBody.class));
    verify(streamStatusesApi, times(2)).updateStreamStatus(updateArgumentCaptor.capture());

    final StreamStatusUpdateRequestBody result = updateArgumentCaptor.getAllValues().get(updateArgumentCaptor.getAllValues().size() - 1);
    assertEquals(expected, result);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testTrackingIncompleteDestinationOnly(final boolean isReset) throws IOException {
    final AirbyteMessageOrigin airbyteMessageOrigin = AirbyteMessageOrigin.SOURCE;
    final AirbyteMessage startedAirbyteMessage = createAirbyteMessage(streamDescriptor, STARTED, TIMESTAMP);
    final AirbyteMessage runningAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.RUNNING, TIMESTAMP);
    final AirbyteMessage destinationIncompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.INCOMPLETE, TIMESTAMP);
    final ReplicationContext replicationContext = getDefaultContext(isReset);
    final var incompleteRunCause = StreamStatusIncompleteRunCause.FAILED;
    final ReplicationAirbyteMessageEvent startedEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, startedAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent runningEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, runningAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent destinationEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.DESTINATION, destinationIncompleteAirbyteMessage, replicationContext,
            incompleteRunCause);
    final StreamStatusUpdateRequestBody expected = new StreamStatusUpdateRequestBody(
        ATTEMPT,
        CONNECTION_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.INCOMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        STREAM_ID,
        incompleteRunCause,
        streamDescriptor.getNamespace(),
        null);
    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext.getWorkspaceId(), replicationContext.getConnectionId(), replicationContext.getJobId(), replicationContext.getAttempt());

    when(streamStatusesApi.createStreamStatus(any())).thenReturn(new StreamStatusRead(
        ATTEMPT,
        CONNECTION_ID,
        STREAM_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.INCOMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        null,
        streamDescriptor.getNamespace(),
        null));
    when(airbyteApiClient.getStreamStatusesApi()).thenReturn(streamStatusesApi);

    streamStatusTracker.track(startedEvent);
    streamStatusTracker.track(runningEvent);
    streamStatusTracker.track(destinationEvent);
    assertEquals(INCOMPLETE, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    verify(streamStatusesApi, times(1)).createStreamStatus(any(StreamStatusCreateRequestBody.class));
    verify(streamStatusesApi, times(2)).updateStreamStatus(updateArgumentCaptor.capture());

    final StreamStatusUpdateRequestBody result = updateArgumentCaptor.getAllValues().get(updateArgumentCaptor.getAllValues().size() - 1);
    assertEquals(expected, result);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testTrackingIncompleteSourceAndIncompleteDestination(final boolean isReset) throws IOException {
    final AirbyteMessageOrigin airbyteMessageOrigin = AirbyteMessageOrigin.SOURCE;
    final AirbyteMessage startedAirbyteMessage = createAirbyteMessage(streamDescriptor, STARTED, TIMESTAMP);
    final AirbyteMessage runningAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.RUNNING, TIMESTAMP);
    final AirbyteMessage destinationIncompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.INCOMPLETE, TIMESTAMP);
    final AirbyteMessage sourceIncompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.INCOMPLETE, TIMESTAMP);
    final ReplicationContext replicationContext = getDefaultContext(isReset);
    final var incompleteRunCause = StreamStatusIncompleteRunCause.FAILED;
    final ReplicationAirbyteMessageEvent startedEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, startedAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent runningEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, runningAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent destinationEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.DESTINATION, destinationIncompleteAirbyteMessage, replicationContext,
            incompleteRunCause);
    final ReplicationAirbyteMessageEvent sourceEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, sourceIncompleteAirbyteMessage, replicationContext, incompleteRunCause);
    final StreamStatusUpdateRequestBody expected = new StreamStatusUpdateRequestBody(
        ATTEMPT,
        CONNECTION_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.INCOMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        STREAM_ID,
        incompleteRunCause,
        streamDescriptor.getNamespace(),
        null);
    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext.getWorkspaceId(), replicationContext.getConnectionId(), replicationContext.getJobId(), replicationContext.getAttempt());

    when(streamStatusesApi.createStreamStatus(any())).thenReturn(new StreamStatusRead(
        ATTEMPT,
        CONNECTION_ID,
        STREAM_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.INCOMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        incompleteRunCause,
        streamDescriptor.getNamespace(),
        null));
    when(airbyteApiClient.getStreamStatusesApi()).thenReturn(streamStatusesApi);

    streamStatusTracker.track(startedEvent);
    streamStatusTracker.track(runningEvent);
    streamStatusTracker.track(sourceEvent);
    assertEquals(INCOMPLETE, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    streamStatusTracker.track(destinationEvent);
    assertEquals(INCOMPLETE, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    verify(streamStatusesApi, times(1)).createStreamStatus(any(StreamStatusCreateRequestBody.class));
    verify(streamStatusesApi, times(2)).updateStreamStatus(updateArgumentCaptor.capture());

    final StreamStatusUpdateRequestBody result = updateArgumentCaptor.getAllValues().get(updateArgumentCaptor.getAllValues().size() - 1);
    assertEquals(expected, result);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testTrackingIncompleteDestinationAndIncompleteSource(final boolean isReset) throws IOException {
    final AirbyteMessageOrigin airbyteMessageOrigin = AirbyteMessageOrigin.SOURCE;
    final AirbyteMessage startedAirbyteMessage = createAirbyteMessage(streamDescriptor, STARTED, TIMESTAMP);
    final AirbyteMessage runningAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.RUNNING, TIMESTAMP);
    final AirbyteMessage destinationIncompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.INCOMPLETE, TIMESTAMP);
    final AirbyteMessage sourceIncompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.INCOMPLETE, TIMESTAMP);
    final ReplicationContext replicationContext = getDefaultContext(isReset);
    final var incompleteRunCause = StreamStatusIncompleteRunCause.FAILED;
    final ReplicationAirbyteMessageEvent startedEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, startedAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent runningEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, runningAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent destinationEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.DESTINATION, destinationIncompleteAirbyteMessage, replicationContext,
            incompleteRunCause);
    final ReplicationAirbyteMessageEvent sourceEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, sourceIncompleteAirbyteMessage, replicationContext, incompleteRunCause);
    final StreamStatusUpdateRequestBody expected = new StreamStatusUpdateRequestBody(
        ATTEMPT,
        CONNECTION_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.INCOMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        STREAM_ID,
        incompleteRunCause,
        streamDescriptor.getNamespace(),
        null);
    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext.getWorkspaceId(), replicationContext.getConnectionId(), replicationContext.getJobId(), replicationContext.getAttempt());

    when(streamStatusesApi.createStreamStatus(any())).thenReturn(new StreamStatusRead(
        ATTEMPT,
        CONNECTION_ID,
        STREAM_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.INCOMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        incompleteRunCause,
        streamDescriptor.getNamespace(),
        null));
    when(airbyteApiClient.getStreamStatusesApi()).thenReturn(streamStatusesApi);

    streamStatusTracker.track(startedEvent);
    streamStatusTracker.track(runningEvent);
    streamStatusTracker.track(destinationEvent);
    assertEquals(INCOMPLETE, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    streamStatusTracker.track(sourceEvent);
    assertEquals(INCOMPLETE, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    verify(streamStatusesApi, times(1)).createStreamStatus(any(StreamStatusCreateRequestBody.class));
    verify(streamStatusesApi, times(2)).updateStreamStatus(updateArgumentCaptor.capture());

    final StreamStatusUpdateRequestBody result = updateArgumentCaptor.getAllValues().get(updateArgumentCaptor.getAllValues().size() - 1);
    assertEquals(expected, result);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testTrackingIncompleteSourceAndCompleteDestination(final boolean isReset) throws IOException {
    final AirbyteMessageOrigin airbyteMessageOrigin = AirbyteMessageOrigin.SOURCE;
    final AirbyteMessage startedAirbyteMessage = createAirbyteMessage(streamDescriptor, STARTED, TIMESTAMP);
    final AirbyteMessage runningAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.RUNNING, TIMESTAMP);
    final AirbyteMessage destinationCompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, COMPLETE, TIMESTAMP);
    final AirbyteMessage sourceIncompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.INCOMPLETE, TIMESTAMP);
    final ReplicationContext replicationContext = getDefaultContext(isReset);
    final var incompleteRunCause = StreamStatusIncompleteRunCause.FAILED;
    final ReplicationAirbyteMessageEvent startedEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, startedAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent runningEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, runningAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent destinationEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.DESTINATION, destinationCompleteAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent sourceEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, sourceIncompleteAirbyteMessage, replicationContext, incompleteRunCause);
    final StreamStatusUpdateRequestBody expected = new StreamStatusUpdateRequestBody(
        ATTEMPT,
        CONNECTION_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.INCOMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        STREAM_ID,
        incompleteRunCause,
        streamDescriptor.getNamespace(),
        null);
    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext.getWorkspaceId(), replicationContext.getConnectionId(), replicationContext.getJobId(), replicationContext.getAttempt());

    when(streamStatusesApi.createStreamStatus(any())).thenReturn(new StreamStatusRead(
        ATTEMPT,
        CONNECTION_ID,
        STREAM_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.INCOMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        incompleteRunCause,
        streamDescriptor.getNamespace(),
        null));
    when(airbyteApiClient.getStreamStatusesApi()).thenReturn(streamStatusesApi);

    streamStatusTracker.track(startedEvent);
    streamStatusTracker.track(runningEvent);
    streamStatusTracker.track(sourceEvent);
    assertEquals(INCOMPLETE, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    streamStatusTracker.track(destinationEvent);
    assertEquals(COMPLETE, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    verify(streamStatusesApi, times(1)).createStreamStatus(any(StreamStatusCreateRequestBody.class));
    verify(streamStatusesApi, times(2)).updateStreamStatus(updateArgumentCaptor.capture());

    final StreamStatusUpdateRequestBody result = updateArgumentCaptor.getAllValues().get(updateArgumentCaptor.getAllValues().size() - 1);
    assertEquals(expected, result);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testTrackingCompleteDestinationAndIncompleteSource(final boolean isReset) throws IOException {
    final AirbyteMessageOrigin airbyteMessageOrigin = AirbyteMessageOrigin.SOURCE;
    final AirbyteMessage startedAirbyteMessage = createAirbyteMessage(streamDescriptor, STARTED, TIMESTAMP);
    final AirbyteMessage runningAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.RUNNING, TIMESTAMP);
    final AirbyteMessage destinationCompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, COMPLETE, TIMESTAMP);
    final AirbyteMessage sourceIncompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.INCOMPLETE, TIMESTAMP);
    final ReplicationContext replicationContext = getDefaultContext(isReset);
    final var incompleteRunCause = StreamStatusIncompleteRunCause.FAILED;
    final ReplicationAirbyteMessageEvent startedEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, startedAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent runningEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, runningAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent destinationEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.DESTINATION, destinationCompleteAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent sourceEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, sourceIncompleteAirbyteMessage, replicationContext, incompleteRunCause);
    final StreamStatusUpdateRequestBody expected = new StreamStatusUpdateRequestBody(
        ATTEMPT,
        CONNECTION_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.INCOMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        STREAM_ID,
        incompleteRunCause,
        streamDescriptor.getNamespace(),
        null);
    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext.getWorkspaceId(), replicationContext.getConnectionId(), replicationContext.getJobId(), replicationContext.getAttempt());

    when(streamStatusesApi.createStreamStatus(any())).thenReturn(new StreamStatusRead(
        ATTEMPT,
        CONNECTION_ID,
        STREAM_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.INCOMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        incompleteRunCause,
        streamDescriptor.getNamespace(),
        null));
    when(airbyteApiClient.getStreamStatusesApi()).thenReturn(streamStatusesApi);

    streamStatusTracker.track(startedEvent);
    streamStatusTracker.track(runningEvent);
    streamStatusTracker.track(destinationEvent);
    assertEquals(COMPLETE, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    streamStatusTracker.track(sourceEvent);
    assertEquals(COMPLETE, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    verify(streamStatusesApi, times(1)).createStreamStatus(any(StreamStatusCreateRequestBody.class));
    verify(streamStatusesApi, times(2)).updateStreamStatus(updateArgumentCaptor.capture());

    final StreamStatusUpdateRequestBody result = updateArgumentCaptor.getAllValues().get(updateArgumentCaptor.getAllValues().size() - 1);
    assertEquals(expected, result);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testTrackingCompleteSourceAndIncompleteDestination(final boolean isReset) throws IOException {
    final AirbyteMessageOrigin airbyteMessageOrigin = AirbyteMessageOrigin.SOURCE;
    final AirbyteMessage startedAirbyteMessage = createAirbyteMessage(streamDescriptor, STARTED, TIMESTAMP);
    final AirbyteMessage runningAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.RUNNING, TIMESTAMP);
    final AirbyteMessage destinationIncompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.INCOMPLETE, TIMESTAMP);
    final AirbyteMessage sourceCompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, COMPLETE, TIMESTAMP);
    final ReplicationContext replicationContext = getDefaultContext(isReset);
    final var incompleteRunCause = StreamStatusIncompleteRunCause.FAILED;
    final ReplicationAirbyteMessageEvent startedEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, startedAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent runningEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, runningAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent destinationEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.DESTINATION, destinationIncompleteAirbyteMessage, replicationContext,
            incompleteRunCause);
    final ReplicationAirbyteMessageEvent sourceEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, sourceCompleteAirbyteMessage, replicationContext);
    final StreamStatusUpdateRequestBody expected = new StreamStatusUpdateRequestBody(
        ATTEMPT,
        CONNECTION_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.INCOMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        STREAM_ID,
        incompleteRunCause,
        streamDescriptor.getNamespace(),
        null);
    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext.getWorkspaceId(), replicationContext.getConnectionId(), replicationContext.getJobId(), replicationContext.getAttempt());

    when(streamStatusesApi.createStreamStatus(any())).thenReturn(new StreamStatusRead(
        ATTEMPT,
        CONNECTION_ID,
        STREAM_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.INCOMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        incompleteRunCause,
        streamDescriptor.getNamespace(),
        null));
    when(airbyteApiClient.getStreamStatusesApi()).thenReturn(streamStatusesApi);

    streamStatusTracker.track(startedEvent);
    streamStatusTracker.track(runningEvent);
    streamStatusTracker.track(sourceEvent);
    assertEquals(COMPLETE, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    streamStatusTracker.track(destinationEvent);
    assertEquals(INCOMPLETE, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    verify(streamStatusesApi, times(1)).createStreamStatus(any(StreamStatusCreateRequestBody.class));
    verify(streamStatusesApi, times(2)).updateStreamStatus(updateArgumentCaptor.capture());

    final StreamStatusUpdateRequestBody result = updateArgumentCaptor.getAllValues().get(updateArgumentCaptor.getAllValues().size() - 1);
    assertEquals(expected, result);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testTrackingIncompleteDestinationAndCompleteSource(final boolean isReset) throws IOException {
    final AirbyteMessageOrigin airbyteMessageOrigin = AirbyteMessageOrigin.SOURCE;
    final AirbyteMessage startedAirbyteMessage = createAirbyteMessage(streamDescriptor, STARTED, TIMESTAMP);
    final AirbyteMessage runningAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.RUNNING, TIMESTAMP);
    final AirbyteMessage destinationIncompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.INCOMPLETE, TIMESTAMP);
    final AirbyteMessage sourceCompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, COMPLETE, TIMESTAMP);
    final ReplicationContext replicationContext = getDefaultContext(isReset);
    final var incompleteRunCause = StreamStatusIncompleteRunCause.FAILED;
    final ReplicationAirbyteMessageEvent startedEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, startedAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent runningEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, runningAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent destinationEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.DESTINATION, destinationIncompleteAirbyteMessage, replicationContext,
            incompleteRunCause);
    final ReplicationAirbyteMessageEvent sourceEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, sourceCompleteAirbyteMessage, replicationContext);
    final StreamStatusUpdateRequestBody expected = new StreamStatusUpdateRequestBody(
        ATTEMPT,
        CONNECTION_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.INCOMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        STREAM_ID,
        incompleteRunCause,
        streamDescriptor.getNamespace(),
        null);
    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext.getWorkspaceId(), replicationContext.getConnectionId(), replicationContext.getJobId(), replicationContext.getAttempt());

    when(streamStatusesApi.createStreamStatus(any())).thenReturn(new StreamStatusRead(
        ATTEMPT,
        CONNECTION_ID,
        STREAM_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.INCOMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        incompleteRunCause,
        streamDescriptor.getNamespace(),
        null));
    when(airbyteApiClient.getStreamStatusesApi()).thenReturn(streamStatusesApi);

    streamStatusTracker.track(startedEvent);
    streamStatusTracker.track(runningEvent);
    streamStatusTracker.track(destinationEvent);
    assertEquals(INCOMPLETE, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    streamStatusTracker.track(sourceEvent);
    assertEquals(INCOMPLETE, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    verify(streamStatusesApi, times(1)).createStreamStatus(any(StreamStatusCreateRequestBody.class));
    verify(streamStatusesApi, times(2)).updateStreamStatus(updateArgumentCaptor.capture());

    final StreamStatusUpdateRequestBody result = updateArgumentCaptor.getAllValues().get(updateArgumentCaptor.getAllValues().size() - 1);
    assertEquals(expected, result);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testTrackingInternalIncomplete(final boolean isReset) throws IOException {
    final AirbyteMessageOrigin airbyteMessageOrigin = AirbyteMessageOrigin.SOURCE;
    final AirbyteMessage startedAirbyteMessage = createAirbyteMessage(streamDescriptor, STARTED, TIMESTAMP);
    final AirbyteMessage runningAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.RUNNING, TIMESTAMP);
    final AirbyteMessage sourceIncompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.INCOMPLETE, TIMESTAMP);
    final ReplicationContext replicationContext = getDefaultContext(isReset);
    final var incompleteRunCause = StreamStatusIncompleteRunCause.FAILED;
    final ReplicationAirbyteMessageEvent startedEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, startedAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent runningEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, runningAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent internalEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.INTERNAL, sourceIncompleteAirbyteMessage, replicationContext, incompleteRunCause);
    final StreamStatusUpdateRequestBody expected = new StreamStatusUpdateRequestBody(
        ATTEMPT,
        CONNECTION_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.INCOMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        STREAM_ID,
        incompleteRunCause,
        streamDescriptor.getNamespace(),
        null);
    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext.getWorkspaceId(), replicationContext.getConnectionId(), replicationContext.getJobId(), replicationContext.getAttempt());

    when(streamStatusesApi.createStreamStatus(any())).thenReturn(new StreamStatusRead(
        ATTEMPT,
        CONNECTION_ID,
        STREAM_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.INCOMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        incompleteRunCause,
        streamDescriptor.getNamespace(),
        null));
    when(airbyteApiClient.getStreamStatusesApi()).thenReturn(streamStatusesApi);

    streamStatusTracker.track(startedEvent);
    streamStatusTracker.track(runningEvent);
    streamStatusTracker.track(internalEvent);

    assertNull(streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    verify(streamStatusesApi, times(1)).createStreamStatus(any(StreamStatusCreateRequestBody.class));
    verify(streamStatusesApi, times(2)).updateStreamStatus(updateArgumentCaptor.capture());

    final StreamStatusUpdateRequestBody result = updateArgumentCaptor.getAllValues().get(updateArgumentCaptor.getAllValues().size() - 1);
    assertEquals(expected, result);
  }

  @Test
  void testTrackingOutOfOrderStartedStatus() throws IOException {
    final AirbyteMessageOrigin airbyteMessageOrigin = AirbyteMessageOrigin.SOURCE;
    final AirbyteMessage airbyteMessage = createAirbyteMessage(streamDescriptor, STARTED, TIMESTAMP);
    final ReplicationContext replicationContext = getDefaultContext(false);
    final ReplicationAirbyteMessageEvent event = new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, airbyteMessage, replicationContext);
    final StreamStatusCreateRequestBody expected = new StreamStatusCreateRequestBody(
        ATTEMPT,
        CONNECTION_ID,
        JOB_ID,
        StreamStatusJobType.SYNC,
        StreamStatusRunState.RUNNING,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        null,
        streamDescriptor.getNamespace(),
        null);
    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext.getWorkspaceId(), replicationContext.getConnectionId(), replicationContext.getJobId(), replicationContext.getAttempt());

    when(streamStatusesApi.createStreamStatus(any())).thenReturn(new StreamStatusRead(
        ATTEMPT,
        CONNECTION_ID,
        STREAM_ID,
        JOB_ID,
        StreamStatusJobType.SYNC,
        StreamStatusRunState.RUNNING,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        null,
        streamDescriptor.getNamespace(),
        null));
    when(airbyteApiClient.getStreamStatusesApi()).thenReturn(streamStatusesApi);

    streamStatusTracker.track(event);
    assertEquals(STARTED, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    streamStatusTracker.track(event);
    assertEquals(STARTED, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    verify(streamStatusesApi, times(1)).createStreamStatus(expected);
  }

  @Test
  void testTrackingOutOfOrderRunningStatus() throws IOException {
    final AirbyteMessageOrigin airbyteMessageOrigin = AirbyteMessageOrigin.SOURCE;
    final AirbyteMessage startedAirbyteMessage = createAirbyteMessage(streamDescriptor, STARTED, TIMESTAMP);
    final AirbyteMessage runningAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.RUNNING, TIMESTAMP);
    final ReplicationContext replicationContext = getDefaultContext(false);
    final ReplicationAirbyteMessageEvent startedEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, startedAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent runningEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, runningAirbyteMessage, replicationContext);
    final StreamStatusUpdateRequestBody expected = new StreamStatusUpdateRequestBody(
        ATTEMPT,
        CONNECTION_ID,
        JOB_ID,
        StreamStatusJobType.SYNC,
        StreamStatusRunState.RUNNING,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        STREAM_ID,
        null,
        streamDescriptor.getNamespace(),
        null);
    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext.getWorkspaceId(), replicationContext.getConnectionId(), replicationContext.getJobId(), replicationContext.getAttempt());

    when(streamStatusesApi.createStreamStatus(any())).thenReturn(new StreamStatusRead(
        ATTEMPT,
        CONNECTION_ID,
        STREAM_ID,
        JOB_ID,
        StreamStatusJobType.SYNC,
        StreamStatusRunState.RUNNING,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        null,
        streamDescriptor.getNamespace(),
        null));
    when(airbyteApiClient.getStreamStatusesApi()).thenReturn(streamStatusesApi);

    streamStatusTracker.track(runningEvent);
    assertNull(streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    streamStatusTracker.track(startedEvent);
    streamStatusTracker.track(runningEvent);
    assertEquals(AirbyteStreamStatus.RUNNING, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    streamStatusTracker.track(runningEvent);
    assertEquals(AirbyteStreamStatus.RUNNING, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    verify(streamStatusesApi, times(1)).createStreamStatus(any(StreamStatusCreateRequestBody.class));
    verify(streamStatusesApi, times(1)).updateStreamStatus(expected);
  }

  @Test
  void testTrackingOutOfOrderCompleteStatus() throws IOException {
    final AirbyteMessageOrigin airbyteMessageOrigin = AirbyteMessageOrigin.SOURCE;
    final AirbyteMessage destinationStoppedAirbyteMessage = createAirbyteMessage(streamDescriptor, COMPLETE, TIMESTAMP);
    final AirbyteMessage sourceStoppedAirbyteMessage = createAirbyteMessage(streamDescriptor, COMPLETE, TIMESTAMP);
    final ReplicationContext replicationContext = getDefaultContext(false);
    final ReplicationAirbyteMessageEvent destinationEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, destinationStoppedAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent sourceEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, sourceStoppedAirbyteMessage, replicationContext);
    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext.getWorkspaceId(), replicationContext.getConnectionId(), replicationContext.getJobId(), replicationContext.getAttempt());

    streamStatusTracker.track(sourceEvent);
    streamStatusTracker.track(destinationEvent);

    assertNull(streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    verify(streamStatusesApi, times(0)).createStreamStatus(any(StreamStatusCreateRequestBody.class));
    verify(streamStatusesApi, times(0)).updateStreamStatus(any(StreamStatusUpdateRequestBody.class));
  }

  @Test
  void testTrackingOutOfOrderIncompleteStatus() throws IOException {
    final AirbyteMessageOrigin airbyteMessageOrigin = AirbyteMessageOrigin.SOURCE;
    final AirbyteMessage destinationStoppedAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.INCOMPLETE, TIMESTAMP);
    final AirbyteMessage sourceStoppedAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.INCOMPLETE, TIMESTAMP);
    final ReplicationContext replicationContext = getDefaultContext(false);
    final ReplicationAirbyteMessageEvent destinationEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, destinationStoppedAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent sourceEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, sourceStoppedAirbyteMessage, replicationContext);
    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext.getWorkspaceId(), replicationContext.getConnectionId(), replicationContext.getJobId(), replicationContext.getAttempt());

    streamStatusTracker.track(sourceEvent);
    streamStatusTracker.track(destinationEvent);

    assertNull(streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    verify(streamStatusesApi, times(0)).createStreamStatus(any(StreamStatusCreateRequestBody.class));
    verify(streamStatusesApi, times(0)).updateStreamStatus(any(StreamStatusUpdateRequestBody.class));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testForceCompletionRunning(final boolean isReset) throws IOException {
    final ReplicationContext replicationContext = getDefaultContext(isReset);

    final AirbyteMessage startedAirbyteMessage = createAirbyteMessage(streamDescriptor, STARTED, TIMESTAMP);
    final AirbyteMessage runningAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.RUNNING, TIMESTAMP);
    final AirbyteMessage forceCompletionMessage = createAirbyteMessage(new StreamDescriptor(), COMPLETE, TIMESTAMP);

    final ReplicationAirbyteMessageEvent startedEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.SOURCE, startedAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent runningEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.SOURCE, runningAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent forceCompletionEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.INTERNAL, forceCompletionMessage, replicationContext);
    final StreamStatusUpdateRequestBody expected = new StreamStatusUpdateRequestBody(
        ATTEMPT,
        CONNECTION_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.COMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        STREAM_ID,
        null,
        streamDescriptor.getNamespace(),
        null);
    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext.getWorkspaceId(), replicationContext.getConnectionId(), replicationContext.getJobId(), replicationContext.getAttempt());

    when(streamStatusesApi.createStreamStatus(any())).thenReturn(new StreamStatusRead(
        ATTEMPT,
        CONNECTION_ID,
        STREAM_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.COMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        null,
        streamDescriptor.getNamespace(),
        null));
    when(airbyteApiClient.getStreamStatusesApi()).thenReturn(streamStatusesApi);

    streamStatusTracker.track(startedEvent);
    streamStatusTracker.track(runningEvent);
    streamStatusTracker.track(forceCompletionEvent);

    assertNull(streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    verify(streamStatusesApi, times(1)).updateStreamStatus(expected);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testForceCompletionPartiallyComplete(final boolean isReset) throws IOException {
    final ReplicationContext replicationContext = getDefaultContext(isReset);

    final AirbyteMessage startedAirbyteMessage = createAirbyteMessage(streamDescriptor, STARTED, TIMESTAMP);
    final AirbyteMessage runningAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.RUNNING, TIMESTAMP);
    final AirbyteMessage sourceCompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, COMPLETE, TIMESTAMP);
    final AirbyteMessage forceCompletionMessage = createAirbyteMessage(new StreamDescriptor(), COMPLETE, TIMESTAMP);

    final ReplicationAirbyteMessageEvent startedEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.SOURCE, startedAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent runningEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.SOURCE, runningAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent sourceCompletedEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.SOURCE, sourceCompleteAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent forceCompletionEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.INTERNAL, forceCompletionMessage, replicationContext);
    final StreamStatusUpdateRequestBody expected = new StreamStatusUpdateRequestBody(
        ATTEMPT,
        CONNECTION_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.COMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        STREAM_ID,
        null,
        streamDescriptor.getNamespace(),
        null);
    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext.getWorkspaceId(), replicationContext.getConnectionId(), replicationContext.getJobId(), replicationContext.getAttempt());

    when(streamStatusesApi.createStreamStatus(any())).thenReturn(new StreamStatusRead(
        ATTEMPT,
        CONNECTION_ID,
        STREAM_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.COMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        null,
        streamDescriptor.getNamespace(),
        null));
    when(airbyteApiClient.getStreamStatusesApi()).thenReturn(streamStatusesApi);

    streamStatusTracker.track(startedEvent);
    streamStatusTracker.track(runningEvent);
    streamStatusTracker.track(sourceCompletedEvent);

    assertFalse(streamStatusTracker.getCurrentStreamStatus(streamStatusKey).isComplete());

    streamStatusTracker.track(forceCompletionEvent);

    assertNull(streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    verify(streamStatusesApi, times(1)).updateStreamStatus(expected);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testForceCompletionAlreadyIncomplete(final boolean isReset) throws IOException {
    final ReplicationContext replicationContext = getDefaultContext(isReset);

    final AirbyteMessage startedAirbyteMessage = createAirbyteMessage(streamDescriptor, STARTED, TIMESTAMP);
    final AirbyteMessage runningAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.RUNNING, TIMESTAMP);
    final AirbyteMessage sourceIncompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.INCOMPLETE, TIMESTAMP);
    final AirbyteMessage destinationCompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, COMPLETE, TIMESTAMP);
    final AirbyteMessage forceCompletionMessage = createAirbyteMessage(new StreamDescriptor(), COMPLETE, TIMESTAMP);

    final ReplicationAirbyteMessageEvent startedEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.SOURCE, startedAirbyteMessage, replicationContext);
    final var incompleteRunCause = StreamStatusIncompleteRunCause.FAILED;
    final ReplicationAirbyteMessageEvent runningEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.SOURCE, runningAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent sourceIncompletedEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.SOURCE, sourceIncompleteAirbyteMessage, replicationContext, incompleteRunCause);
    final ReplicationAirbyteMessageEvent destinationCompletedEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.DESTINATION, destinationCompleteAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent forceCompletionEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.INTERNAL, forceCompletionMessage, replicationContext);
    final StreamStatusUpdateRequestBody expected = new StreamStatusUpdateRequestBody(
        ATTEMPT,
        CONNECTION_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.INCOMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        STREAM_ID,
        incompleteRunCause,
        streamDescriptor.getNamespace(),
        null);
    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext.getWorkspaceId(), replicationContext.getConnectionId(), replicationContext.getJobId(), replicationContext.getAttempt());

    when(streamStatusesApi.createStreamStatus(any())).thenReturn(new StreamStatusRead(
        ATTEMPT,
        CONNECTION_ID,
        STREAM_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.INCOMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        null,
        streamDescriptor.getNamespace(),
        null));
    when(airbyteApiClient.getStreamStatusesApi()).thenReturn(streamStatusesApi);

    streamStatusTracker.track(startedEvent);
    streamStatusTracker.track(runningEvent);
    streamStatusTracker.track(sourceIncompletedEvent);
    streamStatusTracker.track(destinationCompletedEvent);
    streamStatusTracker.track(forceCompletionEvent);

    assertNull(streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    verify(streamStatusesApi, times(1)).updateStreamStatus(expected);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testForceCompletionAlreadyComplete(final boolean isReset) throws IOException {
    final ReplicationContext replicationContext = getDefaultContext(isReset);

    final AirbyteMessage startedAirbyteMessage = createAirbyteMessage(streamDescriptor, STARTED, TIMESTAMP);
    final AirbyteMessage runningAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.RUNNING, TIMESTAMP);
    final AirbyteMessage sourceCompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, COMPLETE, TIMESTAMP);
    final AirbyteMessage destinationCompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, COMPLETE, TIMESTAMP);
    final AirbyteMessage forceCompletionMessage = createAirbyteMessage(new StreamDescriptor(), COMPLETE, TIMESTAMP);

    final ReplicationAirbyteMessageEvent startedEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.SOURCE, startedAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent runningEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.SOURCE, runningAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent sourceCompletedEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.SOURCE, sourceCompleteAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent destinationCompletedEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.DESTINATION, destinationCompleteAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent forceCompletionEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.INTERNAL, forceCompletionMessage, replicationContext);
    final StreamStatusUpdateRequestBody expected = new StreamStatusUpdateRequestBody(
        ATTEMPT,
        CONNECTION_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.COMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        STREAM_ID,
        null,
        streamDescriptor.getNamespace(),
        null);
    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext.getWorkspaceId(), replicationContext.getConnectionId(), replicationContext.getJobId(), replicationContext.getAttempt());

    when(streamStatusesApi.createStreamStatus(any())).thenReturn(new StreamStatusRead(
        ATTEMPT,
        CONNECTION_ID,
        STREAM_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.COMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        null,
        streamDescriptor.getNamespace(),
        null));
    when(airbyteApiClient.getStreamStatusesApi()).thenReturn(streamStatusesApi);

    streamStatusTracker.track(startedEvent);
    streamStatusTracker.track(runningEvent);
    streamStatusTracker.track(sourceCompletedEvent);
    streamStatusTracker.track(destinationCompletedEvent);
    streamStatusTracker.track(forceCompletionEvent);

    assertNull(streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    verify(streamStatusesApi, times(1)).updateStreamStatus(expected);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testForceCompletionDifferentConnectionId(final boolean isReset) throws IOException {
    final Integer attempt = 2;
    final Long jobId = 2L;
    final UUID connectionId = UUID.randomUUID();
    final ReplicationContext replicationContext1 = getDefaultContext(isReset);
    final ReplicationContext replicationContext2 =
        new ReplicationContext(isReset, connectionId, UUID.randomUUID(), UUID.randomUUID(), jobId, attempt, WORKSPACE_ID, SOURCE_IMAGE,
            DESTINATION_IMAGE, SOURCE_DEFINITION_ID, DESTINATION_DEFINITION_ID);

    final AirbyteMessage startedAirbyteMessage = createAirbyteMessage(streamDescriptor, STARTED, TIMESTAMP);
    final AirbyteMessage runningAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.RUNNING, TIMESTAMP);
    final AirbyteMessage forceCompletionMessage = createAirbyteMessage(new StreamDescriptor(), COMPLETE, TIMESTAMP);

    final ReplicationAirbyteMessageEvent startedEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.SOURCE, startedAirbyteMessage, replicationContext1);
    final ReplicationAirbyteMessageEvent runningEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.SOURCE, runningAirbyteMessage, replicationContext1);
    final ReplicationAirbyteMessageEvent forceCompletionEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.INTERNAL, forceCompletionMessage, replicationContext2);
    final StreamStatusUpdateRequestBody expected = new StreamStatusUpdateRequestBody(
        attempt,
        connectionId,
        jobId,
        OldStreamStatusTrackerKt.jobType(replicationContext1),
        StreamStatusRunState.COMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        STREAM_ID,
        null,
        streamDescriptor.getNamespace(),
        null);
    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext1.getWorkspaceId(), replicationContext1.getConnectionId(), replicationContext1.getJobId(),
        replicationContext1.getAttempt());

    when(streamStatusesApi.createStreamStatus(any())).thenReturn(new StreamStatusRead(
        attempt,
        connectionId,
        STREAM_ID,
        jobId,
        OldStreamStatusTrackerKt.jobType(replicationContext1),
        StreamStatusRunState.COMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        null,
        streamDescriptor.getNamespace(),
        null));
    when(airbyteApiClient.getStreamStatusesApi()).thenReturn(streamStatusesApi);

    streamStatusTracker.track(startedEvent);
    streamStatusTracker.track(runningEvent);
    streamStatusTracker.track(forceCompletionEvent);

    assertNotNull(streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    verify(streamStatusesApi, times(0)).updateStreamStatus(expected);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testForceCompletionHandleException(final boolean isReset) throws IOException {
    final ReplicationContext replicationContext = getDefaultContext(isReset);

    final AirbyteMessage startedAirbyteMessage = createAirbyteMessage(streamDescriptor, STARTED, TIMESTAMP);
    final AirbyteMessage forceCompletionMessage = createAirbyteMessage(new StreamDescriptor(), COMPLETE, TIMESTAMP);

    final ReplicationAirbyteMessageEvent startedEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.SOURCE, startedAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent forceCompletionEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.INTERNAL, forceCompletionMessage, replicationContext);
    final StreamStatusUpdateRequestBody expected = new StreamStatusUpdateRequestBody(
        ATTEMPT,
        CONNECTION_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.COMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        STREAM_ID,
        null,
        streamDescriptor.getNamespace(),
        null);
    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext.getWorkspaceId(), replicationContext.getConnectionId(), replicationContext.getJobId(), replicationContext.getAttempt());

    when(streamStatusesApi.createStreamStatus(any())).thenReturn(new StreamStatusRead(
        ATTEMPT,
        CONNECTION_ID,
        STREAM_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.COMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        null,
        streamDescriptor.getNamespace(),
        null));
    when(airbyteApiClient.getStreamStatusesApi()).thenReturn(streamStatusesApi);

    assertDoesNotThrow(() -> {
      streamStatusTracker.track(startedEvent);
      streamStatusTracker.track(forceCompletionEvent);
      assertNull(streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
      verify(streamStatusesApi, times(1)).updateStreamStatus(expected);
    });
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void incompleteEventsWithNoRunCauseDefaultToFailed(final boolean isReset) throws IOException {
    final AirbyteMessageOrigin airbyteMessageOrigin = AirbyteMessageOrigin.SOURCE;
    final AirbyteMessage startedAirbyteMessage = createAirbyteMessage(streamDescriptor, STARTED, TIMESTAMP);
    final AirbyteMessage runningAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.RUNNING, TIMESTAMP);
    final AirbyteMessage destinationIncompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.INCOMPLETE, TIMESTAMP);
    final AirbyteMessage sourceIncompleteAirbyteMessage = createAirbyteMessage(streamDescriptor, AirbyteStreamStatus.INCOMPLETE, TIMESTAMP);
    final ReplicationContext replicationContext = getDefaultContext(isReset);
    final ReplicationAirbyteMessageEvent startedEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, startedAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent runningEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, runningAirbyteMessage, replicationContext);
    final ReplicationAirbyteMessageEvent destinationEvent =
        new ReplicationAirbyteMessageEvent(AirbyteMessageOrigin.DESTINATION, destinationIncompleteAirbyteMessage, replicationContext,
            null);
    final ReplicationAirbyteMessageEvent sourceEvent =
        new ReplicationAirbyteMessageEvent(airbyteMessageOrigin, sourceIncompleteAirbyteMessage, replicationContext, null);
    final StreamStatusUpdateRequestBody expected = new StreamStatusUpdateRequestBody(
        ATTEMPT,
        CONNECTION_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.INCOMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        STREAM_ID,
        StreamStatusIncompleteRunCause.FAILED,
        streamDescriptor.getNamespace(),
        null);
    final StreamStatusKey streamStatusKey = new StreamStatusKey(streamDescriptor.getName(), streamDescriptor.getNamespace(),
        replicationContext.getWorkspaceId(), replicationContext.getConnectionId(), replicationContext.getJobId(), replicationContext.getAttempt());

    when(streamStatusesApi.createStreamStatus(any())).thenReturn(new StreamStatusRead(
        ATTEMPT,
        CONNECTION_ID,
        STREAM_ID,
        JOB_ID,
        OldStreamStatusTrackerKt.jobType(replicationContext),
        StreamStatusRunState.INCOMPLETE,
        streamDescriptor.getName(),
        TIMESTAMP.toMillis(),
        WORKSPACE_ID,
        StreamStatusIncompleteRunCause.FAILED,
        streamDescriptor.getNamespace(),
        null));
    when(airbyteApiClient.getStreamStatusesApi()).thenReturn(streamStatusesApi);

    streamStatusTracker.track(startedEvent);
    streamStatusTracker.track(runningEvent);
    streamStatusTracker.track(sourceEvent);
    assertEquals(INCOMPLETE, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    streamStatusTracker.track(destinationEvent);
    assertEquals(INCOMPLETE, streamStatusTracker.getAirbyteStreamStatus(streamStatusKey));
    verify(streamStatusesApi, times(1)).createStreamStatus(any(StreamStatusCreateRequestBody.class));
    verify(streamStatusesApi, times(2)).updateStreamStatus(updateArgumentCaptor.capture());

    final StreamStatusUpdateRequestBody result = updateArgumentCaptor.getAllValues().get(updateArgumentCaptor.getAllValues().size() - 1);
    assertEquals(expected, result);
  }

  private AirbyteMessage createAirbyteMessage(final StreamDescriptor streamDescriptor, final AirbyteStreamStatus status, final Duration timestamp) {
    final AirbyteStreamStatusTraceMessage statusTraceMessage =
        new AirbyteStreamStatusTraceMessage().withStreamDescriptor(streamDescriptor).withStatus(status);
    final AirbyteTraceMessage traceMessage = new AirbyteTraceMessage().withType(AirbyteTraceMessage.Type.STREAM_STATUS)
        .withStreamStatus(statusTraceMessage).withEmittedAt(Long.valueOf(timestamp.toMillis()).doubleValue());
    return new AirbyteMessage().withType(Type.TRACE).withTrace(traceMessage);
  }

  private AirbyteMessage createAirbyteMessageWithRateLimitedInfo(final StreamDescriptor streamDescriptor,
                                                                 final Duration timestamp,
                                                                 final Long quotaRest) {
    final AirbyteMessage airbyteMessage = createAirbyteMessage(streamDescriptor, RUNNING, timestamp);
    airbyteMessage.getTrace().getStreamStatus().setReasons(Collections.singletonList(
        new AirbyteStreamStatusReason()
            .withType(AirbyteStreamStatusReason.AirbyteStreamStatusReasonType.RATE_LIMITED)
            .withRateLimited(new AirbyteStreamStatusRateLimitedReason().withQuotaReset(quotaRest))));
    return airbyteMessage;
  }

  private ReplicationContext getDefaultContext(boolean isReset) {
    return new ReplicationContext(isReset,
        CONNECTION_ID,
        DESTINATION_ID,
        SOURCE_ID,
        JOB_ID,
        ATTEMPT,
        WORKSPACE_ID,
        SOURCE_IMAGE,
        DESTINATION_IMAGE,
        SOURCE_DEFINITION_ID,
        DESTINATION_DEFINITION_ID);
  }

}
