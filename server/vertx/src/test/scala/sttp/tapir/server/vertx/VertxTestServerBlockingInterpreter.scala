package sttp.tapir.server.vertx

import io.vertx.core.Vertx
import io.vertx.ext.web.{Route, Router}
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.decodefailure.{DecodeFailureHandler, DefaultDecodeFailureHandler}
import sttp.tapir.server.interceptor.metrics.MetricsRequestInterceptor

import scala.concurrent.Future

class VertxTestServerBlockingInterpreter(vertx: Vertx) extends VertxTestServerInterpreter(vertx) {
  override def route(
      e: ServerEndpoint[Any, Future],
      decodeFailureHandler: Option[DecodeFailureHandler],
      metricsInterceptor: Option[MetricsRequestInterceptor[Future]] = None
  ): Router => Route = {
    val options: VertxFutureServerOptions = VertxFutureServerOptions.customInterceptors
      .metricsInterceptor(metricsInterceptor)
      .decodeFailureHandler(decodeFailureHandler.getOrElse(DefaultDecodeFailureHandler.default))
      .options
    VertxFutureServerInterpreter(options).blockingRoute(e)
  }
}
