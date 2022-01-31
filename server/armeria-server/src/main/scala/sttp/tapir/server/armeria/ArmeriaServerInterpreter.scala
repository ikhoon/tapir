package sttp.tapir.server.armeria

import com.linecorp.armeria.common.multipart.Multipart
import com.linecorp.armeria.common.{HttpRequest, HttpResponse, HttpStatus}
import com.linecorp.armeria.server.{HttpServiceWithRoutes, Route, ServiceRequestContext}
import java.util.concurrent.CompletableFuture
import java.util.{Set => JSet}
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import sttp.monad.FutureMonad
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.RequestResult
import sttp.tapir.server.interpreter.{BodyListener, ServerInterpreter}

trait ArmeriaServerInterpreter {

  def armeriaServerOptions: ArmeriaServerOptions = ArmeriaServerOptions.default

  def toRoute(serverEndpoint: ServerEndpoint[ArmeriaStreams, Future]): HttpServiceWithRoutes =
    toRoute(List(serverEndpoint))

  def toRoute(serverEndpoints: List[ServerEndpoint[ArmeriaStreams, Future]]): HttpServiceWithRoutes =
    new TapirService(serverEndpoints, armeriaServerOptions)
}

object ArmeriaServerInterpreter extends ArmeriaServerInterpreter {
  def apply(serverOptions: ArmeriaServerOptions = ArmeriaServerOptions.default): ArmeriaServerInterpreter = {
    new ArmeriaServerInterpreter {
      override def armeriaServerOptions: ArmeriaServerOptions = serverOptions
    }
  }
}

private final class TapirService(serverEndpoints: List[ServerEndpoint[ArmeriaStreams, Future]], armeriaServerOptions: ArmeriaServerOptions)
    extends HttpServiceWithRoutes {

  // TODO(ikhoon): Use upstream's ExchangeType to optimize performance for non-streaming requests
  //               if https://github.com/line/armeria/pull/3956 is merged.
  private val routesMap: Map[Route, ExchangeType.Value] = serverEndpoints.flatMap(se => RouteMapping.toRoute(se.endpoint)).toMap
  override val routes: JSet[Route] = routesMap.keySet.asJava

  override def serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse = {
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutorService(ctx.eventLoop())
    implicit val monad: FutureMonad = new FutureMonad()
    implicit val bodyListener: BodyListener[Future, ArmeriaResponseType] = new ArmeriaBodyListener

    val serverRequest = new ArmeriaServerRequest(ctx)
    val future = new CompletableFuture[HttpResponse]()
    val interpreter = new ServerInterpreter(
      serverEndpoints,
      new ArmeriaToResponseBody,
      armeriaServerOptions.interceptors,
      armeriaServerOptions.deleteFile(ctx, _)
    )

    interpreter(serverRequest, new ArmeriaRequestBody(ctx, req, armeriaServerOptions))
      .map {
        case RequestResult.Failure(_) =>
          HttpResponse.of(HttpStatus.NOT_FOUND)
        case RequestResult.Response(response) =>
          val headers = HeaderMapping.toArmeria(response.headers, response.code)
          response.body match {
            case None =>
              HttpResponse.of(headers)
            case Some(Right(httpData)) =>
              HttpResponse.of(headers, httpData)
            case Some(Left(stream)) =>
              stream match {
                case multipart: Multipart => multipart.toHttpResponse(headers)
                case _                    => HttpResponse.of(headers, stream)
              }
          }
      }
      .onComplete {
        case Failure(exception) =>
          future.completeExceptionally(exception)
        case Success(value) =>
          future.complete(value)
      }
    HttpResponse.from(future)
  }
}
