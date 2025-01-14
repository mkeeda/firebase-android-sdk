// Copyright 2020 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.crashlytics.internal.common;

import static java.util.Objects.requireNonNull;

import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Continuation;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Utils */
public final class Utils {

  private Utils() {}

  /** @return A tasks that is resolved when either of the given tasks is resolved. */
  public static <T> Task<T> race(Task<T> t1, Task<T> t2) {
    final TaskCompletionSource<T> result = new TaskCompletionSource<>();
    Continuation<T, Void> continuation =
        task -> {
          if (task.isSuccessful()) {
            result.trySetResult(task.getResult());
          } else {
            result.trySetException(requireNonNull(task.getException()));
          }
          return null;
        };
    t1.continueWith(continuation);
    t2.continueWith(continuation);
    return result.getTask();
  }

  /** @return A tasks that is resolved when either of the given tasks is resolved. */
  public static <T> Task<T> race(Executor executor, Task<T> t1, Task<T> t2) {
    final TaskCompletionSource<T> result = new TaskCompletionSource<>();
    Continuation<T, Void> continuation =
        task -> {
          if (task.isSuccessful()) {
            result.trySetResult(task.getResult());
          } else {
            result.trySetException(requireNonNull(task.getException()));
          }
          return null;
        };
    t1.continueWith(executor, continuation);
    t2.continueWith(executor, continuation);
    return result.getTask();
  }

  /** Similar to Tasks.call, but takes a Callable that returns a Task. */
  public static <T> Task<T> callTask(Executor executor, Callable<Task<T>> callable) {
    final TaskCompletionSource<T> tcs = new TaskCompletionSource<T>();
    executor.execute(
        new Runnable() {
          @Override
          public void run() {
            try {
              callable
                  .call()
                  .continueWith(
                      new Continuation<T, Void>() {
                        @Override
                        public Void then(@NonNull Task<T> task) throws Exception {
                          if (task.isSuccessful()) {
                            tcs.setResult(task.getResult());
                          } else {
                            tcs.setException(task.getException());
                          }
                          return null;
                        }
                      });
            } catch (Exception e) {
              tcs.setException(e);
            }
          }
        });
    return tcs.getTask();
  }

  /**
   * Blocks until the given Task completes, and then returns the value the Task was resolved with,
   * if successful. If the Task fails, an exception will be thrown, wrapping the Exception of the
   * Task. Blocking on Tasks is generally a bad idea, and you definitely should not block the main
   * thread waiting on one. But there are a couple of weird spots in our SDK where we really have no
   * choice. You should not use this method for any new code. And if you really do have to use it,
   * you should feel slightly bad about it.
   *
   * @param task the task to block on
   * @return the value that was returned by the task, if successful.
   * @throws InterruptedException if the method was interrupted
   * @throws TimeoutException if the method timed out while waiting for the task.
   */
  public static <T> T awaitEvenIfOnMainThread(Task<T> task)
      throws InterruptedException, TimeoutException {
    CountDownLatch latch = new CountDownLatch(1);

    task.continueWith(
        TASK_CONTINUATION_EXECUTOR_SERVICE,
        unusedTask -> {
          latch.countDown();
          return null;
        });

    latch.await(CrashlyticsCore.DEFAULT_MAIN_HANDLER_TIMEOUT_SEC, TimeUnit.SECONDS);

    if (task.isSuccessful()) {
      return task.getResult();
    } else if (task.isCanceled()) {
      throw new CancellationException("Task is already canceled");
    } else if (task.isComplete()) {
      throw new IllegalStateException(task.getException());
    } else {
      throw new TimeoutException();
    }
  }

  /** Invokes latch.await(timeout, unit) uninterruptibly. */
  public static boolean awaitUninterruptibly(CountDownLatch latch, long timeout, TimeUnit unit) {
    boolean interrupted = false;
    try {
      long remainingNanos = unit.toNanos(timeout);
      long end = System.nanoTime() + remainingNanos;

      while (true) {
        try {
          // CountDownLatch treats negative timeouts just like zero.
          return latch.await(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
          interrupted = true;
          remainingNanos = end - System.nanoTime();
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * ExecutorService that is used exclusively by the awaitEvenIfOnMainThread function. If the
   * Continuation which counts down the latch is called on the same thread which is waiting on the
   * latch, a deadlock will occur. A dedicated ExecutorService ensures that cannot happen.
   */
  private static final ExecutorService TASK_CONTINUATION_EXECUTOR_SERVICE =
      ExecutorUtils.buildSingleThreadExecutorService(
          "awaitEvenIfOnMainThread task continuation executor");
}
