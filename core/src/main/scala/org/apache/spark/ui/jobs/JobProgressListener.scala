/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.ui.jobs

import scala.collection.mutable.{HashMap, ListBuffer, Map}

import org.apache.spark._
import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.executor.TaskMetrics
import org.apache.spark.scheduler._
import org.apache.spark.scheduler.SchedulingMode.SchedulingMode
import org.apache.spark.storage.BlockManagerId
import org.apache.spark.ui.jobs.UIData._

/**
 * :: DeveloperApi ::
 * Tracks task-level information to be displayed in the UI.
 *
 * All access to the data structures in this class must be synchronized on the
 * class, since the UI thread and the EventBus loop may otherwise be reading and
 * updating the internal data structures concurrently.
 */
@DeveloperApi
class JobProgressListener(conf: SparkConf) extends SparkListener with Logging {

  import JobProgressListener._

  // How many stages to remember
  val retainedStages = conf.getInt("spark.ui.retainedStages", DEFAULT_RETAINED_STAGES)

  val activeStages = HashMap[Int, StageInfo]()
  val completedStages = ListBuffer[StageInfo]()
  val failedStages = ListBuffer[StageInfo]()

  val stageIdToData = new HashMap[Int, StageUIData]

  val poolToActiveStages = HashMap[String, HashMap[Int, StageInfo]]()

  val executorIdToBlockManagerId = HashMap[String, BlockManagerId]()

  var schedulingMode: Option[SchedulingMode] = None

  def blockManagerIds = executorIdToBlockManagerId.values.toSeq

  override def onStageCompleted(stageCompleted: SparkListenerStageCompleted) = synchronized {
    val stage = stageCompleted.stageInfo
    val stageId = stage.stageId
    val stageData = stageIdToData.getOrElseUpdate(stageId, {
      logWarning("Stage completed for unknown stage " + stageId)
      new StageUIData
    })

    for ((id, info) <- stageCompleted.stageInfo.accumulables) {
      stageData.accumulables(id) = info
    }

    poolToActiveStages.get(stageData.schedulingPool).foreach(_.remove(stageId))
    activeStages.remove(stageId)
    if (stage.failureReason.isEmpty) {
      completedStages += stage
      trimIfNecessary(completedStages)
    } else {
      failedStages += stage
      trimIfNecessary(failedStages)
    }
  }

  /** If stages is too large, remove and garbage collect old stages */
  private def trimIfNecessary(stages: ListBuffer[StageInfo]) = synchronized {
    if (stages.size > retainedStages) {
      val toRemove = math.max(retainedStages / 10, 1)
      stages.take(toRemove).foreach { s => stageIdToData.remove(s.stageId) }
      stages.trimStart(toRemove)
    }
  }

  /** For FIFO, all stages are contained by "default" pool but "default" pool here is meaningless */
  override def onStageSubmitted(stageSubmitted: SparkListenerStageSubmitted) = synchronized {
    val stage = stageSubmitted.stageInfo
    activeStages(stage.stageId) = stage

    val poolName = Option(stageSubmitted.properties).map {
      p => p.getProperty("spark.scheduler.pool", DEFAULT_POOL_NAME)
    }.getOrElse(DEFAULT_POOL_NAME)

    val stageData = stageIdToData.getOrElseUpdate(stage.stageId, new StageUIData)
    stageData.schedulingPool = poolName

    stageData.description = Option(stageSubmitted.properties).flatMap {
      p => Option(p.getProperty(SparkContext.SPARK_JOB_DESCRIPTION))
    }

    val stages = poolToActiveStages.getOrElseUpdate(poolName, new HashMap[Int, StageInfo]())
    stages(stage.stageId) = stage
  }

  override def onTaskStart(taskStart: SparkListenerTaskStart) = synchronized {
    val taskInfo = taskStart.taskInfo
    if (taskInfo != null) {
      val stageData = stageIdToData.getOrElseUpdate(taskStart.stageId, {
        logWarning("Task start for unknown stage " + taskStart.stageId)
        new StageUIData
      })
      stageData.numActiveTasks += 1
      stageData.taskData.put(taskInfo.taskId, new TaskUIData(taskInfo))
    }
  }

  override def onTaskGettingResult(taskGettingResult: SparkListenerTaskGettingResult) {
    // Do nothing: because we don't do a deep copy of the TaskInfo, the TaskInfo in
    // stageToTaskInfos already has the updated status.
  }

  override def onTaskEnd(taskEnd: SparkListenerTaskEnd) = synchronized {
    val info = taskEnd.taskInfo
    if (info != null) {
      val stageData = stageIdToData.getOrElseUpdate(taskEnd.stageId, {
        logWarning("Task end for unknown stage " + taskEnd.stageId)
        new StageUIData
      })

      for (accumulableInfo <- info.accumulables) {
        stageData.accumulables(accumulableInfo.id) = accumulableInfo
      }

      val execSummaryMap = stageData.executorSummary
      val execSummary = execSummaryMap.getOrElseUpdate(info.executorId, new ExecutorSummary)

      taskEnd.reason match {
        case Success =>
          execSummary.succeededTasks += 1
        case _ =>
          execSummary.failedTasks += 1
      }
      execSummary.taskTime += info.duration
      stageData.numActiveTasks -= 1

      val (errorMessage, metrics): (Option[String], Option[TaskMetrics]) =
        taskEnd.reason match {
          case org.apache.spark.Success =>
            stageData.completedIndices.add(info.index)
            stageData.numCompleteTasks += 1
            (None, Option(taskEnd.taskMetrics))
          case e: ExceptionFailure =>  // Handle ExceptionFailure because we might have metrics
            stageData.numFailedTasks += 1
            (Some(e.toErrorString), e.metrics)
          case e: TaskFailedReason =>  // All other failure cases
            stageData.numFailedTasks += 1
            (Some(e.toErrorString), None)
        }

      if (!metrics.isEmpty) {
        val oldMetrics = stageData.taskData.get(info.taskId).flatMap(_.taskMetrics)
        updateAggregateMetrics(stageData, info.executorId, metrics.get, oldMetrics)
      }

      val taskData = stageData.taskData.getOrElseUpdate(info.taskId, new TaskUIData(info))
      taskData.taskInfo = info
      taskData.taskMetrics = metrics
      taskData.errorMessage = errorMessage
    }
  }

  /**
   * Upon receiving new metrics for a task, updates the per-stage and per-executor-per-stage
   * aggregate metrics by calculating deltas between the currently recorded metrics and the new
   * metrics.
   */
  def updateAggregateMetrics(
      stageData: StageUIData,
      execId: String,
      taskMetrics: TaskMetrics,
      oldMetrics: Option[TaskMetrics]) {
    val execSummary = stageData.executorSummary.getOrElseUpdate(execId, new ExecutorSummary)

    val shuffleWriteDelta =
      (taskMetrics.shuffleWriteMetrics.map(_.shuffleBytesWritten).getOrElse(0L)
      - oldMetrics.flatMap(_.shuffleWriteMetrics).map(_.shuffleBytesWritten).getOrElse(0L))
    stageData.shuffleWriteBytes += shuffleWriteDelta
    execSummary.shuffleWrite += shuffleWriteDelta

    val shuffleReadDelta =
      (taskMetrics.shuffleReadMetrics.map(_.remoteBytesRead).getOrElse(0L)
      - oldMetrics.flatMap(_.shuffleReadMetrics).map(_.remoteBytesRead).getOrElse(0L))
    stageData.shuffleReadBytes += shuffleReadDelta
    execSummary.shuffleRead += shuffleReadDelta

    val diskSpillDelta =
      taskMetrics.diskBytesSpilled - oldMetrics.map(_.diskBytesSpilled).getOrElse(0L)
    stageData.diskBytesSpilled += diskSpillDelta
    execSummary.diskBytesSpilled += diskSpillDelta

    val memorySpillDelta =
      taskMetrics.memoryBytesSpilled - oldMetrics.map(_.memoryBytesSpilled).getOrElse(0L)
    stageData.memoryBytesSpilled += memorySpillDelta
    execSummary.memoryBytesSpilled += memorySpillDelta

    val timeDelta =
      taskMetrics.executorRunTime - oldMetrics.map(_.executorRunTime).getOrElse(0L)
    stageData.executorRunTime += timeDelta
  }

  override def onExecutorMetricsUpdate(executorMetricsUpdate: SparkListenerExecutorMetricsUpdate) {
    for ((taskId, sid, taskMetrics) <- executorMetricsUpdate.taskMetrics) {
      val stageData = stageIdToData.getOrElseUpdate(sid, {
        logWarning("Metrics update for task in unknown stage " + sid)
        new StageUIData
      })
      val taskData = stageData.taskData.get(taskId)
      taskData.map { t =>
        if (!t.taskInfo.finished) {
          updateAggregateMetrics(stageData, executorMetricsUpdate.execId, taskMetrics,
            t.taskMetrics)

          // Overwrite task metrics
          t.taskMetrics = Some(taskMetrics)
        }
      }
    }
  }

  override def onEnvironmentUpdate(environmentUpdate: SparkListenerEnvironmentUpdate) {
    synchronized {
      schedulingMode = environmentUpdate
        .environmentDetails("Spark Properties").toMap
        .get("spark.scheduler.mode")
        .map(SchedulingMode.withName)
    }
  }

  override def onBlockManagerAdded(blockManagerAdded: SparkListenerBlockManagerAdded) {
    synchronized {
      val blockManagerId = blockManagerAdded.blockManagerId
      val executorId = blockManagerId.executorId
      executorIdToBlockManagerId(executorId) = blockManagerId
    }
  }

  override def onBlockManagerRemoved(blockManagerRemoved: SparkListenerBlockManagerRemoved) {
    synchronized {
      val executorId = blockManagerRemoved.blockManagerId.executorId
      executorIdToBlockManagerId.remove(executorId)
    }
  }

}

private object JobProgressListener {
  val DEFAULT_POOL_NAME = "default"
  val DEFAULT_RETAINED_STAGES = 1000
}
