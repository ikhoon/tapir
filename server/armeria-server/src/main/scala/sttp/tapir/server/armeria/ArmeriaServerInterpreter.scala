package sttp.tapir.server.armeria

import sttp.capabilities.Streams
import sttp.tapir.server.ServerEndpoint

trait ArmeriaServerInterpreter[S <: Streams[S], F[_]] {

  def armeriaServerOptions: ArmeriaServerOptions[F]

  def toRoute(serverEndpoint: ServerEndpoint[S, F]): TapirService[S, F] =
    toRoute(List(serverEndpoint))

  def toRoute(serverEndpoints: List[ServerEndpoint[S, F]]): TapirService[S, F]
}
