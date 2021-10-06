package sttp.tapir.server.armeria

import java.util.UUID
import org.scalatest.funsuite.AnyFunSuite
import sttp.model.Method
import sttp.tapir.EndpointInput.PathCapture
import sttp.tapir.internal.RichEndpointInput
import sttp.tapir.{Endpoint, EndpointIO, EndpointInput, endpoint, header, plainBody, query}

class EndpointTest extends AnyFunSuite {

  test("foo") {
    import io.circe.generic.auto._
    import sttp.tapir._
    import sttp.tapir.generic.auto._
    import sttp.tapir.json.circe._

    case class ErrorInfo(message: String)

    val baseEndpoint: Endpoint[Unit, ErrorInfo, Unit, Any] = {
      endpoint.in("api" / "v1.0").errorOut(jsonBody[ErrorInfo])
    }

    val value: Endpoint[(String, Int, List[String], List[String], UUID), Unit, Unit, Any] = endpoint
      .in(path[String]("foo"))
      .in(path[Int]("bar"))
      .in(paths)
      .in(paths)
      .in(query[UUID]("start"))
    // prefix:/:foo/:bar

    println(extractPath(value))
  }

  test("empty") {
    val endpoint2 = endpoint.in(query[UUID]("page"))
    println(extractPath(endpoint2))
  }

  import sttp.tapir.generic.auto._
  import sttp.tapir.json.circe._
  import io.circe.generic.auto._
  case class User(name: String)

  test("method") {
    val value: Endpoint[Unit, Unit, Unit, Any] = endpoint.method(Method.GET)
    val value1 = value.post.delete
      .in(header[String]("foo"))
      .in(jsonBody[User])
      .in(plainBody[UUID])
    value1.input
      .asVectorOfBasicInputs()
      .collect { x =>
        x match {
          case EndpointInput.FixedMethod(m, codec, info) =>
            println(m)
          case EndpointInput.FixedPath(s, codec, info)       =>
          case PathCapture(name, codec, info)                =>
          case EndpointInput.PathsCapture(codec, info)       =>
          case EndpointInput.Query(name, codec, info)        =>
          case EndpointInput.QueryParams(codec, info)        =>
          case EndpointInput.Cookie(name, codec, info)       =>
          case EndpointInput.ExtractFromRequest(codec, info) =>
          case basic: EndpointIO.Body[_, _] =>
            println(s"$basic basic.codec.format.mediaType = " + basic.codec.format.mediaType)
          case x =>
            println(s"else $x")
        }
      }

    value1.output
    println(value1.output.show)
  }

  def extractPath(endpoint: Endpoint[_, _, _, _]): String = {
    var catchSubPaths = false
    var idxUsed = 0
    var path = endpoint.input
      .asVectorOfBasicInputs()
      .collect {
        case segment: EndpointInput.FixedPath[_] => segment.show
        case PathCapture(Some(name), _, _)       => s"/:$name"
        case PathCapture(_, _, _) =>
          idxUsed += 1
          s"/:param$idxUsed"
        case EndpointInput.PathsCapture(_, _) =>
          catchSubPaths = true
          ""
      }
      .mkString

    if (path.isEmpty) {
      "prefix:/"
    } else if (catchSubPaths) {
      s"prefix:$path"
    } else {
      path
    }
  }
}
