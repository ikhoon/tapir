package sttp.tapir.server.armeria

import com.linecorp.armeria.common.multipart.Multipart
import com.linecorp.armeria.common.{HttpMethod, HttpRequest, HttpResponse, HttpStatus}
import com.linecorp.armeria.server.{HttpServiceWithRoutes, Route, ServiceRequestContext}
import java.util.concurrent.CompletableFuture
import java.util.{Set => JSet}
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.{Failure, Success}
import sttp.monad.FutureMonad
import sttp.tapir.EndpointInput._
import sttp.tapir.internal.RichEndpointInput
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.RequestResult
import sttp.tapir.server.interpreter.{BodyListener, ServerInterpreter}
import sttp.tapir.{Endpoint, EndpointInput}

trait ArmeriaServerInterpreter {

  def armeriaServerOptions: ArmeriaServerOptions = ArmeriaServerOptions.default

  def toRoutes[I, E, O](e: Endpoint[I, E, O, ArmeriaStreams])(logic: I => Future[Either[E, O]]): HttpServiceWithRoutes = {
    toRoutes(e.serverLogic(logic))
  }

  def toRoutesRecoverErrors[I, E, O](e: Endpoint[I, E, O, ArmeriaStreams])(logic: I => Future[O])(implicit
      eIsThrowable: E <:< Throwable,
      eClassTag: ClassTag[E]
  ): HttpServiceWithRoutes = {
    toRoutes(e.serverLogicRecoverErrors(logic))
  }

  def toRoutes[I, E, O](se: ServerEndpoint[I, E, O, ArmeriaStreams, Future]): HttpServiceWithRoutes = {
    toRoutes(List(se))
  }

  def toRoutes(serverEndpoints: List[ServerEndpoint[_, _, _, ArmeriaStreams, Future]]): HttpServiceWithRoutes = {
    new HttpServiceWithRoutes {
      override val routes: JSet[Route] = serverEndpoints.flatMap(se => toRoute(se.endpoint)).toSet.asJava

      override def serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse = {
        implicit val ec: ExecutionContext = ExecutionContext.fromExecutorService(ctx.eventLoop())
        implicit val monad: FutureMonad = new FutureMonad()
        implicit val bodyListener: BodyListener[Future, ArmeriaResponseType] = new ArmeriaBodyListener

        val serverRequest = new ArmeriaServerRequest(ctx)
        val future = new CompletableFuture[HttpResponse]()
        val interpreter = new ServerInterpreter(
          new ArmeriaRequestBody(ctx, req, armeriaServerOptions),
          new ArmeriaToResponseBody,
          armeriaServerOptions.interceptors,
          armeriaServerOptions.deleteFile(ctx, _)
        )

        interpreter(serverRequest, serverEndpoints)
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
  }

  private def toRoute(e: Endpoint[_, _, _, _]): List[Route] = {
    val inputs: Seq[Basic[_]] = e.input.asVectorOfBasicInputs(false)
    val methods = inputs.collect { case FixedMethod(m, _, _) =>
      HttpMethod.valueOf(m.method)
    }

    toPathPatterns(inputs).map { path =>
      val routeBuilder =
        Route
          .builder()
          .path(path)

      if (methods.nonEmpty) {
        routeBuilder.methods(methods.asJava)
      }
      routeBuilder.build()
    }
  }

  private def toPathPatterns(inputs: Seq[Basic[_]]): List[String] = {
    val useGlobPattern = inputs.exists(x => x.isInstanceOf[EndpointInput.PathsCapture[_]])
    if (useGlobPattern) {
      val pathPattern =
        inputs
          .collect {
            case segment: FixedPath[_] =>
              segment.show
            case PathCapture(_, _, _) =>
              "/*"
            case PathsCapture(_, _) =>
              "/**"
          }
          .mkString("glob:", "", "")
      List(pathPattern)
    } else {
      var idxUsed = 0
      val fragments = inputs.collect {
        case segment: FixedPath[_] =>
          segment.show
        case PathCapture(Some(name), _, _) =>
          s"/:$name"
        case PathCapture(_, _, _) =>
          idxUsed += 1
          s"/:param$idxUsed"
      }
      if (fragments.isEmpty) {
        // No path should match anything
        List("prefix:/")
      } else {
        val pathPattern = fragments.mkString
        // endpoint.in("api") should match both '/api', '/api/'
        List(pathPattern, s"$pathPattern/")
      }
    }
  }
}

object ArmeriaServerInterpreter extends ArmeriaServerInterpreter {
  def apply(serverOptions: ArmeriaServerOptions = ArmeriaServerOptions.default): ArmeriaServerInterpreter = {
    new ArmeriaServerInterpreter {
      override def armeriaServerOptions: ArmeriaServerOptions = serverOptions
    }
  }
}
