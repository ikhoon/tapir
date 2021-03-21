package sttp.tapir.server.armeria

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.server.ServiceRequestContext
import java.net.{InetSocketAddress, URI}
import sttp.model.Method
import sttp.tapir.model.{ConnectionInfo, ServerRequest}

private[armeria] class ArmeriaServerRequest(ctx: ServiceRequestContext) extends ServerRequest {
  private lazy val request: HttpRequest = ctx.request

  lazy val connectionInfo: ConnectionInfo = {
    val remotePort = ctx.remoteAddress[InetSocketAddress]().getPort
    val clientAddress = InetSocketAddress.createUnresolved(ctx.clientAddress().getHostAddress, remotePort)
    ConnectionInfo(
      Some(ctx.localAddress[InetSocketAddress]()),
      Some(clientAddress),
      Some(ctx.sessionProtocol().isTls)
    )
  }

  override def method: Method = MethodMapping.toSttp(request.method())

  override def protocol: String = ctx.sessionProtocol().toString

  override def uri: URI = request.uri()

  override def headers: Seq[(String, String)] = HeaderMapping.fromArmeria(request.headers())

  override def header(name: String): Option[String] = Option(request.headers().get(name))

  override def underlying: Any = ctx
}
