/*
 * Copyright (c) 2020-2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.persistence.job.errorreporter;

import com.google.common.collect.ImmutableSet;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.airbyte.commons.lang.Exceptions;
import io.airbyte.commons.map.MoreMaps;
import io.airbyte.config.ActorDefinitionVersion;
import io.airbyte.config.AttemptFailureSummary;
import io.airbyte.config.Configs.DeploymentMode;
import io.airbyte.config.FailureReason;
import io.airbyte.config.FailureReason.FailureOrigin;
import io.airbyte.config.FailureReason.FailureType;
import io.airbyte.config.ReleaseStage;
import io.airbyte.config.StandardDestinationDefinition;
import io.airbyte.config.StandardSourceDefinition;
import io.airbyte.config.StandardWorkspace;
import io.airbyte.config.persistence.ActorDefinitionVersionHelper;
import io.airbyte.config.persistence.ConfigNotFoundException;
import io.airbyte.config.persistence.ConfigRepository;
import io.airbyte.persistence.job.WebUrlHelper;
import io.airbyte.validation.json.JsonValidationException;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Report errors from Jobs. Common error information that can be sent to any of the reporting
 * clients that we support.
 */
public class JobErrorReporter {

  private static final Logger LOGGER = LoggerFactory.getLogger(JobErrorReporter.class);
  private static final String FROM_TRACE_MESSAGE = "from_trace_message";
  private static final String DEPLOYMENT_MODE_META_KEY = "deployment_mode";
  private static final String AIRBYTE_VERSION_META_KEY = "airbyte_version";
  private static final String FAILURE_ORIGIN_META_KEY = "failure_origin";
  private static final String FAILURE_TYPE_META_KEY = "failure_type";
  private static final String WORKSPACE_ID_META_KEY = "workspace_id";
  private static final String WORKSPACE_URL_META_KEY = "workspace_url";
  private static final String CONNECTION_ID_META_KEY = "connection_id";
  private static final String CONNECTION_URL_META_KEY = "connection_url";
  private static final String CONNECTOR_NAME_META_KEY = "connector_name";
  private static final String CONNECTOR_REPOSITORY_META_KEY = "connector_repository";
  private static final String CONNECTOR_DEFINITION_ID_META_KEY = "connector_definition_id";
  private static final String CONNECTOR_RELEASE_STAGE_META_KEY = "connector_release_stage";
  private static final String CONNECTOR_COMMAND_META_KEY = "connector_command";
  private static final String NORMALIZATION_REPOSITORY_META_KEY = "normalization_repository";
  private static final String JOB_ID_KEY = "job_id";

  private static final ImmutableSet<FailureType> UNSUPPORTED_FAILURETYPES =
      ImmutableSet.of(FailureType.CONFIG_ERROR, FailureType.MANUAL_CANCELLATION, FailureType.TRANSIENT_ERROR);

  private final ConfigRepository configRepository;
  private final DeploymentMode deploymentMode;
  private final String airbyteVersion;
  private final WebUrlHelper webUrlHelper;
  private final JobErrorReportingClient jobErrorReportingClient;

  public JobErrorReporter(final ConfigRepository configRepository,
                          final DeploymentMode deploymentMode,
                          final String airbyteVersion,
                          final WebUrlHelper webUrlHelper,
                          final JobErrorReportingClient jobErrorReportingClient) {

    this.configRepository = configRepository;
    this.deploymentMode = deploymentMode;
    this.airbyteVersion = airbyteVersion;
    this.webUrlHelper = webUrlHelper;
    this.jobErrorReportingClient = jobErrorReportingClient;
  }

  /**
   * Reports a Sync Job's connector-caused FailureReasons to the JobErrorReportingClient.
   *
   * @param connectionId - connection that had the failure
   * @param failureSummary - final attempt failure summary
   * @param jobContext - sync job reporting context
   */
  public void reportSyncJobFailure(final UUID connectionId,
                                   final AttemptFailureSummary failureSummary,
                                   final SyncJobReportingContext jobContext,
                                   @Nullable final AttemptConfigReportingContext attemptConfig) {
    Exceptions.swallow(() -> {
      final List<FailureReason> traceMessageFailures = failureSummary.getFailures().stream()
          .filter(failure -> failure.getMetadata() != null && failure.getMetadata().getAdditionalProperties().containsKey(FROM_TRACE_MESSAGE))
          .toList();

      final StandardWorkspace workspace = configRepository.getStandardWorkspaceFromConnection(connectionId, true);
      final Map<String, String> commonMetadata = MoreMaps.merge(
          Map.of(JOB_ID_KEY, String.valueOf(jobContext.jobId())),
          getConnectionMetadata(workspace.getWorkspaceId(), connectionId));

      for (final FailureReason failureReason : traceMessageFailures) {
        final FailureOrigin failureOrigin = failureReason.getFailureOrigin();

        if (failureOrigin == FailureOrigin.SOURCE) {
          final StandardSourceDefinition sourceDefinition = configRepository.getSourceDefinitionFromConnection(connectionId);
          final ActorDefinitionVersion sourceVersion = configRepository.getActorDefinitionVersion(jobContext.sourceVersionId());
          final String dockerImage = ActorDefinitionVersionHelper.getDockerImageName(sourceVersion);
          final Map<String, String> metadata =
              MoreMaps.merge(commonMetadata, getSourceMetadata(sourceDefinition, dockerImage, sourceVersion.getReleaseStage()));

          reportJobFailureReason(workspace, failureReason, dockerImage, metadata, attemptConfig);
        } else if (failureOrigin == FailureOrigin.DESTINATION) {
          final StandardDestinationDefinition destinationDefinition = configRepository.getDestinationDefinitionFromConnection(connectionId);
          final ActorDefinitionVersion destinationVersion = configRepository.getActorDefinitionVersion(jobContext.destinationVersionId());
          final String dockerImage = ActorDefinitionVersionHelper.getDockerImageName(destinationVersion);
          final Map<String, String> metadata =
              MoreMaps.merge(commonMetadata, getDestinationMetadata(destinationDefinition, dockerImage, destinationVersion.getReleaseStage()));

          reportJobFailureReason(workspace, failureReason, dockerImage, metadata, attemptConfig);
        } else if (failureOrigin == FailureOrigin.NORMALIZATION) {
          final StandardSourceDefinition sourceDefinition = configRepository.getSourceDefinitionFromConnection(connectionId);
          final StandardDestinationDefinition destinationDefinition = configRepository.getDestinationDefinitionFromConnection(connectionId);
          final ActorDefinitionVersion destinationVersion = configRepository.getActorDefinitionVersion(jobContext.destinationVersionId());
          // null check because resets don't have sources
          final @Nullable ActorDefinitionVersion sourceVersion =
              jobContext.sourceVersionId() != null ? configRepository.getActorDefinitionVersion(jobContext.sourceVersionId()) : null;

          final Map<String, String> destinationMetadata = getDestinationMetadata(
              destinationDefinition,
              ActorDefinitionVersionHelper.getDockerImageName(destinationVersion),
              destinationVersion.getReleaseStage());

          // prefixing source keys, so we don't overlap (destination as 'true' keys since normalization runs
          // on the destination)
          final Map<String, String> sourceMetadata = sourceVersion != null
              ? prefixConnectorMetadataKeys(getSourceMetadata(
                  sourceDefinition,
                  ActorDefinitionVersionHelper.getDockerImageName(sourceVersion),
                  sourceVersion.getReleaseStage()), "source")
              : Map.of();

          // since error could be arising from source or destination or normalization itself, we want all the
          // metadata
          final Map<String, String> metadata = MoreMaps.merge(
              commonMetadata,
              getNormalizationMetadata(destinationVersion.getNormalizationConfig().getNormalizationRepository()),
              sourceMetadata,
              destinationMetadata);

          final String normalizationDockerImage =
              destinationVersion.getNormalizationConfig().getNormalizationRepository() + ":"
                  + destinationVersion.getNormalizationConfig().getNormalizationTag();

          reportJobFailureReason(workspace, failureReason, normalizationDockerImage, metadata, attemptConfig);
        }
      }
    });
  }

  /**
   * Reports a FailureReason from a connector Check job for a Source to the JobErrorReportingClient.
   *
   * @param workspaceId - workspace for which the check failed
   * @param failureReason - failure reason from the check connection job
   * @param jobContext - connector job reporting context
   */
  public void reportSourceCheckJobFailure(final UUID sourceDefinitionId,
                                          @Nullable final UUID workspaceId,
                                          final FailureReason failureReason,
                                          final ConnectorJobReportingContext jobContext)
      throws JsonValidationException, ConfigNotFoundException, IOException {
    final StandardWorkspace workspace = workspaceId != null ? configRepository.getStandardWorkspaceNoSecrets(workspaceId, true) : null;
    final StandardSourceDefinition sourceDefinition = configRepository.getStandardSourceDefinition(sourceDefinitionId);
    final Map<String, String> metadata = MoreMaps.merge(
        getSourceMetadata(sourceDefinition, jobContext.dockerImage(), jobContext.releaseStage()),
        Map.of(JOB_ID_KEY, jobContext.jobId().toString()));
  }

  /**
   * Reports a FailureReason from a connector Check job for a Destination to the
   * JobErrorReportingClient.
   *
   * @param workspaceId - workspace for which the check failed
   * @param failureReason - failure reason from the check connection job
   * @param jobContext - connector job reporting context
   */
  public void reportDestinationCheckJobFailure(final UUID destinationDefinitionId,
                                               @Nullable final UUID workspaceId,
                                               final FailureReason failureReason,
                                               final ConnectorJobReportingContext jobContext)
      throws JsonValidationException, ConfigNotFoundException, IOException {
    final StandardWorkspace workspace = workspaceId != null ? configRepository.getStandardWorkspaceNoSecrets(workspaceId, true) : null;
    final StandardDestinationDefinition destinationDefinition = configRepository.getStandardDestinationDefinition(destinationDefinitionId);
    final Map<String, String> metadata = MoreMaps.merge(
        getDestinationMetadata(destinationDefinition, jobContext.dockerImage(), jobContext.releaseStage()),
        Map.of(JOB_ID_KEY, jobContext.jobId().toString()));
  }

  /**
   * Reports a FailureReason from a connector Deploy job for a Source to the JobErrorReportingClient.
   *
   * @param workspaceId - workspace for which the Discover job failed
   * @param failureReason - failure reason from the Discover job
   * @param jobContext - connector job reporting context
   */
  public void reportDiscoverJobFailure(final UUID sourceDefinitionId,
                                       @Nullable final UUID workspaceId,
                                       final FailureReason failureReason,
                                       final ConnectorJobReportingContext jobContext)
      throws JsonValidationException, ConfigNotFoundException, IOException {
    final StandardWorkspace workspace = workspaceId != null ? configRepository.getStandardWorkspaceNoSecrets(workspaceId, true) : null;
    final StandardSourceDefinition sourceDefinition = configRepository.getStandardSourceDefinition(sourceDefinitionId);
    final Map<String, String> metadata = MoreMaps.merge(
        getSourceMetadata(sourceDefinition, jobContext.dockerImage(), jobContext.releaseStage()),
        Map.of(JOB_ID_KEY, jobContext.jobId().toString()));
  }

  /**
   * Reports a FailureReason from a connector Spec job to the JobErrorReportingClient.
   *
   * @param failureReason - failure reason from the Deploy job
   * @param jobContext - connector job reporting context
   */
  public void reportSpecJobFailure(final FailureReason failureReason, final ConnectorJobReportingContext jobContext) {
    final String dockerImage = jobContext.dockerImage();
    final String connectorRepository = dockerImage.split(":")[0];
    final Map<String, String> metadata = Map.of(
        JOB_ID_KEY, jobContext.jobId().toString(),
        CONNECTOR_REPOSITORY_META_KEY, connectorRepository);
  }

  private Map<String, String> getConnectionMetadata(final UUID workspaceId, final UUID connectionId) {
    final String connectionUrl = webUrlHelper.getConnectionUrl(workspaceId, connectionId);
    return Map.ofEntries(
        Map.entry(CONNECTION_ID_META_KEY, connectionId.toString()),
        Map.entry(CONNECTION_URL_META_KEY, connectionUrl));
  }

  private Map<String, String> getDestinationMetadata(final StandardDestinationDefinition destinationDefinition,
                                                     final String dockerImage,
                                                     @Nullable final ReleaseStage releaseStage) {
    final String connectorRepository = dockerImage.split(":")[0];

    final Map<String, String> metadata = new HashMap<>(Map.ofEntries(
        Map.entry(CONNECTOR_DEFINITION_ID_META_KEY, destinationDefinition.getDestinationDefinitionId().toString()),
        Map.entry(CONNECTOR_NAME_META_KEY, destinationDefinition.getName()),
        Map.entry(CONNECTOR_REPOSITORY_META_KEY, connectorRepository)));
    if (releaseStage != null) {
      metadata.put(CONNECTOR_RELEASE_STAGE_META_KEY, releaseStage.value());
    }
    return metadata;
  }

  private Map<String, String> getSourceMetadata(final StandardSourceDefinition sourceDefinition,
                                                final String dockerImage,
                                                @Nullable final ReleaseStage releaseStage) {
    final String connectorRepository = dockerImage.split(":")[0];
    final Map<String, String> metadata = new HashMap<>(Map.ofEntries(
        Map.entry(CONNECTOR_DEFINITION_ID_META_KEY, sourceDefinition.getSourceDefinitionId().toString()),
        Map.entry(CONNECTOR_NAME_META_KEY, sourceDefinition.getName()),
        Map.entry(CONNECTOR_REPOSITORY_META_KEY, connectorRepository)));
    if (releaseStage != null) {
      metadata.put(CONNECTOR_RELEASE_STAGE_META_KEY, releaseStage.value());
    }
    return metadata;
  }

  private Map<String, String> getNormalizationMetadata(final String normalizationImage) {
    return Map.ofEntries(
        Map.entry(NORMALIZATION_REPOSITORY_META_KEY, normalizationImage));
  }

  private Map<String, String> prefixConnectorMetadataKeys(final Map<String, String> connectorMetadata, final String prefix) {
    final Map<String, String> prefixedMetadata = new HashMap<>();
    for (final Map.Entry<String, String> entry : connectorMetadata.entrySet()) {
      prefixedMetadata.put(String.format("%s_%s", prefix, entry.getKey()), entry.getValue());
    }
    return prefixedMetadata;
  }

  private Map<String, String> getFailureReasonMetadata(final FailureReason failureReason) {
    final Map<String, Object> failureReasonAdditionalProps =
        failureReason.getMetadata() != null ? failureReason.getMetadata().getAdditionalProperties() : Map.of();
    final Map<String, String> outMetadata = new HashMap<>();

    if (failureReasonAdditionalProps.containsKey(CONNECTOR_COMMAND_META_KEY)
        && failureReasonAdditionalProps.get(CONNECTOR_COMMAND_META_KEY) != null) {
      outMetadata.put(CONNECTOR_COMMAND_META_KEY, failureReasonAdditionalProps.get(CONNECTOR_COMMAND_META_KEY).toString());
    }

    if (failureReason.getFailureOrigin() != null) {
      outMetadata.put(FAILURE_ORIGIN_META_KEY, failureReason.getFailureOrigin().value());
    }

    if (failureReason.getFailureType() != null) {
      outMetadata.put(FAILURE_TYPE_META_KEY, failureReason.getFailureType().value());
    }

    return outMetadata;
  }

  private Map<String, String> getWorkspaceMetadata(final UUID workspaceId) {
    final String workspaceUrl = webUrlHelper.getWorkspaceUrl(workspaceId);
    return Map.ofEntries(
        Map.entry(WORKSPACE_ID_META_KEY, workspaceId.toString()),
        Map.entry(WORKSPACE_URL_META_KEY, workspaceUrl));
  }

  private void reportJobFailureReason(@Nullable final StandardWorkspace workspace,
                                      final FailureReason failureReason,
                                      final String dockerImage,
                                      final Map<String, String> metadata,
                                      @Nullable final AttemptConfigReportingContext attemptConfig) {
    // Failure types associated with a config-error or a manual-cancellation should NOT be reported.
    if (UNSUPPORTED_FAILURETYPES.contains(failureReason.getFailureType())) {
      return;
    }

    final Map<String, String> commonMetadata = new HashMap<>(Map.ofEntries(
        Map.entry(AIRBYTE_VERSION_META_KEY, airbyteVersion),
        Map.entry(DEPLOYMENT_MODE_META_KEY, deploymentMode.name())));

    if (workspace != null) {
      commonMetadata.putAll(getWorkspaceMetadata(workspace.getWorkspaceId()));
    }

    final Map<String, String> allMetadata = MoreMaps.merge(
        commonMetadata,
        getFailureReasonMetadata(failureReason),
        metadata);

    try {
      jobErrorReportingClient.reportJobFailureReason(workspace, failureReason, dockerImage, allMetadata, attemptConfig);
    } catch (final Exception e) {
      LOGGER.error("Error when reporting job failure reason: {}", failureReason, e);
    }
  }

}
