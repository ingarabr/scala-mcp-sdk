package mcp.protocol

import io.circe.{Codec, Decoder, Encoder}

import scala.compiletime.{constValue, erasedValue}
import scala.reflect.ClassTag

trait EnumCodec[A] {
  def codec: Codec[A]
}
object EnumCodec {

  inline def derived[A](using ct: ClassTag[A]): EnumCodec[A] = new EnumCodec[A] {
    def codec: Codec[A] = {
      val values: Array[A] = classValues[A]

      Codec.from(
        Decoder.decodeString.emap { s =>
          values
            .find(_.toString == s)
            .toRight(s"'$s' not in [${values.map(_.toString).mkString(", ")}]")
        },
        Encoder.encodeString.contramap(_.toString)
      )
    }
  }

  private def classValues[A](using ct: ClassTag[A]): Array[A] = {
    val clazz = ct.runtimeClass
    clazz.getMethod("values").invoke(null).asInstanceOf[Array[A]]
  }
}
given [A](using ev: EnumCodec[A]): Codec[A] = ev.codec
