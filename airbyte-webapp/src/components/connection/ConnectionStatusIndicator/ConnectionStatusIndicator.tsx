import classNames from "classnames";
import React, { cloneElement } from "react";

import { Icon, IconProps } from "components/ui/Icon";
import { CircleLoader } from "components/ui/StatusIcon/CircleLoader";

import { useExperiment } from "hooks/services/Experiment";

import styles from "./ConnectionStatusIndicator.module.scss";
import { StreamStatusLoadingSpinner } from "../StreamStatusIndicator";

export enum ConnectionStatusIndicatorStatus {
  OnTime = "onTime",
  OnTrack = "onTrack",
  Late = "late",
  Pending = "pending",
  Syncing = "syncing",
  Queued = "queued",
  Error = "error",
  ActionRequired = "actionRequired",
  Disabled = "disabled",
}

const ICON_BY_STATUS: Readonly<Record<ConnectionStatusIndicatorStatus, JSX.Element>> = {
  onTime: <Icon type="statusSuccess" size="md" />,
  onTrack: <Icon type="statusSuccess" size="md" />,
  error: <Icon type="statusWarning" size="md" />,
  disabled: <Icon type="statusInactive" size="md" />,
  pending: <Icon type="statusInactive" size="md" />,
  late: <Icon type="clockFilled" size="md" />,
  actionRequired: <Icon type="statusError" size="md" />,
  syncing: <CircleLoader className={styles.circleLoader} />,
  queued: <CircleLoader className={styles.circleLoader} />,
};

const STYLE_BY_STATUS: Readonly<Record<ConnectionStatusIndicatorStatus, string>> = {
  onTime: styles["status--upToDate"],
  onTrack: styles["status--upToDate"],
  error: styles["status--error"],
  disabled: styles["status--disabled"],
  pending: styles["status--pending"],
  late: styles["status--late"],
  actionRequired: styles["status--actionRequired"],
  syncing: styles["status--syncing"],
  queued: styles["status--syncing"],
};

const BOX_STYLE_BY_STATUS: Readonly<Record<ConnectionStatusIndicatorStatus, string>> = {
  onTime: styles["status--upToDate-withBox"],
  onTrack: styles["status--upToDate-withBox"],
  error: styles["status--error-withBox"],
  disabled: styles["status--disabled-withBox"],
  pending: styles["status--pending-withBox"],
  late: styles["status--late-withBox"],
  actionRequired: styles["status--actionRequired-withBox"],
  syncing: styles["status--syncing-withBox"],
  queued: styles["status--syncing-withBox"],
};

interface ConnectionStatusIndicatorProps {
  status: ConnectionStatusIndicatorStatus;
  loading?: boolean;
  withBox?: boolean;
  size?: IconProps["size"];
}

export const ConnectionStatusIndicator: React.FC<ConnectionStatusIndicatorProps> = ({
  status,
  loading,
  withBox,
  size,
}) => {
  const showSyncProgress = useExperiment("connection.syncProgress", false);

  return (
    <div
      className={classNames(styles.status, STYLE_BY_STATUS[status], { [BOX_STYLE_BY_STATUS[status]]: withBox })}
      data-loading={loading}
      data-testid="connection-status-indicator"
      data-status={status}
    >
      <div className={styles.icon}>{cloneElement(ICON_BY_STATUS[status], { [size ? "size" : ""]: size })}</div>
      {!showSyncProgress && loading && <StreamStatusLoadingSpinner className={styles.spinner} />}
    </div>
  );
};
