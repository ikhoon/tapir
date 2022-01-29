package sttp.tapir.client.armeria

import com.linecorp.armeria.common.stream.StreamMessage
import com.linecorp.armeria.common.{Cookie, HttpData, HttpMethod, HttpRequest, HttpRequestBuilder, HttpResponse, QueryParamsBuilder}
import com.linecorp.armeria.internal.shaded.guava.io.ByteStreams
import io.netty.buffer.Unpooled
import java.io.{File, InputStream}
import java.nio.ByteBuffer
import scala.collection.JavaConverters._
import scala.concurrent.Future
import sttp.tapir.Codec.PlainCodec
import sttp.tapir.internal.Params
import sttp.tapir.{Codec, CodecFormat, DecodeResult, Endpoint, EndpointIO, EndpointInput, FileRange, Mapping, RawBodyType, StreamBodyIO}

private[armeria] class EndpointToArmeriaClient(clientOptions: ArmeriaClientOptions) {

  def toArmeriaRequest[I, E, O, R](
      e: Endpoint[I, E, O, R],
      baseUri: Option[String]
  ): I => (HttpRequest, HttpResponse => Future[DecodeResult[Either[E, O]]]) = { params =>
    val pathBuilder = new StringBuilder
    pathBuilder ++= baseUri.getOrElse("/")
    ???
  }

  @scala.annotation.tailrec
  private def setInputParams[I](
      input: EndpointInput[I],
      params: Params,
      req: HttpRequestBuilder,
      pathBuilder: StringBuilder,
      queryParamsBuilder: QueryParamsBuilder
  ): HttpRequestBuilder = {
    def value: I = params.asAny.asInstanceOf[I]

    input match {
      case EndpointInput.FixedMethod(m, _, _) => req.method(HttpMethod.valueOf(m.method))
      case EndpointInput.FixedPath(p, _, _) =>
        pathBuilder ++= "/" + p
        req
      case EndpointInput.PathCapture(_, codec, _) =>
        val v = codec.asInstanceOf[PlainCodec[Any]].encode(value: Any)
        pathBuilder ++= "/" + v
        req
      case EndpointInput.PathsCapture(codec, _) =>
        val ps = codec.encode(value)
        pathBuilder ++= ps.mkString("/", "/", "")
        req
      case EndpointInput.Query(name, codec, _) =>
        codec.encode(value) match {
          case values if values.nonEmpty =>
            queryParamsBuilder.add(name, values.asJava)
          case _ =>
        }
        req
      case EndpointInput.Cookie(name, codec, _) =>
        codec.encode(value).foreach { str =>
          req.cookie(Cookie.of(name, str))
        }
        req
      case EndpointInput.QueryParams(codec, _) =>
        codec.encode(value).toMultiSeq.foreach { case (name, values) =>
          queryParamsBuilder.add(name, values.asJava)
        }
        req
      case EndpointIO.Empty(_, _)              => req
      case EndpointIO.Body(bodyType, codec, _) => setBody(value, bodyType, codec, req)
      case EndpointIO.StreamBodyWrapper(StreamBodyIO(streams, _, _, _)) =>
        setStreamingBody(streams)(value.asInstanceOf[streams.BinaryStream], req)
      case EndpointIO.Header(name, codec, _) =>
        val headers = codec.encode(value).map(value => Header.Raw(CIString(name), value): Header.ToRaw)
        req.putHeaders(headers: _*)
      case EndpointIO.Headers(codec, _) =>
        val headers = codec.encode(value).map(h => Header.Raw(CIString(h.name), h.value): Header.ToRaw)
        req.putHeaders(headers: _*)
      case EndpointIO.FixedHeader(h, _, _)           => req.putHeaders(Header.Raw(CIString(h.name), h.value))
      case EndpointInput.ExtractFromRequest(_, _)    => req // ignoring
      case a: EndpointInput.Auth[_]                  => setInputParams(a.input, params, req)
      case EndpointInput.Pair(left, right, _, split) => handleInputPair(left, right, params, split, req)
      case EndpointIO.Pair(left, right, _, split)    => handleInputPair(left, right, params, split, req)
      case EndpointInput.MappedPair(wrapped, codec) =>
        handleMapped(wrapped.asInstanceOf[EndpointInput.Pair[Any, Any, Any]], codec.asInstanceOf[Mapping[Any, Any]], params, req)
      case EndpointIO.MappedPair(wrapped, codec) =>
        handleMapped(wrapped.asInstanceOf[EndpointIO.Pair[Any, Any, Any]], codec.asInstanceOf[Mapping[Any, Any]], params, req)
    }
  }

  private def toBody[R, T, CF <: CodecFormat](
      value: T,
      bodyType: RawBodyType[R],
      codec: Codec[R, T, CF]
  ): Either[StreamMessage[HttpData], HttpData] = {
    val encoded: R = codec.encode(value)
    val newReq = bodyType match {
      case RawBodyType.StringBody(charset) =>
        val str = encoded.asInstanceOf[String]
        Right(HttpData.of(charset, str))
      case RawBodyType.ByteArrayBody =>
        val bytes = encoded.asInstanceOf[Array[Byte]]
        Right(HttpData.wrap(bytes))
      case RawBodyType.ByteBufferBody =>
        val byteBuffer = encoded.asInstanceOf[ByteBuffer]
        Right(HttpData.wrap(Unpooled.wrappedBuffer(byteBuffer)))
      case RawBodyType.InputStreamBody =>
        // TODO(ikhoon): Use StreamMessage.of(InputStream) once implemented
        val is = encoded.asInstanceOf[InputStream]
        Right(HttpData.wrap(ByteStreams.toByteArray(is)))
      case RawBodyType.FileBody =>
        val file = encoded.asInstanceOf[FileRange]
        Left(StreamMessage.of(file.file))
      case _: RawBodyType.MultipartBody =>
        // TODO(ikhoon): Implement
        throw new IllegalArgumentException("Multipart body isn't supported yet")
    }
    val contentType = `Content-Type`.parse(codec.format.mediaType.toString()).right.get

    newReq.withContentType(contentType)
  }
}
