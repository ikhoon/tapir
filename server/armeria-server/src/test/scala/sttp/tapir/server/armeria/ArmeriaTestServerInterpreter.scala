package sttp.tapir.server.armeria

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import com.linecorp.armeria.common.logging.LogLevel
import com.linecorp.armeria.server.logging.{AccessLogWriter, LoggingService}
import com.linecorp.armeria.server.{HttpServiceWithRoutes, Server}
import scala.concurrent.Future
import scala.reflect.ClassTag
import sttp.tapir.Endpoint
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.decodefailure.{DecodeFailureHandler, DefaultDecodeFailureHandler}
import sttp.tapir.server.interceptor.metrics.MetricsRequestInterceptor
import sttp.tapir.server.tests.TestServerInterpreter
import sttp.tapir.tests.Port

class ArmeriaTestServerInterpreter() extends TestServerInterpreter[Future, ArmeriaStreams, HttpServiceWithRoutes] {
  override def route[I, E, O](
      e: ServerEndpoint[I, E, O, ArmeriaStreams, Future],
      decodeFailureHandler: Option[DecodeFailureHandler] = None,
      metricsInterceptor: Option[MetricsRequestInterceptor[Future]] = None
  ): HttpServiceWithRoutes = {
    val serverOptions: ArmeriaServerOptions = ArmeriaServerOptions.customInterceptors
      .metricsInterceptor(metricsInterceptor)
      .decodeFailureHandler(
        decodeFailureHandler
          .getOrElse(DefaultDecodeFailureHandler.handler)
      )
      .options
    ArmeriaServerInterpreter(serverOptions).toRoutes(e)
  }

  override def route[I, E, O](es: List[ServerEndpoint[I, E, O, ArmeriaStreams, Future]]): HttpServiceWithRoutes =
    ArmeriaServerInterpreter().toRoutes(es)

  override def routeRecoverErrors[I, E <: Throwable, O](e: Endpoint[I, E, O, ArmeriaStreams], fn: I => Future[O])(implicit
      eClassTag: ClassTag[E]
  ): HttpServiceWithRoutes = {
    ArmeriaServerInterpreter().toRoutesRecoverErrors(e)(fn)
  }

  override def server(routes: NonEmptyList[HttpServiceWithRoutes]): Resource[IO, Port] = {
    val bind = IO.fromCompletableFuture(
      IO {
        val serverBuilder = Server
          .builder()
          .maxRequestLength(0)
          .accessLogWriter(AccessLogWriter.combined(), true)
          .decorator(LoggingService.builder().requestLogLevel(LogLevel.INFO).newDecorator())
        routes.foldLeft(serverBuilder)((sb, route) => sb.service(route))
        val server = serverBuilder.build()
        server.start().thenApply(_ => server)
      }
    )
    Resource.make(bind)(binding => IO.fromCompletableFuture(IO(binding.stop())).void).map(_.activeLocalPort())
  }
}
