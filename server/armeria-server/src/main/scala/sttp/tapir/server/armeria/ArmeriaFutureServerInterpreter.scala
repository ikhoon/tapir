package sttp.tapir.server.armeria

import scala.concurrent.Future
import sttp.tapir.server.ServerEndpoint

trait ArmeriaFutureServerInterpreter extends ArmeriaServerInterpreter[ArmeriaStreams, Future] {

  def armeriaServerOptions: ArmeriaFutureServerOptions = ArmeriaFutureServerOptions.default

  def toRoute(serverEndpoints: List[ServerEndpoint[ArmeriaStreams, Future]]): TapirService[ArmeriaStreams, Future] =
    TapirFutureService(serverEndpoints, armeriaServerOptions)
}

object ArmeriaFutureServerInterpreter extends ArmeriaFutureServerInterpreter {
  def apply(serverOptions: ArmeriaFutureServerOptions = ArmeriaFutureServerOptions.default): ArmeriaFutureServerInterpreter = {
    new ArmeriaFutureServerInterpreter {
      override def armeriaServerOptions: ArmeriaFutureServerOptions = serverOptions
    }
  }
}
