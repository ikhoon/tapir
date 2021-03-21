package sttp.tapir.server.armeria

import com.linecorp.armeria.common.HttpMethod
import sttp.model.Method

/** Utility object to convert HTTP methods between Vert.x and Tapir
  */
private[armeria] object MethodMapping {

  private val sttpToArmeria = Map(
    Method.CONNECT -> HttpMethod.CONNECT,
    Method.DELETE -> HttpMethod.DELETE,
    Method.GET -> HttpMethod.GET,
    Method.HEAD -> HttpMethod.HEAD,
    Method.OPTIONS -> HttpMethod.OPTIONS,
    Method.PATCH -> HttpMethod.PATCH,
    Method.POST -> HttpMethod.POST,
    Method.PUT -> HttpMethod.PUT,
    Method.TRACE -> HttpMethod.TRACE
  )

  private val amreriaToSttp = Map(
    HttpMethod.CONNECT -> Method.CONNECT,
    HttpMethod.DELETE -> Method.DELETE,
    HttpMethod.GET -> Method.GET,
    HttpMethod.HEAD -> Method.HEAD,
    HttpMethod.OPTIONS -> Method.OPTIONS,
    HttpMethod.PATCH -> Method.PATCH,
    HttpMethod.POST -> Method.POST,
    HttpMethod.PUT -> Method.PUT,
    HttpMethod.TRACE -> Method.TRACE
  )

  def toArmeria(method: Option[Method]): Option[HttpMethod] = method.flatMap(sttpToArmeria.get)

  def toSttp(method: HttpMethod): Method = amreriaToSttp(method)
}
