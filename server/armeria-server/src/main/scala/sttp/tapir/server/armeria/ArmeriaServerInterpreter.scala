package sttp.tapir.server.armeria

import com.linecorp.armeria.common.multipart.Multipart
import com.linecorp.armeria.common.{HttpData, HttpRequest, HttpResponse, HttpStatus}
import com.linecorp.armeria.server.{HttpServiceWithRoutes, Route, ServiceRequestContext}
import java.util.concurrent.CompletableFuture
import java.util.{Set => JSet}
import org.reactivestreams.Publisher
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import sttp.monad.FutureMonad
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.RequestResult
import sttp.tapir.server.interpreter.{BodyListener, ServerInterpreter}

trait ArmeriaServerInterpreter {

  def armeriaServerOptions: ArmeriaFutureServerOptions = ArmeriaFutureServerOptions.default

  def toRoute(serverEndpoint: ServerEndpoint[ArmeriaStreams, Future]): HttpServiceWithRoutes =
    toRoute(List(serverEndpoint))

  def toRoute(serverEndpoints: List[ServerEndpoint[ArmeriaStreams, Future]]): HttpServiceWithRoutes =
    new TapirService(serverEndpoints, armeriaServerOptions)
}

object ArmeriaServerInterpreter extends ArmeriaServerInterpreter {
  def apply(serverOptions: ArmeriaFutureServerOptions = ArmeriaFutureServerOptions.default): ArmeriaServerInterpreter = {
    new ArmeriaServerInterpreter {
      override def armeriaServerOptions: ArmeriaFutureServerOptions = serverOptions
    }
  }
}

private object ArmeriaStreamCompatible extends StreamCompatible[ArmeriaStreams] {
  override val streams: ArmeriaStreams = ArmeriaStreams

  override def fromArmeriaStream(s: Publisher[HttpData]): Publisher[HttpData] = s

  override def asStreamMessage(s: Publisher[HttpData]): Publisher[HttpData] = s
}
