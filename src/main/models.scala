package io.rinhabackend.models

import io.rinhabackend.core.*
import sttp.model.*
import zio.*
import zio.json.*
import zio.schema.*

final case class Transaction(correlationId: String, amount: Double)

final case class Payment(correlationId: String, amount: Double, requestedAt: String)

final case class Processor(id: String, url: Uri)

final case class PaymentProcessorManager(primary: Processor, secundary: Processor)

final case class ResponseDetail(statusCode: StatusCode, body: String)

final case class RequestDetail(payment: Payment, processor: Processor)

final case class RequestResponseDetail(request: RequestDetail, response: ResponseDetail)

//nome das variáveis curtos para economizar bytes na string json gerada quando enviar ao valkey
final case class Member(c: String, r: Long, a: Double)

final case class ProcessorSummary(totalRequest: Int, totalAmount: Double)

final case class PaymentSummary(default: ProcessorSummary, fallback: ProcessorSummary)

object Transaction:
  given schema: Schema[Transaction] = DeriveSchema.gen[Transaction]

object Payment:
  given encoder: JsonEncoder[Payment] = DeriveJsonEncoder.gen[Payment]
  given decoder: JsonDecoder[Payment] = DeriveJsonDecoder.gen[Payment]

  def from(t: Transaction): Payment =
    Payment(t.correlationId, t.amount, java.time.Instant.now.toString)

  def summary(payments: List[Member]): ProcessorSummary =
    val totalRequest = payments.length
    val totalAmount  = payments.map(_.a).sum
    ProcessorSummary(totalRequest, totalAmount)

object Member:
  given encoder: JsonEncoder[Member] = DeriveJsonEncoder.gen[Member]
  given decoder: JsonDecoder[Member] = DeriveJsonDecoder.gen[Member]

object PaymentProcessorManager:
  val live = ZLayer:
    for
      appConfig <- ZIO.config[AppConfig]
      primary   = Processor("default", appConfig.defaultProcessorUrl)
      secundary = Processor("fallback", appConfig.fallbackProcessorUrl)
      ppm       = PaymentProcessorManager(primary, secundary)
    yield ppm

object PaymentSummary:
  given schema: Schema[PaymentSummary] = DeriveSchema.gen[PaymentSummary]

object ProcessorSummary:
  given schema: Schema[ProcessorSummary] = DeriveSchema.gen[ProcessorSummary]
