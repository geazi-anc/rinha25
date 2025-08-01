package io.rinhabackend.models

import io.rinhabackend.core.*
import sttp.model.*
import zio.*
import zio.json.*
import zio.schema.*

final case class Transaction(correlationId: String, amount: Double)

final case class Payment(correlationId: String, amount: Double, requestedAt: String)

final case class Processor(id: String, url: Uri)

final case class PaymentProcessorManager(primary: Processor, secundary: Processor):
  def setPrimary(processor: Processor): PaymentProcessorManager =
    if processor.id == primary.id then this else copy(primary = processor, secundary = primary)

final case class ResponseDetail(statusCode: StatusCode, body: String)

final case class RequestDetail(payment: Payment, processor: Processor)

final case class RequestResponseDetail(request: RequestDetail, response: ResponseDetail)

final case class HealthResponse(failing: Boolean, minResponseTime: Int)

final case class HealthStatus(processor: Processor, failing: Boolean, minResponseTime: Int)

//nome das variáveis curtos para economizar bytes na string json gerada quando enviar ao valkey
final case class Member(c: String, r: Long, a: Double)

final case class ProcessorSummary(totalRequests: Int, totalAmount: Double)

final case class PaymentSummary(default: ProcessorSummary, fallback: ProcessorSummary)

object Transaction:
  given schema: Schema[Transaction] = DeriveSchema.gen[Transaction]

object Payment:
  given encoder: JsonEncoder[Payment] = DeriveJsonEncoder.gen[Payment]
  given decoder: JsonDecoder[Payment] = DeriveJsonDecoder.gen[Payment]

  def from(t: Transaction): Payment =
    Payment(t.correlationId, t.amount, java.time.Instant.now.toString)

  def from(m: Member): Payment =
    Payment(correlationId = m.c, requestedAt = java.time.Instant.ofEpochMilli(m.r).toString, amount = m.a)

  def summary(payments: List[Member]): ProcessorSummary =
    val totalRequest = payments.length
    val totalAmount  = payments.map(_.a).sum
    ProcessorSummary(totalRequest, totalAmount)

object Member:
  given encoder: JsonEncoder[Member] = DeriveJsonEncoder.gen[Member]
  given decoder: JsonDecoder[Member] = DeriveJsonDecoder.gen[Member]

object PaymentSummary:
  given schema: Schema[PaymentSummary] = DeriveSchema.gen[PaymentSummary]

object ProcessorSummary:
  given schema: Schema[ProcessorSummary] = DeriveSchema.gen[ProcessorSummary]

object HealthResponse:
  given encoder: JsonEncoder[HealthResponse] = DeriveJsonEncoder.gen[HealthResponse]
  given decoder: JsonDecoder[HealthResponse] = DeriveJsonDecoder.gen[HealthResponse]
