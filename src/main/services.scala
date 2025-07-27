package io.rinhabackend.services

import io.rinhabackend.core.*
import io.rinhabackend.models.*
import io.valkey.*
import sttp.client4.*
import sttp.client4.httpclient.zio.HttpClientZioBackend
import sttp.client4.ziojson.*
import sttp.model.*
import zio.*
import zio.json.*

import java.time.Instant
import scala.jdk.CollectionConverters.*


class PaymentService(valkeyService: ValkeyService, paymentProcessorManager: PaymentProcessorManager):
  def addPayment(payment: Payment, processor: Processor): IO[String, RequestResponseDetail] =
    val requestDetail = RequestDetail(payment, processor)

    HttpClientZioBackend()
      .flatMap(backend => basicRequest.post(processor.url.addPath("payments")).body(asJson(payment)).send(backend))
      .map(r => ResponseDetail(r.code, r.body.getOrElse(s"Request error, status = ${r.code}")))
      .map(responseDetail => RequestResponseDetail(requestDetail, responseDetail))
      .mapError(e => e.toString)

  def sendToProcessor(payment: Payment): IO[String, RequestResponseDetail] = for
    ppm <- ZIO.succeed(paymentProcessorManager)
    pr1 = ZIO.log(s"= Send payment to ${ppm.primary.id}") *> addPayment(payment, ppm.primary)
    pr2 = pr1.filterOrFail(_.response.statusCode.isSuccess)("Payment processor is unavailable")
    sr1 = pr2.orElse(ZIO.log(s"Send payment to ${ppm.secundary.id}") *> addPayment(payment, ppm.secundary))
    sr2 = sr1.filterOrFail(_.response.statusCode.isSuccess)("Payment processor is unavailable")
    r1 <- sr2.retryOrElse(
      Schedule.recurs(10) && Schedule.exponential(100.milliseconds).jittered,
      (e, r) =>
        ZIO.fail(
          s"Unable to send payment ${payment.correlationId} to payment processors after ${r._2.getSeconds} seconds, both are unavailable",
        ),
    )
  yield r1
  end sendToProcessor

  def sendToProcessorAndDb(payment: Payment): IO[String, Unit] = for
    detail  <- sendToProcessor(payment)
    _       <- ZIO.log(s"Payment ${payment.correlationId} has been successfully sent to ${detail.request.processor.id}")
    detail2 <- valkeyService.zaddPayment(payment, detail.request.processor.id)
    _       <- ZIO.log(s"Payment ${payment.correlationId} has been successfully sent to valkey")
  yield ()

  def summary(from: String, to: String): IO[String, PaymentSummary] = (
    for
      start            <- ZIO.attempt(Instant.parse(from).toEpochMilli.toLong.toDouble)
      end              <- ZIO.attempt(Instant.parse(to).toEpochMilli.toLong.toDouble)
      vs               <- ZIO.succeed(valkeyService)
      defaultPayments  <- vs.zrangePaymentsByScores("payments:default", start, end)
      fallbackPayments <- vs.zrangePaymentsByScores("payments:fallback", start, end)
      defaultSummary  = Payment.summary(defaultPayments)
      fallbackSummary = Payment.summary(fallbackPayments)
      paymentsSummary = PaymentSummary(defaultSummary, fallbackSummary)
    yield paymentsSummary
  ).mapError(e => e.toString)

  def summary(): IO[String, PaymentSummary] = (
    for
      vs               <- ZIO.succeed(valkeyService)
      defaultPayments  <- vs.zrangePayments("payments:default")
      fallbackPayments <- vs.zrangePayments("payments:fallback")
      defaultSummary  = Payment.summary(defaultPayments)
      fallbackSummary = Payment.summary(fallbackPayments)
      paymentsSummary = PaymentSummary(defaultSummary, fallbackSummary)
    yield paymentsSummary
  ).mapError(e => e.toString)
end PaymentService

class ValkeyService(valkeyClient: JedisPool):
  private val client = valkeyClient

  def zaddPayment(payment: Payment, processorId: String): IO[String, Unit] = ZIO
    .attempt(client.getResource())
    .flatMap: client =>
      val key    = s"payments:${processorId}"
      val score  = java.time.Instant.parse(payment.requestedAt).toEpochMilli.toLong.toDouble
      val member = Member(c = payment.correlationId, r = score.toLong, a = payment.amount).toJson
      ZIO
        .attemptBlocking(client.zadd(key, score, member))
        .map(_ => client.close())
        .onError(_ => ZIO.succeed(client.close()))
        .unit
    .mapError(e => e.toString)

  def zrangePaymentsByScores(key: String, start: Double, end: Double): IO[String, List[Member]] = ZIO
    .attempt(client.getResource())
    .flatMap(client =>
      ZIO
        .attemptBlocking(client.zrangeByScore(key, start, end))
        .map(e => { client.close(); e })
        .onError(_ => ZIO.succeed(client.close())),
    )
    .map(a => a.asScala.toList.map(p => ZIO.fromEither(p.fromJson[Member])))
    .flatMap(a => ZIO.collectAll(a))
    .mapError(e => e.toString)

  def zrangePayments(key: String): IO[String, List[Member]] = ZIO
    .attempt(client.getResource())
    .flatMap(client =>
      ZIO
        .attemptBlocking(client.zrange(key, 0, -1).asScala.toList)
        .map(e => { client.close(); e })
        .onError(_ => ZIO.succeed(client.close())),
    )
    .map(a => a.map(p => ZIO.fromEither(p.fromJson[Member])))
    .flatMap(a => ZIO.collectAll(a))
    .mapError(e => e.toString)

end ValkeyService

object PaymentService:
  val live = ZLayer.fromFunction(PaymentService.apply)

object ValkeyService:
  val live = ZLayer.fromFunction(ValkeyService.apply)

object ValkeyClientLive:
  val live = ZLayer:
    ZIO
      .config[AppConfig]
      .flatMap: appConfig =>
        val config = JedisPoolConfig()
        config.setMaxTotal(32);
        config.setMaxIdle(32)
        config.setMinIdle(16)
        config.setMaxWait(java.time.Duration.ofMillis(500))

        ZIO.attemptBlocking(JedisPool(config, appConfig.valkeyHost, appConfig.valkeyPort))
