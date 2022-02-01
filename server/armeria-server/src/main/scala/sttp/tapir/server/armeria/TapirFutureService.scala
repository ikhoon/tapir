package sttp.tapir.server.armeria

import com.linecorp.armeria.common.{HttpData, HttpRequest, HttpResponse}
import com.linecorp.armeria.server.ServiceRequestContext
import java.util.concurrent.CompletableFuture
import org.reactivestreams.Publisher
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import sttp.monad.FutureMonad
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interpreter.{BodyListener, ServerInterpreter}

private[armeria] final case class TapirFutureService(
    serverEndpoints: List[ServerEndpoint[ArmeriaStreams, Future]],
    armeriaServerOptions: ArmeriaFutureServerOptions
) extends TapirService[ArmeriaStreams, Future] {

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

private object ArmeriaStreamCompatible extends StreamCompatible[ArmeriaStreams] {
  override val streams: ArmeriaStreams = ArmeriaStreams

  override def fromArmeriaStream(s: Publisher[HttpData]): Publisher[HttpData] = s

  override def asStreamMessage(s: Publisher[HttpData]): Publisher[HttpData] = s
}
