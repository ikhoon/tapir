package sttp.tapir.server.armeria

import com.linecorp.armeria.server.ServiceRequestContext
import org.slf4j.{Logger, LoggerFactory}
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal
import sttp.tapir.server.interceptor.log.{DefaultServerLog, ServerLog}
import sttp.tapir.server.interceptor.{CustomInterceptors, Interceptor}
import sttp.tapir.{Defaults, TapirFile}

final case class ArmeriaFutureServerOptions(
    createFile: ServiceRequestContext => Future[TapirFile],
    deleteFile: (ServiceRequestContext, TapirFile) => Future[Unit],
    interceptors: List[Interceptor[Future]]
) extends ArmeriaServerOptions[Future] {
  def prependInterceptor(i: Interceptor[Future]): ArmeriaFutureServerOptions = copy(interceptors = i :: interceptors)

  def appendInterceptor(i: Interceptor[Future]): ArmeriaFutureServerOptions = copy(interceptors = interceptors :+ i)
}

object ArmeriaFutureServerOptions {

  /** Allows customising the interceptors used by the server interpreter. */
  def customInterceptors: CustomInterceptors[Future, ArmeriaFutureServerOptions] =
    CustomInterceptors(
      createOptions = (ci: CustomInterceptors[Future, ArmeriaFutureServerOptions]) =>
        ArmeriaFutureServerOptions(
          ArmeriaServerOptions.defaultCreateFile,
          ArmeriaServerOptions.defaultDeleteFile,
          ci.interceptors
        )
    ).serverLog(ArmeriaServerOptions.defaultServerLog)

  val default: ArmeriaFutureServerOptions = customInterceptors.options

}
