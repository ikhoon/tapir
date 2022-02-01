package sttp.tapir.server.armeria

import com.linecorp.armeria.common.{HttpResponse, HttpStatus}
import com.linecorp.armeria.common.multipart.Multipart
import sttp.tapir.server.interceptor.RequestResult

object ResultMapping {
  def toArmeria(result: RequestResult[ArmeriaResponseType]): HttpResponse = {
    result match {
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
  }
}
