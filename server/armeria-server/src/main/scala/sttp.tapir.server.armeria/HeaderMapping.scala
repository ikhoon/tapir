package sttp.tapir.server.armeria

import com.linecorp.armeria.common.RequestHeaders

object HeaderMapping {
  def fromArmeria(headers: RequestHeaders): Seq[(String, String)] = {
    val builder = Seq.newBuilder[(String, String)]
    builder.sizeHint(headers.size())

    headers.forEach((key, value) => {
      builder += (key, value)
    })
    builder.result()
  }
}
