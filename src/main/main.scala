package io.rinhabackend.core

import io.rinhabackend.models.*
import io.rinhabackend.services.*
import zio.*
import zio.http.*
import zio.http.codec.*
import zio.http.endpoint.*
import zio.schema.*

object Main extends ZIOAppDefault:
  override val bootstrap = Runtime.removeDefaultLoggers >>> logger

  def consumer(q: Queue[Transaction]): ZIO[PaymentService, String, Unit] = for
    transaction <- q.take
    payment = Payment.from(transaction)
    ps <- ZIO.service[PaymentService]
    _  <- ps.sendToProcessorAndDb(payment)
  yield ()


  // routes
  def routes(transactions: Queue[Transaction]) =
    val paymentsRoute = Endpoint(RoutePattern.POST / "payments")
      .in[Transaction]
      .out[Unit]
      .outError[String](Status.InternalServerError)
      .implement { transaction =>
        transactions.offer(transaction).unit
      }

    val paymentsSummaryRoute = Endpoint(RoutePattern.GET / "payments-summary")
      .query(HttpCodec.query[Option[String]]("from") ++ HttpCodec.query[Option[String]]("to"))
      .out[PaymentSummary]
      .outError[String](Status.InternalServerError)
      .implement {
        case (Some(from), Some(to)) => ZIO.service[PaymentService].flatMap(ps => ps.summary(from, to))
        case (_, _)                 => ZIO.service[PaymentService].flatMap(ps => ps.summary())
      }

    Routes(paymentsRoute, paymentsSummaryRoute)

  val app = for
    transactions <- Queue.unbounded[Transaction]
    _            <- consumer(transactions).schedule(Schedule.spaced(1.millis)).forkDaemon
    server       <- Server.serve(routes(transactions))
  yield ()

  val run = app.provide(
    Server.default,
    PaymentService.live,
    ValkeyClientLive.live,
    ValkeyService.live,
    PaymentProcessorManager.live,
  )
