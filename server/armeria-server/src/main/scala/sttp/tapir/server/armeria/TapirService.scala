package sttp.tapir.server.armeria

import com.linecorp.armeria.common.{HttpRequest, HttpResponse}
import com.linecorp.armeria.server.{HttpServiceWithRoutes, Route, ServiceRequestContext}
import java.util.concurrent.CompletableFuture
import java.util.{Set => JSet}
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import sttp.monad.FutureMonad
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interpreter.{BodyListener, ServerInterpreter}

private final class TapirService(
    serverEndpoints: List[ServerEndpoint[ArmeriaStreams, Future]],
    armeriaServerOptions: ArmeriaFutureServerOptions
) extends HttpServiceWithRoutes {

  // TODO(ikhoon): Use upstream's ExchangeType to optimize performance for non-streaming requests
  //               if https://github.com/line/armeria/pull/3956 is merged.
  private val routesMap: Map[Route, ExchangeType.Value] = serverEndpoints.flatMap(se => RouteMapping.toRoute(se.endpoint)).toMap
  override val routes: JSet[Route] = routesMap.keySet.asJava

  override def serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse = {
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutorService(ctx.eventLoop())
    implicit val monad: FutureMonad = new FutureMonad()
    implicit val bodyListener: BodyListener[Future, ArmeriaResponseType] = new ArmeriaBodyListener

    val serverRequest = new ArmeriaServerRequest(ctx)
    val interpreter = new ServerInterpreter(
      serverEndpoints,
      new ArmeriaToResponseBody(ArmeriaStreamCompatible),
      armeriaServerOptions.interceptors,
      file => armeriaServerOptions.deleteFile(ctx, file)
    )

    val requestBody: ArmeriaRequestBody[Future, ArmeriaStreams] =
      new ArmeriaRequestBody(ctx, armeriaServerOptions, FromFuture.identity, ArmeriaStreamCompatible)

    val future = new CompletableFuture[HttpResponse]()
    interpreter(serverRequest, requestBody)
      .map(ResultMapping.toArmeria)
      .onComplete {
        case Failure(exception) =>
          future.completeExceptionally(exception)
        case Success(value) =>
          future.complete(value)
      }
    HttpResponse.from(future)
  }
}
