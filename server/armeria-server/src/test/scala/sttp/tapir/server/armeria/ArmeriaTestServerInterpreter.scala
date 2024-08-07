package sttp.tapir.server.armeria

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import com.linecorp.armeria.server.Server
import sttp.capabilities.Streams
import sttp.tapir.server.tests.TestServerInterpreter
import sttp.tapir.tests._

import scala.concurrent.duration._

trait ArmeriaTestServerInterpreter[S <: Streams[S], F[_], OPTIONS] extends TestServerInterpreter[F, S, OPTIONS, TapirService[S, F]] {

  override def server(
      routes: NonEmptyList[TapirService[S, F]],
      gracefulShutdownTimeout: Option[FiniteDuration]
  ): Resource[IO, Port] = {
    val (quietPeriodMs, totalDeadlineMs) = gracefulShutdownTimeout
      .map(t => (t.toMillis, t.toMillis + 50))
      .getOrElse((0L, 0L))

    val bind = IO.fromCompletableFuture(
      IO {
        val serverBuilder = Server
          .builder()
          .maxRequestLength(0)
          .connectionDrainDurationMicros(0)
          .gracefulShutdownTimeoutMillis(quietPeriodMs, totalDeadlineMs)
        routes.foldLeft(serverBuilder)((sb, route) => sb.service(route))
        val server = serverBuilder.build()
        server.start().thenApply[Server](_ => server)
      }
    )
    // Ignore future returned by stop() for fast test iterations.
    // Armeria server wait for 2 seconds by default to let the boss group gracefully finish all remaining
    // tasks in the queue. Even if graceful shutdown timeouts are set to 0.
    Resource
      .make(bind)(s => IO.blocking { val _ = s.stop() })
      .map(_.activeLocalPort())
  }
}
