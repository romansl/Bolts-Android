/*
 *  Copyright (c) 2014, Facebook, Inc.
 *  All rights reserved.
 *
 *  This source code is licensed under the BSD-style license found in the
 *  LICENSE file in the root directory of this source tree. An additional grant
 *  of patent rights can be found in the PATENTS file in the same directory.
 *
 */
package bolts

import java.util.ArrayList
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.platform.platformStatic

/**
 * Represents the result of an asynchronous operation.
 *
 * @param <TResult> The type of the result of the task.
 */
[suppress("BASE_WITH_NULLABLE_UPPER_BOUND")]
public class Task<TResult> private() {

    private val lock = Object()
    private var complete: Boolean = false
    private var cancelled: Boolean = false
    private var result: TResult? = null
    private var error: Exception? = null
    private var continuations: ArrayList<(Task<TResult>) -> Unit>? = null

    {
        continuations = ArrayList<(Task<TResult>) -> Unit>()
    }

    /**
     * @return {@code true} if the task completed (has a result, an error, or was cancelled.
     * {@code false} otherwise.
     */
    public fun isCompleted(): Boolean {
        synchronized (lock) {
            return complete
        }
    }

    /**
     * @return {@code true} if the task was cancelled, {@code false} otherwise.
     */
    public fun isCancelled(): Boolean {
        synchronized (lock) {
            return cancelled
        }
    }

    /**
     * @return {@code true} if the task has an error, {@code false} otherwise.
     */
    public fun isFaulted(): Boolean {
        synchronized (lock) {
            return error != null
        }
    }

    /**
     * @return The result of the task, if set. {@code null} otherwise.
     */
    public fun getResult(): TResult? {
        synchronized (lock) {
            return result
        }
    }

    /**
     * @return The error for the task, if set. {@code null} otherwise.
     */
    public fun getError(): Exception? {
        synchronized (lock) {
            return error
        }
    }

    /**
     * Blocks until the task is complete.
     */
    throws(javaClass<InterruptedException>())
    public fun waitForCompletion() {
        synchronized (lock) {
            if (!isCompleted()) {
                lock.wait()
            }
        }
    }

    /**
     * Makes a fluent cast of a Task's result possible, avoiding an extra continuation just to cast
     * the type of the result.
     */
    public fun <TOut> cast(): Task<TOut> {
        [suppress("CAST_NEVER_SUCCEEDS")]
        return this as Task<TOut>
    }

    /**
     * Turns a Task<T> into a Task<Void>, dropping any result.
     */
    public fun makeVoid(): Task<Void> {
        return this.continueWithTask<Void>(object : Continuation<TResult, Task<Void>> {
            throws(javaClass<Exception>())
            override fun then(task: Task<TResult>): Task<Void> {
                if (task.isCancelled()) {
                    return Task.cancelled<Void>()
                }
                if (task.isFaulted()) {
                    return Task.forError<Void>(task.getError())
                }
                return Task.forResult<Void>(null)
            }
        })
    }

    /**
     * Continues a task with the equivalent of a Task-based while loop, where the body of the loop is
     * a task continuation.
     */
    public fun continueWhile(predicate: Callable<Boolean>, continuation: Continuation<Void, Task<Void>>): Task<Void> {
        return continueWhile(predicate, continuation, IMMEDIATE_EXECUTOR)
    }

    /**
     * Continues a task with the equivalent of a Task-based while loop, where the body of the loop is
     * a task continuation.
     */
    public fun continueWhile(predicate: Callable<Boolean>, continuation: Continuation<Void, Task<Void>>, executor: Executor): Task<Void> {
        val predicateContinuation = Capture<Continuation<Void, Task<Void>>>()
        predicateContinuation.set(object : Continuation<Void, Task<Void>> {
            throws(javaClass<Exception>())
            override fun then(task: Task<Void>): Task<Void> {
                if (predicate.call()) {
                    return Task.forResult<Void>(null).onSuccessTask<Void>(continuation, executor).onSuccessTask<Void>(predicateContinuation.get(), executor)
                }
                return Task.forResult<Void>(null)
            }
        })
        return makeVoid().continueWithTask<Void>(executor, predicateContinuation.get())
    }

    /**
     * Adds a continuation that will be scheduled using the executor, returning a new task that
     * completes after the continuation has finished running. This allows the continuation to be
     * scheduled on different thread.
     */
    public fun <TContinuationResult> continueWith(executor: Executor, continuation: Continuation<TResult, TContinuationResult>): Task<TContinuationResult> {
        var completed: Boolean = false
        val tcs = Task.create<TContinuationResult>()
        synchronized (lock) {
            completed = isCompleted()
            if (!completed) {
                continuations!!.add {
                    completeImmediately(tcs, continuation, it, executor)
                }
            }
        }
        if (completed) {
            completeImmediately(tcs, continuation, this, executor)
        }
        return tcs.task
    }

    /**
     * Adds a synchronous continuation to this task, returning a new task that completes after the
     * continuation has finished running.
     */
    public fun <TContinuationResult> continueWith(continuation: Continuation<TResult, TContinuationResult>): Task<TContinuationResult> {
        return continueWith(IMMEDIATE_EXECUTOR, continuation)
    }

    /**
     * Adds an Task-based continuation to this task that will be scheduled using the executor,
     * returning a new task that completes after the task returned by the continuation has completed.
     */
    public fun <TContinuationResult> continueWithTask(executor: Executor, continuation: Continuation<TResult, Task<TContinuationResult>>): Task<TContinuationResult> {
        var completed: Boolean = false
        val tcs = Task.create<TContinuationResult>()
        synchronized (lock) {
            completed = isCompleted()
            if (!completed) {
                continuations!!.add {
                    completeAfterTask(tcs, continuation, it, executor)
                }
            }
        }
        if (completed) {
            completeAfterTask(tcs, continuation, this, executor)
        }
        return tcs.task
    }

    /**
     * Adds an asynchronous continuation to this task, returning a new task that completes after the
     * task returned by the continuation has completed.
     */
    public fun <TContinuationResult> continueWithTask(continuation: Continuation<TResult, Task<TContinuationResult>>): Task<TContinuationResult> {
        return continueWithTask(IMMEDIATE_EXECUTOR, continuation)
    }

    /**
     * Runs a continuation when a task completes successfully, forwarding along
     * {@link java.lang.Exception} or cancellation.
     */
    public fun <TContinuationResult> onSuccess(continuation: Continuation<TResult, TContinuationResult>, executor: Executor): Task<TContinuationResult> {
        return continueWithTask(executor, object : Continuation<TResult, Task<TContinuationResult>> {
            override fun then(task: Task<TResult>): Task<TContinuationResult> {
                if (task.isFaulted()) {
                    return Task.forError<TContinuationResult>(task.getError())
                } else if (task.isCancelled()) {
                    return Task.cancelled<TContinuationResult>()
                } else {
                    return task.continueWith<TContinuationResult>(continuation)
                }
            }
        })
    }

    /**
     * Runs a continuation when a task completes successfully, forwarding along
     * {@link java.lang.Exception}s or cancellation.
     */
    public fun <TContinuationResult> onSuccess(continuation: Continuation<TResult, TContinuationResult>): Task<TContinuationResult> {
        return onSuccess(continuation, IMMEDIATE_EXECUTOR)
    }

    /**
     * Runs a continuation when a task completes successfully, forwarding along
     * {@link java.lang.Exception}s or cancellation.
     */
    public fun <TContinuationResult> onSuccessTask(continuation: Continuation<TResult, Task<TContinuationResult>>, executor: Executor): Task<TContinuationResult> {
        return continueWithTask(executor, object : Continuation<TResult, Task<TContinuationResult>> {
            override fun then(task: Task<TResult>): Task<TContinuationResult> {
                if (task.isFaulted()) {
                    return Task.forError<TContinuationResult>(task.getError())
                } else if (task.isCancelled()) {
                    return Task.cancelled<TContinuationResult>()
                } else {
                    return task.continueWithTask<TContinuationResult>(continuation)
                }
            }
        })
    }

    /**
     * Runs a continuation when a task completes successfully, forwarding along
     * {@link java.lang.Exception}s or cancellation.
     */
    public fun <TContinuationResult> onSuccessTask(continuation: Continuation<TResult, Task<TContinuationResult>>): Task<TContinuationResult> {
        return onSuccessTask(continuation, IMMEDIATE_EXECUTOR)
    }

    private fun runContinuations() {
        synchronized (lock) {
            for (continuation in continuations!!) {
                try {
                    continuation(this)
                } catch (e: RuntimeException) {
                    throw e
                } catch (e: Exception) {
                    throw RuntimeException(e)
                }

            }
            continuations = null
        }
    }

    /**
     * Allows safe orchestration of a task's completion, preventing the consumer from prematurely
     * completing the task. Essentially, it represents the producer side of a Task<TResult>, providing
     * access to the consumer side through the getTask() method while isolating the Task's completion
     * mechanisms from the consumer.
     */
    public class TaskCompletionSource<TResult>(public val task: Task<TResult>) {

        /**
         * Sets the cancelled flag on the Task if the Task hasn't already been completed.
         */
        public fun trySetCancelled(): Boolean {
            synchronized (task.lock) {
                if (task.complete) {
                    return false
                }
                task.complete = true
                task.cancelled = true
                task.lock.notifyAll()
                task.runContinuations()
                return true
            }
        }

        /**
         * Sets the result on the Task if the Task hasn't already been completed.
         */
        public fun trySetResult(result: TResult?): Boolean {
            synchronized (task.lock) {
                if (task.complete) {
                    return false
                }
                task.complete = true
                task.result = result
                task.lock.notifyAll()
                task.runContinuations()
                return true
            }
        }

        /**
         * Sets the error on the Task if the Task hasn't already been completed.
         */
        public fun trySetError(error: Exception?): Boolean {
            synchronized (task.lock) {
                if (task.complete) {
                    return false
                }
                task.complete = true
                task.error = error
                task.lock.notifyAll()
                task.runContinuations()
                return true
            }
        }

        /**
         * Sets the cancelled flag on the task, throwing if the Task has already been completed.
         */
        public fun setCancelled() {
            if (!trySetCancelled()) {
                throw IllegalStateException("Cannot cancel a completed task.")
            }
        }

        /**
         * Sets the result of the Task, throwing if the Task has already been completed.
         */
        public fun setResult(result: TResult?) {
            if (!trySetResult(result)) {
                throw IllegalStateException("Cannot set the result of a completed task.")
            }
        }

        /**
         * Sets the error of the Task, throwing if the Task has already been completed.
         */
        public fun setError(error: Exception?) {
            if (!trySetError(error)) {
                throw IllegalStateException("Cannot set the error on a completed task.")
            }
        }
    }
    /**
     * @return the Task associated with this TaskCompletionSource.
     */

    class object {
        /**
         * An {@link java.util.concurrent.Executor} that executes tasks in parallel.
         */
        public val BACKGROUND_EXECUTOR: Executor = BoltsExecutors.background()

        /**
         * An {@link java.util.concurrent.Executor} that executes tasks in the current thread unless
         * the stack runs too deep, at which point it will delegate to {@link Task#BACKGROUND_EXECUTOR} in
         * order to trim the stack.
         */
        private val IMMEDIATE_EXECUTOR = BoltsExecutors.immediate()

        /**
         * An {@link java.util.concurrent.Executor} that executes tasks on the UI thread.
         */
        public val UI_THREAD_EXECUTOR: Executor = AndroidExecutors.uiThread()

        /**
         * Creates a TaskCompletionSource that orchestrates a Task. This allows the creator of a task to
         * be solely responsible for its completion.
         *
         * @return A new TaskCompletionSource.
         */
        platformStatic public fun <TResult> create(): TaskCompletionSource<TResult> {
            return TaskCompletionSource(Task<TResult>())
        }

        /**
         * Creates a completed task with the given value.
         */
        platformStatic public fun <TResult> forResult(value: TResult?): Task<TResult> {
            val tcs = Task.create<TResult>()
            tcs.setResult(value)
            return tcs.task
        }

        /**
         * Creates a faulted task with the given error.
         */
        platformStatic public fun <TResult> forError(error: Exception?): Task<TResult> {
            val tcs = Task.create<TResult>()
            tcs.setError(error)
            return tcs.task
        }

        /**
         * Creates a cancelled task.
         */
        platformStatic public fun <TResult> cancelled(): Task<TResult> {
            val tcs = Task.create<TResult>()
            tcs.setCancelled()
            return tcs.task
        }

        /**
         * Invokes the callable on a background thread, returning a Task to represent the operation.
         */
        platformStatic public fun <TResult> callInBackground(callable: Callable<TResult>): Task<TResult> {
            return call(callable, BACKGROUND_EXECUTOR)
        }

        /**
         * Invokes the callable using the given executor, returning a Task to represent the operation.
         */
        platformStatic public fun <TResult> call(callable: Callable<TResult>, executor: Executor): Task<TResult> {
            val tcs = Task.create<TResult>()
            executor.execute(object : Runnable {
                override fun run() {
                    try {
                        tcs.setResult(callable.call())
                    } catch (e: Exception) {
                        tcs.setError(e)
                    }

                }
            })
            return tcs.task
        }

        /**
         * Invokes the callable on the current thread, producing a Task.
         */
        platformStatic public fun <TResult> call(callable: Callable<TResult>): Task<TResult> {
            return call(callable, IMMEDIATE_EXECUTOR)
        }

        /**
         * Creates a task that completes when all of the provided tasks are complete.
         *
         * @param tasks The tasks that the return value will wait for before completing.
         * @return A Task that will resolve to {@code Void} when all the tasks are resolved. If a single
         * task fails, it will resolve to that {@link java.lang.Exception}. If multiple tasks fail, it
         * will resolve to an {@link AggregateException} of all the {@link java.lang.Exception}s.
         */
        platformStatic public fun whenAll(tasks: Collection<Task<*>>): Task<Void> {
            if (tasks.size() == 0) {
                return Task.forResult<Void>(null)
            }

            val allFinished = Task.create<Void>()
            val causes = ArrayList<Exception>()
            val errorLock = Object()
            val count = AtomicInteger(tasks.size())
            val isCancelled = AtomicBoolean(false)

            for (task in tasks) {
                [suppress("UNCHECKED_CAST")]
                val t = task as Task<Any>
                t.continueWith<Void>(object : Continuation<Any, Void> {
                    override fun then(task: Task<Any>): Void? {
                        if (task.isFaulted()) {
                            synchronized (errorLock) {
                                causes.add(task.getError())
                            }
                        }

                        if (task.isCancelled()) {
                            isCancelled.set(true)
                        }

                        if (count.decrementAndGet() == 0) {
                            if (causes.size() != 0) {
                                if (causes.size() == 1) {
                                    allFinished.setError(causes.get(0))
                                } else {
                                    val throwables = causes.toArray<Throwable>(arrayOfNulls<Throwable>(causes.size()))
                                    val error = AggregateException("There were " + causes.size() + " exceptions.", throwables)
                                    allFinished.setError(error)
                                }
                            } else if (isCancelled.get()) {
                                allFinished.setCancelled()
                            } else {
                                allFinished.setResult(null)
                            }
                        }
                        return null
                    }
                })
            }

            return allFinished.task
        }

        /**
         * Handles the non-async (i.e. the continuation doesn't return a Task) continuation case, passing
         * the results of the given Task through to the given continuation and using the results of that
         * call to set the result of the TaskContinuationSource.
         *
         * @param tcs          The TaskContinuationSource that will be orchestrated by this call.
         * @param continuation The non-async continuation.
         * @param task         The task being completed.
         * @param executor     The executor to use when running the continuation (allowing the continuation to be
         *                     scheduled on a different thread).
         */
        private fun <TContinuationResult, TResult> completeImmediately(tcs: TaskCompletionSource<TContinuationResult>, continuation: Continuation<TResult, TContinuationResult>, task: Task<TResult>, executor: Executor) {
            executor.execute(object : Runnable {
                override fun run() {
                    try {
                        val result = continuation.then(task)
                        tcs.setResult(result)
                    } catch (e: Exception) {
                        tcs.setError(e)
                    }

                }
            })
        }

        /**
         * Handles the async (i.e. the continuation does return a Task) continuation case, passing the
         * results of the given Task through to the given continuation to get a new Task. The
         * TaskCompletionSource's results are only set when the new Task has completed, unwrapping the
         * results of the task returned by the continuation.
         *
         * @param tcs          The TaskContinuationSource that will be orchestrated by this call.
         * @param continuation The async continuation.
         * @param task         The task being completed.
         * @param executor     The executor to use when running the continuation (allowing the continuation to be
         *                     scheduled on a different thread).
         */
        private fun <TContinuationResult, TResult> completeAfterTask(tcs: TaskCompletionSource<TContinuationResult>, continuation: Continuation<TResult, Task<TContinuationResult>>, task: Task<TResult>, executor: Executor) {
            executor.execute(object : Runnable {
                override fun run() {
                    try {
                        val result = continuation.then(task)
                        if (result == null) {
                            tcs.setResult(null)
                        } else {
                            result.continueWith<Void>(object : Continuation<TContinuationResult, Void> {
                                override fun then(task: Task<TContinuationResult>): Void? {
                                    if (task.isCancelled()) {
                                        tcs.setCancelled()
                                    } else if (task.isFaulted()) {
                                        tcs.setError(task.getError())
                                    } else {
                                        tcs.setResult(task.getResult())
                                    }
                                    return null
                                }
                            })
                        }
                    } catch (e: Exception) {
                        tcs.setError(e)
                    }

                }
            })
        }
    }
}
