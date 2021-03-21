package sttp.tapir.server.armeria

import com.linecorp.armeria.common.{HttpRequest, QueryParams => ArmeriaQueryParams}
import com.linecorp.armeria.server.ServiceRequestContext
import io.netty.handler.codec.http.QueryStringDecoder
import scala.collection.JavaConverters._
import sttp.model.{Method, QueryParams}
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.internal.DecodeInputsContext

private[armeria] class ArmeriaDecodeInputsContext(
    ctx: ServiceRequestContext,
    pathConsumed: Int = 0
) extends DecodeInputsContext {

  private val request: HttpRequest = ctx.request()
  private lazy val params: ArmeriaQueryParams = ArmeriaQueryParams.fromQueryString(ctx.query())

  override def method: Method = MethodMapping.toSttp(request.method())

  override def nextPathSegment: (Option[String], DecodeInputsContext) = {
    val path = Option(request.path).getOrElse("").drop(pathConsumed)
    val nextStart = path.dropWhile(_ == '/')
    val segment = nextStart.split("/", 2) match {
      case Array("")   => None
      case Array(s)    => Some(s)
      case Array(s, _) => Some(s)
    }
    val charactersConsumed = segment.map(_.length).getOrElse(0) + (path.length - nextStart.length)

    (
      segment.map(QueryStringDecoder.decodeComponent),
      new ArmeriaDecodeInputsContext(ctx, pathConsumed + charactersConsumed)
    )
  }

  override def header(name: String): List[String] = request.headers.getAll(name).asScala.toList

  override def headers: Seq[(String, String)] = HeaderMapping.fromArmeria(request.headers())

  override def queryParameter(name: String): Seq[String] = params.getAll(name).asScala

  override def queryParameters: QueryParams =
    QueryParams.fromMultiMap(
      params.names.asScala.map { key => (key, params.getAll(key).asScala) }.toMap
    )

  override def bodyStream: Any = request

  override def serverRequest: ServerRequest = new VertxServerRequest(request)
}
