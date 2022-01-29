package sttp.tapir.server.armeria

import com.linecorp.armeria.server.ServiceRequestContext
import org.slf4j.{Logger, LoggerFactory}
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal
import sttp.tapir.server.interceptor.log.{DefaultServerLog, ServerLog}
import sttp.tapir.server.interceptor.{CustomInterceptors, Interceptor}
import sttp.tapir.{Defaults, TapirFile}

final case class ArmeriaServerOptions(
    createFile: ServiceRequestContext => Future[TapirFile],
    deleteFile: (ServiceRequestContext, TapirFile) => Future[Unit],
    interceptors: List[Interceptor[Future]]
) {
  def prependInterceptor(i: Interceptor[Future]): ArmeriaServerOptions = copy(interceptors = i :: interceptors)

  def appendInterceptor(i: Interceptor[Future]): ArmeriaServerOptions = copy(interceptors = interceptors :+ i)
}

object ArmeriaServerOptions {

  /** Allows customising the interceptors used by the server interpreter. */
  def customInterceptors: CustomInterceptors[Future, ArmeriaServerOptions] =
    CustomInterceptors(
      createOptions = (ci: CustomInterceptors[Future, ArmeriaServerOptions]) =>
        ArmeriaServerOptions(
          defaultCreateFile,
          defaultDeleteFile,
          ci.interceptors
        )
    ).serverLog(defaultServerLog)

  val logger: Logger = LoggerFactory.getLogger(this.getClass.getPackage.getName)

  val defaultCreateFile: (ServiceRequestContext) => Future[TapirFile] = ctx => blocking(ctx)(Defaults.createTempFile())

  val defaultDeleteFile: (ServiceRequestContext, TapirFile) => Future[Unit] =
    (ctx, file) => blocking(ctx)(Defaults.deleteFile()(file))

  val defaultServerLog: ServerLog[Future] = DefaultServerLog[Future](
    doLogWhenHandled = debugLog,
    doLogAllDecodeFailures = debugLog,
    doLogExceptions = (msg: String, ex: Throwable) => Future.successful(logger.warn(msg, ex)),
    noLog = Future.unit
  )

  val default: ArmeriaServerOptions = customInterceptors.options

  private def debugLog(msg: String, exOpt: Option[Throwable]): Future[Unit] =
    Future.successful(exOpt match {
      case None     => logger.debug(msg)
      case Some(ex) => logger.debug(msg, ex)
    })

  def blocking[T](ctx: ServiceRequestContext)(body: => T): Future[T] = {
    val promise = Promise[T]()
    ctx
      .blockingTaskExecutor()
      .execute(() => {
        try {
          promise.success(body)
        } catch {
          case NonFatal(ex) => promise.failure(ex)
        }
      })
    promise.future
  }
}
