package sttp.tapir.server.armeria.zio

import _root_.zio._
import sttp.capabilities.zio.ZioStreams
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.armeria.{ArmeriaServerInterpreter, TapirService}

trait ArmeriaZioServerInterpreter[R] extends ArmeriaServerInterpreter[ZioStreams, RIO[R, *]] {

  implicit def runtime: Runtime[R]

  def armeriaServerOptions: ArmeriaZioServerOptions[RIO[R, *]]

  def toRoute(serverEndpoints: List[ServerEndpoint[ZioStreams, RIO[R, *]]]): TapirService[ZioStreams, RIO[R, *]] =
    TapirZioService(serverEndpoints, armeriaServerOptions)
}

object ArmeriaZioServerInterpreter {
  def apply[R](
      serverOptions: ArmeriaZioServerOptions[RIO[R, *]] = ArmeriaZioServerOptions.default[R]
  )(implicit _runtime: Runtime[R]): ArmeriaZioServerInterpreter[R] = {
    new ArmeriaZioServerInterpreter[R] {
      override def runtime: Runtime[R] = _runtime
      override def armeriaServerOptions: ArmeriaZioServerOptions[RIO[R, *]] = serverOptions
    }
  }
}
