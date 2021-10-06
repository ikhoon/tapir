package sttp.tapir.server.armeria

import com.linecorp.armeria.common.multipart.{AggregatedBodyPart, Multipart}
import com.linecorp.armeria.common.stream.StreamMessage
import com.linecorp.armeria.common.{HttpData, HttpRequest}
import com.linecorp.armeria.server.ServiceRequestContext
import java.io.ByteArrayInputStream
import java.nio.file.{Path, Paths}
import java.util.Date
import java.util.concurrent.ThreadLocalRandom
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.jdk.FutureConverters.CompletionStageOps
import sttp.model.Part
import sttp.tapir.RawBodyType
import sttp.tapir.server.interpreter.{RawValue, RequestBody}

final class ArmeriaRequestBody(
    ctx: ServiceRequestContext,
    request: HttpRequest,
    serverOptions: ArmeriaServerOptions
)(implicit ec: ExecutionContext)
    extends RequestBody[Future, ArmeriaStreams] {

  override val streams: ArmeriaStreams = ArmeriaStreams

  override def toStream(): streams.BinaryStream =
    request.filter(x => x.isInstanceOf[HttpData]).asInstanceOf[StreamMessage[HttpData]]

  override def toRaw[R](bodyType: RawBodyType[R]): Future[RawValue[R]] = bodyType match {
    case RawBodyType.StringBody(_) =>
      request.aggregate().thenApply[RawValue[R]](agg => RawValue(agg.contentUtf8())).asScala
    case RawBodyType.ByteArrayBody =>
      request.aggregate().thenApply[RawValue[R]](agg => RawValue(agg.content().array())).asScala
    case RawBodyType.ByteBufferBody =>
      request.aggregate().thenApply[RawValue[R]](agg => RawValue(agg.content().byteBuf().nioBuffer())).asScala
    case RawBodyType.InputStreamBody =>
      request
        .aggregate()
        .thenApply[RawValue[R]](agg => RawValue(new ByteArrayInputStream(agg.content().array())))
        .asScala
    case RawBodyType.FileBody =>
      val bodyStream = request.filter(x => x.isInstanceOf[HttpData]).asInstanceOf[StreamMessage[HttpData]]
      for {
        file <- serverOptions.createFile(ctx)
        _ <- new AsyncFileWriter(bodyStream, file.toPath, ctx.eventLoop()).whenComplete()
      } yield RawValue(file, Seq(file))
    case m: RawBodyType.MultipartBody =>
      Multipart
        .from(request)
        .aggregate()
        .asScala
        .flatMap(multipart => {
          val rawParts = multipart
            .bodyParts()
            .asScala
            .toList
            .flatMap(part => m.partType(part.name()).map(toRawPart(part, _)))

          Future
            .sequence(rawParts)
            .map(RawValue.fromParts(_))
        })
        .asInstanceOf[Future[RawValue[R]]]
  }

  private def toRawFromHttpData[R](body: HttpData, bodyType: RawBodyType[R]): Future[RawValue[R]] = {
    bodyType match {
      case RawBodyType.StringBody(_)   => Future.successful(RawValue(body.toStringUtf8))
      case RawBodyType.ByteArrayBody   => Future.successful(RawValue(body.array()))
      case RawBodyType.ByteBufferBody  => Future.successful(RawValue(body.byteBuf().nioBuffer()))
      case RawBodyType.InputStreamBody => Future.successful(RawValue(new ByteArrayInputStream(body.array())))
      case RawBodyType.FileBody =>
        for {
          file <- serverOptions.createFile(ctx)
          _ <- new AsyncFileWriter(StreamMessage.of(body), file.toPath, ctx.eventLoop()).whenComplete()
        } yield RawValue(file, Seq(file))
      case RawBodyType.MultipartBody(_, _) =>
        throw new UnsupportedOperationException("Nested multipart data is not supported.")
    }
  }

  private def toRawPart[R](part: AggregatedBodyPart, bodyType: RawBodyType[R]): Future[Part[R]] = {
    toRawFromHttpData(part.content(), bodyType)
      .map((r: RawValue[R]) =>
        Part(
          name = part.name,
          body = r.value,
          contentType = if (part.contentType() != null) {
            Some(HeaderMapping.fromArmeria(part.contentType()))
          } else {
            None
          },
          fileName = Option(part.filename()),
          otherHeaders = HeaderMapping.fromArmeria(part.headers())
        )
      )
  }
}
