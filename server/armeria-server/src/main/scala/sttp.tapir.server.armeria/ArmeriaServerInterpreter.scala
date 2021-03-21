package sttp.tapir.server.armeria

import com.linecorp.armeria.common.{HttpRequest, HttpResponse}
import com.linecorp.armeria.server.{HttpServiceWithRoutes, Route, ServiceRequestContext}
import java.util
import scala.concurrent.Future
import sttp.tapir.Endpoint
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.internal.DecodeInputs

trait ArmeriaServerInterpreter {

  def toRoute[I, E, O](e: Endpoint[I, E, O, ArmeriaStreams])(logic: I => Future[Either[E, O]]): HttpServiceWithRoutes = {
    toRoute(e.serverLogic(logic))
  }

  def toRoute[I, E, O](e: ServerEndpoint[I, E, O, ArmeriaStreams, Future]): HttpServiceWithRoutes = {
    new HttpServiceWithRoutes {
      override def routes(): util.Set[Route] = ???

      override def serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse = {
        DecodeInputs(e.input, new ArmeriaDecodeInputsContext(ctx))
        // TODO(ikhoon): Implment here
        ???
      }
    }

  }
}

object ArmeriaServerInterpreter extends ArmeriaServerInterpreter
