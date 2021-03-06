/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gobblin.cluster;

import com.google.common.base.Joiner;

import java.util.Map;
import java.util.Set;

import org.I0Itec.zkclient.DataUpdater;
import org.apache.helix.AccessOption;
import org.apache.helix.ConfigAccessor;
import org.apache.helix.HelixAdmin;
import org.apache.helix.HelixDataAccessor;
import org.apache.helix.HelixManager;
import org.apache.helix.PropertyPathConfig;
import org.apache.helix.PropertyType;
import org.apache.helix.ZNRecord;
import org.apache.helix.manager.zk.ZKHelixAdmin;
import org.apache.helix.manager.zk.ZKHelixDataAccessor;
import org.apache.helix.manager.zk.ZkBaseDataAccessor;
import org.apache.helix.manager.zk.ZkClient;
import org.apache.helix.store.HelixPropertyStore;
import org.apache.helix.store.zk.ZkHelixPropertyStore;
import org.apache.helix.task.JobDag;
import org.apache.helix.task.TaskConstants;
import org.apache.helix.task.TaskDriver;
import org.apache.helix.task.TaskState;
import org.apache.helix.task.TaskUtil;
import org.apache.helix.task.WorkflowConfig;
import org.apache.helix.task.WorkflowContext;
import org.apache.log4j.Logger;

/**
 * #HELIX-0.6.7-WORKAROUND
 * Replacement TaskDriver methods to workaround bugs and changes in behavior for the 0.6.7 upgrade
 */
public class GobblinHelixTaskDriver {
  /** For logging */
  private static final Logger LOG = Logger.getLogger(GobblinHelixTaskDriver.class);

  private final HelixDataAccessor _accessor;
  private final ConfigAccessor _cfgAccessor;
  private final HelixPropertyStore<ZNRecord> _propertyStore;
  private final HelixAdmin _admin;
  private final String _clusterName;
  private final TaskDriver _taskDriver;

  public GobblinHelixTaskDriver(HelixManager manager) {
    this(manager.getClusterManagmentTool(), manager.getHelixDataAccessor(), manager
        .getConfigAccessor(), manager.getHelixPropertyStore(), manager.getClusterName());
  }

  public GobblinHelixTaskDriver(ZkClient client, String clusterName) {
    this(client, new ZkBaseDataAccessor<ZNRecord>(client), clusterName);
  }

  public GobblinHelixTaskDriver(ZkClient client, ZkBaseDataAccessor<ZNRecord> baseAccessor, String clusterName) {
    this(new ZKHelixAdmin(client), new ZKHelixDataAccessor(clusterName, baseAccessor),
        new ConfigAccessor(client), new ZkHelixPropertyStore<ZNRecord>(baseAccessor,
            PropertyPathConfig.getPath(PropertyType.PROPERTYSTORE, clusterName), null), clusterName);
  }

  public GobblinHelixTaskDriver(HelixAdmin admin, HelixDataAccessor accessor, ConfigAccessor cfgAccessor,
      HelixPropertyStore<ZNRecord> propertyStore, String clusterName) {
    _admin = admin;
    _accessor = accessor;
    _cfgAccessor = cfgAccessor;
    _propertyStore = propertyStore;
    _clusterName = clusterName;
    _taskDriver = new TaskDriver(admin, accessor, cfgAccessor, propertyStore, clusterName);
  }

  /**
   * Delete a job from an existing named queue,
   * the queue has to be stopped prior to this call
   *
   * @param queueName
   * @param jobName
   */
  public void deleteJob(final String queueName, final String jobName) {
    WorkflowConfig workflowCfg =
        _taskDriver.getWorkflowConfig(queueName);

    if (workflowCfg == null) {
      throw new IllegalArgumentException("Queue " + queueName + " does not yet exist!");
    }
    if (workflowCfg.isTerminable()) {
      throw new IllegalArgumentException(queueName + " is not a queue!");
    }

    boolean isRecurringWorkflow =
        (workflowCfg.getScheduleConfig() != null && workflowCfg.getScheduleConfig().isRecurring());

    if (isRecurringWorkflow) {
      WorkflowContext wCtx = _taskDriver.getWorkflowContext(queueName);

      String lastScheduledQueue = wCtx.getLastScheduledSingleWorkflow();

      // delete the current scheduled one
      deleteJobFromScheduledQueue(lastScheduledQueue, jobName, true);

      // Remove the job from the original queue template's DAG
      removeJobFromDag(queueName, jobName);

      // delete the ideal state and resource config for the template job
      final String namespacedJobName = TaskUtil.getNamespacedJobName(queueName, jobName);
      _admin.dropResource(_clusterName, namespacedJobName);

      // Delete the job template from property store
      String jobPropertyPath =
          Joiner.on("/")
              .join(TaskConstants.REBALANCER_CONTEXT_ROOT, namespacedJobName);
      _propertyStore.remove(jobPropertyPath, AccessOption.PERSISTENT);
    } else {
      deleteJobFromScheduledQueue(queueName, jobName, false);
    }
  }

  /**
   * delete a job from a scheduled (non-recurrent) queue.
   *
   * @param queueName
   * @param jobName
   */
  private void deleteJobFromScheduledQueue(final String queueName, final String jobName,
      boolean isRecurrent) {
    WorkflowConfig workflowCfg = _taskDriver.getWorkflowConfig(queueName);

    if (workflowCfg == null) {
      // When try to delete recurrent job, it could be either not started or finished. So
      // there may not be a workflow config.
      if (isRecurrent) {
        return;
      } else {
        throw new IllegalArgumentException("Queue " + queueName + " does not yet exist!");
      }
    }

    WorkflowContext wCtx = _taskDriver.getWorkflowContext(queueName);
    if (wCtx != null && wCtx.getWorkflowState() == null) {
      throw new IllegalStateException("Queue " + queueName + " does not have a valid work state!");
    }

    // #HELIX-0.6.7-WORKAROUND
    // This check is removed to get the same behavior as 0.6.6-SNAPSHOT until new APIs to support delete are provided
    //String workflowState =
    //    (wCtx != null) ? wCtx.getWorkflowState().name() : TaskState.NOT_STARTED.name();
    //if (workflowState.equals(TaskState.IN_PROGRESS.name())) {
    //  throw new IllegalStateException("Queue " + queueName + " is still in progress!");
    //}

    removeJob(queueName, jobName);
  }

  private boolean removeJobContext(HelixPropertyStore<ZNRecord> propertyStore,
      String jobResource) {
    return propertyStore.remove(
        Joiner.on("/").join(TaskConstants.REBALANCER_CONTEXT_ROOT, jobResource),
        AccessOption.PERSISTENT);
  }

  private void removeJob(String queueName, String jobName) {
    // Remove the job from the queue in the DAG
    removeJobFromDag(queueName, jobName);

    // delete the ideal state and resource config for the job
    final String namespacedJobName = TaskUtil.getNamespacedJobName(queueName, jobName);
    _admin.dropResource(_clusterName, namespacedJobName);

    // update queue's property to remove job from JOB_STATES if it is already started.
    removeJobStateFromQueue(queueName, jobName);

    // Delete the job from property store
    removeJobContext(_propertyStore, jobName);
  }

  /** Remove the job name from the DAG from the queue configuration */
  private void removeJobFromDag(final String queueName, final String jobName) {
    final String namespacedJobName = TaskUtil.getNamespacedJobName(queueName, jobName);

    DataUpdater<ZNRecord> dagRemover = new DataUpdater<ZNRecord>() {
      @Override
      public ZNRecord update(ZNRecord currentData) {
        if (currentData == null) {
          LOG.error("Could not update DAG for queue: " + queueName + " ZNRecord is null.");
          return null;
        }
        // Add the node to the existing DAG
        JobDag jobDag = JobDag.fromJson(
            currentData.getSimpleField(WorkflowConfig.WorkflowConfigProperty.Dag.name()));
        Set<String> allNodes = jobDag.getAllNodes();
        if (!allNodes.contains(namespacedJobName)) {
          LOG.warn(
              "Could not delete job from queue " + queueName + ", job " + jobName + " not exists");
          return currentData;
        }
        String parent = null;
        String child = null;
        // remove the node from the queue
        for (String node : allNodes) {
          if (jobDag.getDirectChildren(node).contains(namespacedJobName)) {
            parent = node;
            jobDag.removeParentToChild(parent, namespacedJobName);
          } else if (jobDag.getDirectParents(node).contains(namespacedJobName)) {
            child = node;
            jobDag.removeParentToChild(namespacedJobName, child);
          }
        }
        if (parent != null && child != null) {
          jobDag.addParentToChild(parent, child);
        }
        jobDag.removeNode(namespacedJobName);

        // Save the updated DAG
        try {
          currentData
              .setSimpleField(WorkflowConfig.WorkflowConfigProperty.Dag.name(), jobDag.toJson());
        } catch (Exception e) {
          throw new IllegalStateException(
              "Could not remove job " + jobName + " from DAG of queue " + queueName, e);
        }
        return currentData;
      }
    };

    String path = _accessor.keyBuilder().resourceConfig(queueName).getPath();
    if (!_accessor.getBaseDataAccessor().update(path, dagRemover, AccessOption.PERSISTENT)) {
      throw new IllegalArgumentException(
          "Could not remove job " + jobName + " from DAG of queue " + queueName);
    }
  }

  /** update queue's property to remove job from JOB_STATES if it is already started. */
  private void removeJobStateFromQueue(final String queueName, final String jobName) {
    final String namespacedJobName = TaskUtil.getNamespacedJobName(queueName, jobName);
    String queuePropertyPath =
        Joiner.on("/")
            .join(TaskConstants.REBALANCER_CONTEXT_ROOT, queueName, TaskUtil.CONTEXT_NODE);

    DataUpdater<ZNRecord> updater = new DataUpdater<ZNRecord>() {
      @Override
      public ZNRecord update(ZNRecord currentData) {
        if (currentData != null) {
          Map<String, String> states = currentData.getMapField(WorkflowContext.JOB_STATES);
          if (states != null && states.containsKey(namespacedJobName)) {
            states.keySet().remove(namespacedJobName);
          }
        }
        return currentData;
      }
    };
    if (!_propertyStore.update(queuePropertyPath, updater, AccessOption.PERSISTENT)) {
      LOG.warn("Fail to remove job state for job " + namespacedJobName + " from queue " + queueName);
    }
  }
}
