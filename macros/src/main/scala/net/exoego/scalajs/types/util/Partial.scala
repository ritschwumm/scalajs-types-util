package net.exoego.scalajs.types.util

import scala.annotation.StaticAnnotation
import scala.language.experimental.macros
import scala.reflect.macros.blackbox
import scala.scalajs.js

/**
  * Enrich the annotated type with all properties of `T` set to optional (wrapped with `js.UndefOr`).
  * Methods ('def`) are also added to the annotated type, but the return type is not modified.
  *
  * If the below code given,
  *
  * {{{
  * @js.native
  * trait Base extends js.Object {
  *   var foo: String = js.native
  *   val bar: js.Array[Int] = js.native
  *   def buz(x: String): Boolean = js.native
  * }
  *
  * @Partial[Base]
  * @js.native
  * trait Enriched extends js.Object {
  *   var ownProp: String = js.native
  * }
  * }}}
  *
  * The `Enriched` will be:
  *
  * {{{
  *   @js.native
  *   trait Enriched extends js.Object {
  *     var ownProp: String = js.native
  *
  *     // Added from `Base`
  *     var foo: js.UndefOr[String] = js.native
  *     val bar: js.UndefOr[js.Array[Int]] = js.native
  *     def buz(x: String): Boolean = js.native
  *   }
  * }}}
  *
  * @tparam T
  */
class Partial[T <: js.Object] extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro Partial.impl
}

object Partial {
  def impl(c: blackbox.Context)(annottees: c.Expr[Any]*) = {
    import c.universe._

    def bail(message: String) = c.abort(c.enclosingPosition, "Can annotate only trait")
    def toPartial(s: Symbol, isJsNative: Boolean): Tree = {
      val name      = TermName(s.name.decodedName.toString)
      val stringRep = s.toString
      if (stringRep.startsWith("value ")) {
        val tpt = s.typeSignature
        if (isJsNative) {
          q"val $name: js.UndefOr[$tpt] = scala.scalajs.js.native"
        } else {
          q"val $name: js.UndefOr[$tpt]"
        }
      } else if (stringRep.startsWith("variable ")) {
        val m = s.asMethod
        val paramss = m.paramLists.map(_.map(param => {
          internal.valDef(param)
        }))
        val retType = m.returnType
        if (isJsNative) {
          q"def $name (...$paramss): js.UndefOr[$retType] = scala.scalajs.js.native"
        } else {
          q"def $name (): js.UndefOr[$retType]"
        }
      } else {
        val m = s.asMethod
        val paramss = m.paramLists.map(_.map(param => {
          internal.valDef(param)
        }))
        val retType = m.returnType
        if (isJsNative) {
          q"def $name (...$paramss): $retType = scala.scalajs.js.native"
        } else {
          q"def $name (): $retType"
        }
      }
    }

    val argumentType: Type = {
      val macroTypeWithArguments          = c.typecheck(q"${c.prefix.tree}").tpe
      val annotationClass: ClassSymbol    = macroTypeWithArguments.typeSymbol.asClass
      val annotationTypePlaceholder: Type = annotationClass.typeParams.head.asType.toType
      annotationTypePlaceholder.asSeenFrom(macroTypeWithArguments, annotationClass)
    }

    if (argumentType.finalResultType == c.typeOf[Nothing]) {
      bail("Type parameter T must be provided")
    }

    val inputs = annottees.map(_.tree).toList
    if (!inputs.headOption.exists(_.isInstanceOf[ClassDef])) {
      bail("Can annotate only trait")
    }

    annottees.map(_.tree) match {
      case List(
          q"$mods trait $tpname[..$tparams] extends { ..$earlydefns } with ..$parents { $self => ..$ownMembers }"
          ) =>
        val isJsNative = mods.annotations.exists {
          case q"new scala.scalajs.js.native()" => true
          case q"new scalajs.js.native()"       => true
          case q"new js.native()"               => true
          case _                                => false
        }
        val inheritedMembers =
          // maybe decls instead of members?
          (argumentType.members.toSet -- c.typeOf[js.Object].members.toSet).toList
            .filterNot(_.isConstructor)
            .sortBy(_.name.decodedName.toString)
        val partialMembers = inheritedMembers.map(s => toPartial(s, isJsNative))
        c.Expr[Any](q"""
          $mods trait $tpname[..$tparams] extends { ..$earlydefns } with ..$parents { $self => 
            ..$ownMembers
            ..$partialMembers
          }
        """)
      case _ => bail("Must be a trait")
    }
  }
}