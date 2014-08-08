package scala.pickling

import scala.reflect.runtime.{universe => ru}
import ir._

import internal._


class RuntimeTypeInfo(classLoader: ClassLoader, clazz: Class[_]) {
  import ru._
  import definitions._

  val mirror: ru.Mirror =
    ru.runtimeMirror(classLoader)

  val sym =
    if (clazz != null) mirror.classSymbol(clazz) else NullClass

  val tpe = {
    val elemClass: Class[_] =
      if (clazz != null) clazz.getComponentType() else null

    if (elemClass != null) // assume it's an array
      appliedType(ArrayClass.toType, List(mirror.classSymbol(elemClass).asType.toType))
    else
      sym.asType.toType
  }

  val irs = new IRs[ru.type](ru)
  val cir = irs.flattenedClassIR(tpe)

  val tag = FastTypeTag(mirror, tpe, tpe.key)
}

// TODO: sharing
class RuntimePickler(classLoader: ClassLoader, clazz: Class[_])(implicit pf: PickleFormat, share: refs.Share) extends RuntimeTypeInfo(classLoader, clazz) {
  import ru._

  sealed abstract class Logic(fir: irs.FieldIR, isEffFinal: Boolean) {
    def run(builder: PBuilder, im: ru.InstanceMirror): Unit = {
      val fldMirror = im.reflectField(fir.field.get)
      val fldValue: Any = fldMirror.get
      val fldClass = if (fldValue != null) fldValue.getClass else null

      //debug(s"pickling field of type: ${fir.tpe.toString}")
      //debug(s"isEffFinal: $isEffFinal")
      //debug(s"field value: $fldValue")
      //debug(s"field class: ${fldClass.getName}")

      // idea: picklers for all fields could be created and cached once and for all.
      // however, it depends on whether the type of the field is effectively final or not.
      // essentially, we have to emulate the behavior of generated picklers, which make
      // the same decision.
      val fldPickler = SPickler.genPickler(classLoader, fldClass).asInstanceOf[SPickler[Any]]
      //debug(s"looked up field pickler: $fldPickler")

      builder.putField(fir.name, b => {
        pickleLogic(fldClass, fldValue, b, fldPickler)
      })
    }

    def pickleLogic(fieldClass: Class[_], fieldValue: Any, builder: PBuilder, pickler: SPickler[Any]): Unit
  }

  final class DefaultLogic(fir: irs.FieldIR) extends Logic(fir, false) {
    val staticClass = mirror.runtimeClass(fir.tpe.erasure)
    def pickleLogic(fldClass: Class[_], fldValue: Any, b: PBuilder, fldPickler: SPickler[Any]): Unit = {
      if (fldValue == null || fldValue.getClass == staticClass) b.hintDynamicallyElidedType()
      pickleInto(fldClass, fldValue, b, fldPickler)
    }
  }

  final class EffectivelyFinalLogic(fir: irs.FieldIR) extends Logic(fir, true) {
    def pickleLogic(fldClass: Class[_], fldValue: Any, b: PBuilder, fldPickler: SPickler[Any]): Unit = {
      b.hintStaticallyElidedType()
      pickleInto(fldClass, fldValue, b, fldPickler)
    }
  }

  final class AbstractLogic(fir: irs.FieldIR) extends Logic(fir, false) {
    def pickleLogic(fldClass: Class[_], fldValue: Any, b: PBuilder, fldPickler: SPickler[Any]): Unit = {
      pickleInto(fldClass, fldValue, b, fldPickler)
    }
  }

  // difference to old runtime pickler: create tag based on fieldClass instead of fir.tpe
  def pickleInto(fieldClass: Class[_], fieldValue: Any, builder: PBuilder, pickler: SPickler[Any]): Unit = {
    val fieldTag = FastTypeTag.mkRaw(fieldClass, mirror)
    //debug(s"fieldTag for pickleInto: ${fieldTag.key}")
    builder.hintTag(fieldTag)
    pickler.pickle(fieldValue, builder)
  }

  def mkPickler: SPickler[_] = {
    new SPickler[Any] {
      val format: PickleFormat = pf

      val fields: List[Logic] =
        cir.fields.filter(_.hasGetter).map { fir =>
          if (fir.tpe.typeSymbol.isEffectivelyFinal) new EffectivelyFinalLogic(fir)
          else if (fir.tpe.typeSymbol.asType.isAbstractType) new AbstractLogic(fir)
          else new DefaultLogic(fir)
        }

      def putFields(picklee: Any, builder: PBuilder): Unit = {
        val im = mirror.reflect(picklee)
        fields.foreach(_.run(builder, im))
      }

      def pickle(picklee: Any, builder: PBuilder): Unit = {
        //debug(s"pickling object of type: ${tag.key}")
        builder.beginEntry(picklee)
        putFields(picklee, builder)
        builder.endEntry()
      }
    }
  }

}