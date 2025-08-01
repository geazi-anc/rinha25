package io.rinhabackend.core

import io.rinhabackend.models.*
import io.rinhabackend.services.*
import zio.*
import zio.http.*
import zio.http.codec.*
import zio.http.endpoint.*
import zio.schema.*

object Backend extends ZIOAppDefault:
  override val bootstrap = Runtime.disableFlags(RuntimeFlag.FiberRoots) >>> Runtime.removeDefaultLoggers >>> logger

  def consumer(q: Queue[Payment]) = for
    vs      <- ZIO.service[ValkeyService]
    payment <- q.take
    _       <- vs.rpushPayment("payments:all", payment).logError("deu ruim")
  yield ()

  // routes
  def routes(payments: Queue[Payment]) =
    val paymentsRoute = Endpoint(RoutePattern.POST / "payments")
      .in[Transaction]
      .out[Unit]
      .outError[String](Status.InternalServerError)
      .implement { transaction =>
        payments.offer(Payment.from(transaction)).unit
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
    payments <- Queue.unbounded[Payment]
    _        <- consumer(payments).schedule(Schedule.spaced(1.millis)).forkDaemon
    server   <- Server.serve(routes(payments))
  yield ()

  val run = app.provide(
    Server.default,
    PaymentService.live,
    ValkeyClientLive.live,
    ValkeyService.live,
  )
