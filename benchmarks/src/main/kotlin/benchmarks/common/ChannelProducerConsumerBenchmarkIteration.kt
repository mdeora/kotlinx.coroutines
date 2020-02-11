/*
 * Copyright 2016-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package benchmarks.common

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.scheduling.*
import kotlinx.coroutines.selects.*
import java.util.concurrent.*

abstract class ChannelProducerConsumerBenchmarkIteration(private val channelCreator: ChannelCreator,
                                                         private val withSelect: Boolean,
                                                         private val producers: Int,
                                                         private val consumers: Int,
                                                         dispatcherCreator: DispatcherCreator,
                                                         parallelism: Int,
                                                         private val approximateBatchSize: Int) {
    private val channel: Channel<Int> = channelCreator.create()
    val dispatcher = dispatcherCreator.create(parallelism)

    fun run() {
        val totalMessages = approximateBatchSize / (producers * consumers) * (producers * consumers)
        val phaser = Phaser(producers + consumers + 1)
        // Run producers
        repeat(producers) { coroutineNumber ->
            GlobalScope.launch(dispatcher) {
                val dummy = if (withSelect) channelCreator.create() else null
                repeat(totalMessages / producers) {
                    produce(it, dummy, coroutineNumber)
                }
                phaser.arrive()
            }
        }
        // Run consumers
        repeat(consumers) { coroutineNumber ->
            GlobalScope.launch(dispatcher) {
                val dummy = if (withSelect) channelCreator.create() else null
                repeat(totalMessages / consumers) {
                    consume(dummy, coroutineNumber)
                }
                phaser.arrive()
            }
        }
        // Wait until the work is done
        phaser.arriveAndAwaitAdvance()
    }

    private suspend fun produce(element: Int, dummy: Channel<Int>?, coroutineNumber: Int) {
        if (withSelect) {
            select<Unit> {
                channel.onSend(element) {}
                dummy!!.onReceive {}
            }
        } else {
            channel.send(element)
        }
        doProducerWork(coroutineNumber)
    }

    private suspend fun consume(dummy: Channel<Int>?, coroutineNumber: Int) {
        if (withSelect) {
            select<Unit> {
                channel.onReceive {}
                dummy!!.onReceive {}
            }
        } else {
            channel.receive()
        }
        doConsumerWork(coroutineNumber)
    }

    abstract fun doProducerWork(id: Int)
    abstract fun doConsumerWork(id: Int)
}

enum class DispatcherCreator(val create: (parallelism: Int) -> CoroutineDispatcher) {
    ForkJoin({ parallelism ->  ForkJoinPool(parallelism).asCoroutineDispatcher() }),
    Experimental({ parallelism -> ExperimentalCoroutineDispatcher(corePoolSize = parallelism, maxPoolSize = parallelism) })
}

enum class ChannelCreator(private val capacity: Int) {
    Rendezvous(Channel.RENDEZVOUS),
    `Buffered(16)`(16),
    `Buffered(128)`(128),
    Unlimited(Channel.UNLIMITED);

    fun create(): Channel<Int> = Channel(capacity)
}