package sttp.tapir.client.armeria

import com.linecorp.armeria.common._
import com.linecorp.armeria.common.stream.{StreamMessage, StreamMessages}
import io.netty.buffer.Unpooled
import java.io.{ByteArrayInputStream, File}
import java.nio.ByteBuffer
import java.nio.file.Files
import scala.collection.JavaConverters._
import scala.compat.java8.FutureConverters._
import scala.concurrent.Future
import sttp.capabilities.Streams
import sttp.model.ResponseMetadata
import sttp.tapir.Codec.PlainCodec
import sttp.tapir.internal.{Params, ParamsAsAny, RichEndpointOutput, SplitParams}
import sttp.tapir.{
  Codec,
  CodecFormat,
  DecodeResult,
  Endpoint,
  EndpointIO,
  EndpointInput,
  EndpointOutput,
  FileRange,
  Mapping,
  RawBodyType,
  StreamBodyIO
}

private[armeria] class EndpointToArmeriaClient(clientOptions: ArmeriaClientOptions) {

  def toArmeriaRequest[A, I, E, O, R](
      e: Endpoint[A, I, E, O, R],
      baseUri: Option[String]
  ): A => I => (HttpRequest, HttpResponse => Future[DecodeResult[Either[E, O]]]) = { aParams => iParams =>
    val pathBuilder = new StringBuilder(baseUri.getOrElse("/"))
    val queryParamsBuilder = QueryParams.builder();
    val reqBuilder = new HttpRequestBuilder()
    val req = setInputParams(e.securityInput, ParamsAsAny(aParams), reqBuilder, pathBuilder, queryParamsBuilder)
      .path(pathBuilder.toString())
      .queryParams(queryParamsBuilder.build())
      .build()

    // TODO: Use Armeria ResponseAs
    def responseParser(response: HttpResponse): Future[DecodeResult[Either[E, O]]] = {
      parseArmeriaResponse(e).apply(response)
    }

    (req, responseParser)
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
        codec.encode(value).map { encoded =>
          queryParamsBuilder.add(name, encoded)
        }
        req
      case EndpointInput.Cookie(name, codec, _) =>
        codec.encode(value).foreach { str =>
          req.cookie(Cookie.ofSecure(name, str))
        }
        req
      case EndpointInput.QueryParams(codec, _) =>
        codec.encode(value).toMultiSeq.foreach { case (name, values) =>
          queryParamsBuilder.add(name, values.asJava)
        }
        req
      case EndpointIO.Empty(_, _)              => req
      case EndpointIO.Body(bodyType, codec, _) => setBody(value, bodyType, codec, req)
      case EndpointIO.OneOfBody(variants, _)   => setInputParams(variants.head.body, params, req, pathBuilder, queryParamsBuilder)
      case EndpointIO.StreamBodyWrapper(StreamBodyIO(streams, codec, _, _, _)) =>
        setStreamingBody(codec, req, streams)(value.asInstanceOf[streams.BinaryStream])
      case EndpointIO.Header(name, codec, _) =>
        codec
          .encode(value)
          .foldLeft(req) { case (r, v) => r.header(name, v) }

      case EndpointIO.Headers(codec, _) =>
        val headers = codec.encode(value)
        headers.foldLeft(req) { case (r, h) => r.header(h.name, h.value) }
      case EndpointIO.FixedHeader(h, _, _) =>
        req.header(h.name, h.value)
      case EndpointInput.ExtractFromRequest(_, _)    => req // ignoring
      case a: EndpointInput.Auth[_, _]               => setInputParams(a.input, params, req, pathBuilder, queryParamsBuilder)
      case EndpointInput.Pair(left, right, _, split) => handleInputPair(left, right, params, split, req, pathBuilder, queryParamsBuilder)
      case EndpointIO.Pair(left, right, _, split)    => handleInputPair(left, right, params, split, req, pathBuilder, queryParamsBuilder)
      case EndpointInput.MappedPair(wrapped, codec) =>
        handleMapped(wrapped, codec.asInstanceOf[Mapping[Any, Any]], params, req, pathBuilder, queryParamsBuilder)
      case EndpointIO.MappedPair(wrapped, codec) =>
        handleMapped(wrapped, codec.asInstanceOf[Mapping[Any, Any]], params, req, pathBuilder, queryParamsBuilder)
    }
  }

  private def handleInputPair(
      left: EndpointInput[_],
      right: EndpointInput[_],
      params: Params,
      split: SplitParams,
      req: HttpRequestBuilder,
      pathBuilder: StringBuilder,
      queryParamsBuilder: QueryParamsBuilder
  ): HttpRequestBuilder = {
    val (leftParams, rightParams) = split(params)
    setInputParams(left.asInstanceOf[EndpointInput[Any]], leftParams, req, pathBuilder, queryParamsBuilder)
    setInputParams(right.asInstanceOf[EndpointInput[Any]], rightParams, req, pathBuilder, queryParamsBuilder)
  }

  private def handleMapped[II, T](
      tuple: EndpointInput[II],
      codec: Mapping[T, II],
      params: Params,
      req: HttpRequestBuilder,
      pathBuilder: StringBuilder,
      queryParamsBuilder: QueryParamsBuilder
  ): HttpRequestBuilder = {
    setInputParams(
      tuple.asInstanceOf[EndpointInput[Any]],
      ParamsAsAny(
        codec.encode(
          params.asAny
            .asInstanceOf[II]
        )
      ),
      req,
      pathBuilder,
      queryParamsBuilder
    )
  }

  private def setBody[R, T, CF <: CodecFormat](
      v: T,
      bodyType: RawBodyType[R],
      codec: Codec[R, T, CF],
      req: HttpRequestBuilder
  ): HttpRequestBuilder = {
    val mediaType = toMediaType(codec)
    val encoded: R = codec.encode(v)
    val req2 = bodyType match {
      case RawBodyType.StringBody(_) =>
        req.content(mediaType, encoded.asInstanceOf[String])
      case RawBodyType.ByteArrayBody => req.content(mediaType, encoded.asInstanceOf[Array[Byte]])
      case RawBodyType.ByteBufferBody =>
        val buf = Unpooled.wrappedBuffer(encoded.asInstanceOf[ByteBuffer])
        req.content(mediaType, HttpData.wrap(buf))
      case RawBodyType.InputStreamBody =>
        // TODO(ikhoon): Support InputStream after https://github.com/line/armeria/pull/4059 is merged.
        throw new UnsupportedOperationException("InputStream is not supported yet.")
      case RawBodyType.FileBody =>
        req.content(mediaType, StreamMessage.of(encoded.asInstanceOf[File]))
      case _: RawBodyType.MultipartBody =>
        // TODO(ikhoon): Implmement multipart body
        throw new IllegalArgumentException("Multipart body aren't supported")
    }
    req2
  }

  private def setStreamingBody[S](codec: Codec[_, _, _ <: CodecFormat], req: HttpRequestBuilder, streams: Streams[S])(
      v: streams.BinaryStream
  ): HttpRequestBuilder = {
    streams match {
      case ArmeriaStreams =>
        req.content(toMediaType(codec), v.asInstanceOf[ArmeriaStreams.BinaryStream])
      case _ => throw new IllegalArgumentException("Only ArmeriaStreams streaming is supported")
    }
  }

  private def toMediaType(codec: Codec[_, _, _ <: CodecFormat]): MediaType = {
    MediaType.parse(codec.format.mediaType.toString())
  }

  private def parseArmeriaResponse[A, I, E, O, R](
      e: Endpoint[A, I, E, O, R]
  ): HttpResponse => Future[DecodeResult[Either[E, O]]] = { response =>
    bodyIsStream(out)
    val splitResponse = response.split()
    splitResponse.headers().toScala.map { headers =>
      val code = sttp.model.StatusCode(headers.status.code)
      val parser = if (code.isSuccess) responseFromOutput(e.output) else responseFromOutput(e.errorOutput)

    }

    val parser = if (code.isSuccess) responseFromOutput[F](e.output) else responseFromOutput[F](e.errorOutput)
    val output = if (code.isSuccess) e.output else e.errorOutput

    // headers with cookies
    val headers = response.headers.headers.map(h => sttp.model.Header(h.name.toString, h.value)).toVector

    parser(response).map { responseBody =>
      val params = clientOutputParams(output, responseBody, ResponseMetadata(code, response.status.reason, headers))
      params.map(_.asAny).map(p => if (code.isSuccess) Right(p.asInstanceOf[O]) else Left(p.asInstanceOf[E]))
    }
  }

  private def responseFromOutput(out: EndpointOutput[_]): StreamMessage[HttpData] => Future[Any] = { body =>
    bodyIsStream(out) match {
      case Some(streams) =>
        streams match {
          case ArmeriaStreams => Future.successful(body.asInstanceOf[ArmeriaStreams.BinaryStream])
          case _              => throw new IllegalArgumentException("Only ArmeriaStreams streaming is supported")
        }
      case None =>
        out.bodyType
          .map {
            case RawBodyType.StringBody(_)   =>
            case RawBodyType.ByteArrayBody   => body.body[Array[Byte]]
            case RawBodyType.ByteBufferBody  => body.body[ByteBuffer]
            case RawBodyType.InputStreamBody => new ByteArrayInputStream(body.body[Array[Byte]])
            case RawBodyType.FileBody        =>
              // TODO Consider using bodyAsSource to not load the whole content in memory
              val f = clientOptions.createFile()
              val outputStream = Files.newOutputStream(f.toPath)
              outputStream.write(body.body[Array[Byte]])
              outputStream.close()
              FileRange(f)
            case RawBodyType.MultipartBody(_, _) =>
              throw new IllegalArgumentException(
                "Multipart bodies " +
                  "aren't supported in responses"
              )
          }
          .getOrElse(()) // Unit
    }
  }

  private def aggregateBody(bodyType: RawBodyType[_], contentType: MediaType, body: StreamMessage[HttpData]): Future[Any] = {
    bodyType match {
      case RawBodyType.StringBody(charset) =>
        aggregateHttpData(body)
      case RawBodyType.ByteArrayBody   =>
      case RawBodyType.ByteBufferBody  => Future.successful(body.body[ByteBuffer])
      case RawBodyType.InputStreamBody => Future.successful(new ByteArrayInputStream(body.body[Array[Byte]]))
      case RawBodyType.FileBody =>
        val f = clientOptions.createFile()
        StreamMessages.writeTo(body, f.toPath)
        Future.successful(FileRange(f))
      case _: RawBodyType.MultipartBody =>
        import com.linecorp.armeria.common.multipart.{Multipart, Multiparts}
        val boundary = Multiparts.getBoundary(contentType)
        val multipart = Multipart.from(boundary, body)
        // TODO(ikhoon): Implement this
        throw new IllegalArgumentException("Multipart bodies aren't supported")
    }
  }

  private def aggregateHttpData(streamMessage: StreamMessage[HttpData]): Future[HttpData] = {
    streamMessage
      .collect()
      .thenApply[HttpData] { objects =>
        if (objects.isEmpty) {
          HttpData.empty()
        } else if (objects.size == 1) {
          objects.get(0)
        } else {
          val list = objects.asScala
          val totalSize = list.map(_.length()).sum
          val array = Array.ofDim[Byte](totalSize)
          list.foldLeft(0) { (offset, data) =>
            val length = data.length
            System.arraycopy(data.array(), 0, array, offset, length)
            offset + length
          }
          HttpData.wrap(array)
        }
      }
      .toScala
  }

  private def bodyIsStream[I](out: EndpointOutput[I]): Option[Streams[_]] = {
    out.traverseOutputs { case EndpointIO.StreamBodyWrapper(StreamBodyIO(streams, _, _, _, _)) =>
      Vector(streams)
    }.headOption
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
