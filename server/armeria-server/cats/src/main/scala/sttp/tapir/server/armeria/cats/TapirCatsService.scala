package sttp.tapir.server.armeria.cats

import cats.effect.Async
import cats.effect.std.Dispatcher
import com.linecorp.armeria.common.{HttpRequest, HttpResponse}
import com.linecorp.armeria.server.{HttpServiceWithRoutes, Route, ServiceRequestContext}
import java.util.concurrent.CompletableFuture
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}
import sttp.capabilities.fs2.Fs2Streams
import sttp.monad.MonadError
import sttp.tapir.integ.cats.CatsMonadError
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.armeria._
import sttp.tapir.server.interpreter.{BodyListener, ServerInterpreter}
import java.util.{Set => JSet}
import scala.collection.JavaConverters._
import sttp.monad.syntax._

private[cats] final class TapirCatsService[F[_]: Async](
    serverEndpoints: List[ServerEndpoint[Fs2Streams[F], F]],
    armeriaServerOptions: ArmeriaCatsServerOptions[F]
) extends HttpServiceWithRoutes {

  // TODO(ikhoon): Use upstream's ExchangeType to optimize performance for non-streaming requests
  //               if https://github.com/line/armeria/pull/3956 is merged.
  private val routesMap: Map[Route, ExchangeType.Value] = serverEndpoints.flatMap(se => RouteMapping.toRoute(se.endpoint)).toMap
  override val routes: JSet[Route] = routesMap.keySet.asJava

  private val dispatcher: Dispatcher[F] = armeriaServerOptions.dispatcher
  private val fs2StreamCompatible: StreamCompatible[Fs2Streams[F]] = Fs2StreamCompatible(dispatcher)

  private implicit val monad: MonadError[F] = new CatsMonadError()
  private implicit val bodyListener: BodyListener[F, ArmeriaResponseType] = new ArmeriaBodyListener

  override def serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse = {
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutorService(ctx.eventLoop())

    val catsFFromFuture = new CatsFromFuture
    val serverRequest = new ArmeriaServerRequest(ctx)
    val interpreter = new ServerInterpreter(
      serverEndpoints,
      new ArmeriaToResponseBody(fs2StreamCompatible),
      armeriaServerOptions.interceptors,
      file => catsFFromFuture(armeriaServerOptions.deleteFile(ctx, file))
    )

    val requestBody = new ArmeriaRequestBody(ctx, armeriaServerOptions, catsFFromFuture, fs2StreamCompatible)
    val future = new CompletableFuture[HttpResponse]()
    val result = interpreter(serverRequest, requestBody).map(ResultMapping.toArmeria)
    val (response, cancelRef) = dispatcher.unsafeToFutureCancelable(result)
    response.onComplete {
      case Failure(exception) =>
        future.completeExceptionally(exception)
      case Success(value) =>
        future.complete(value)
    }

    val httpResponse = HttpResponse.from(future)
    httpResponse
      .whenComplete()
      .asInstanceOf[CompletableFuture[Unit]]
      .exceptionally { case (_: Throwable) =>
        cancelRef()
        ()
      }
    httpResponse
  }
}
