package io.rinhabackend.core

import io.rinhabackend.models.*
import io.rinhabackend.services.*
import zio.*

def sender(vs: ValkeyService, ps: PaymentService, ppm: Ref[PaymentProcessorManager], batchSize: Int) = for
  payments <- vs.lpopPayments("payments:all", batchSize).logError("Failed to lpop payments from valkey")
  size = payments.size
  _ <- ZIO.log(s"* Got $size payments with batch $batchSize")
  _ <- ZIO.foreachParDiscard(payments)(p => ps.sendToProcessorAndDb(p, ppm))
yield ()

object Worker extends ZIOAppDefault:
  override val bootstrap = Runtime.enableAutoBlockingExecutor >>> Runtime.disableFlags(
    RuntimeFlag.FiberRoots,
  ) >>> Runtime.removeDefaultLoggers >>> logger

  val app = for
    appConfig <- ZIO.config[AppConfig]
    primary   = Processor("default", appConfig.defaultProcessorUrl)
    secundary = Processor("fallback", appConfig.fallbackProcessorUrl)
    ppm <- Ref.make(PaymentProcessorManager(primary, secundary))
    _   <- PaymentProcessorService.monitor(ppm).repeat(Schedule.spaced(5.seconds)).fork
    ps  <- ZIO.service[PaymentService]
    vs  <- ZIO.service[ValkeyService]
    _   <- sender(vs, ps, ppm, appConfig.workerBatchParallelism).repeat(
      Schedule.spaced(appConfig.workerBatchIntervalMS.millis),
    )
  yield ()

  val run = app.provide(ValkeyService.live, ValkeyClientLive.live, PaymentService.live)
