package io.rinhabackend.core

import io.rinhabackend.models.*
import sttp.model.*
import zio.*
import zio.logging.LogFormat.*
import zio.logging.{ConsoleLoggerConfig, LogFilter, consoleLogger}

private val logFormat = level |-| line |-| cause
private val logConfig = ConsoleLoggerConfig(logFormat, LogFilter.LogLevelByNameConfig(LogLevel.Error))
val logger            = consoleLogger(logConfig)

case class AppConfig(
  private val defaultUrl: String,
  private val fallbackUrl: String,
  valkeyHost: String,
  valkeyPort: Int,
  workerBatchParallelism: Int,
  workerBatchIntervalMS: Int,
):
  val defaultProcessorUrl  = Uri.unsafeParse(defaultUrl)
  val fallbackProcessorUrl = Uri.unsafeParse(fallbackUrl)

object AppConfig:
  given config: Config[AppConfig] =
    (Config.string("PAYMENT_PROCESSOR_DEFAULT_URL") ++ Config.string("PAYMENT_PROCESSOR_FALLBACK_URL") ++ Config.string(
      "VALKEY_HOST",
    ) ++ Config.int("VALKEY_PORT") ++ Config.int("WORKER_BATCH_PARALLELISM") ++ Config.int(
      "WORKER_BATCH_INTERVAL_MS",
    )).map { case (defaultUrl, fallbackUrl, valkeyHost, valkeyPort, batchPar, batchInterval) =>
      AppConfig(defaultUrl, fallbackUrl, valkeyHost, valkeyPort, batchPar, batchInterval)
    }
