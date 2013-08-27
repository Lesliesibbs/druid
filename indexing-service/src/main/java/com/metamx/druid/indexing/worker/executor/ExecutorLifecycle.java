package com.metamx.druid.indexing.worker.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.inject.Inject;
import com.metamx.common.lifecycle.LifecycleStart;
import com.metamx.common.lifecycle.LifecycleStop;
import com.metamx.druid.concurrent.Execs;
import com.metamx.druid.indexing.common.TaskStatus;
import com.metamx.druid.indexing.common.task.Task;
import com.metamx.druid.indexing.coordinator.TaskRunner;
import com.metamx.emitter.EmittingLogger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;

/**
 * Encapsulates the lifecycle of a task executor. Loads one task, runs it, writes its status, and all the while
 * monitors its parent process.
 */
public class ExecutorLifecycle
{
  private static final EmittingLogger log = new EmittingLogger(ExecutorLifecycle.class);

  private final ExecutorLifecycleConfig config;
  private final TaskRunner taskRunner;
  private final ObjectMapper jsonMapper;

  private final ExecutorService parentMonitorExec = Execs.singleThreaded("parent-monitor-%d");

  private volatile ListenableFuture<TaskStatus> statusFuture = null;

  @Inject
  public ExecutorLifecycle(
      ExecutorLifecycleConfig config,
      TaskRunner taskRunner,
      ObjectMapper jsonMapper
  )
  {
    this.config = config;
    this.taskRunner = taskRunner;
    this.jsonMapper = jsonMapper;
  }

  @LifecycleStart
  public void start()
  {
    final File taskFile = config.getTaskFile();
    final File statusFile = config.getStatusFile();
    final InputStream parentStream = config.getParentStream();

    final Task task;

    try {
      task = jsonMapper.readValue(taskFile, Task.class);

      log.info(
          "Running with task: %s",
          jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(task)
      );
    }
    catch (IOException e) {
      throw Throwables.propagate(e);
    }

    // Spawn monitor thread to keep a watch on parent's stdin
    // If stdin reaches eof, the parent is gone, and we should shut down
    parentMonitorExec.submit(
        new Runnable()
        {
          @Override
          public void run()
          {
            try {
              while (parentStream.read() != -1) {
                // Toss the byte
              }
            }
            catch (Exception e) {
              log.error(e, "Failed to read from stdin");
            }

            // Kind of gross, but best way to kill the JVM as far as I know
            log.info("Triggering JVM shutdown.");
            System.exit(2);
          }
        }
    );

    statusFuture = Futures.transform(
        taskRunner.run(task), new Function<TaskStatus, TaskStatus>()
    {
      @Override
      public TaskStatus apply(TaskStatus taskStatus)
      {
        try {
          log.info(
              "Task completed with status: %s",
              jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(taskStatus)
          );

          jsonMapper.writeValue(statusFile, taskStatus);

          return taskStatus;
        }
        catch (Exception e) {
          throw Throwables.propagate(e);
        }
      }
    }
    );
  }

  public void join()
  {
    try {
      statusFuture.get();
    }
    catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  @LifecycleStop
  public void stop()
  {
    parentMonitorExec.shutdown();
  }
}
