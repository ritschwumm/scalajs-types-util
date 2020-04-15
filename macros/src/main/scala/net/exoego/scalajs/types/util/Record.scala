package net.exoego.scalajs.types.util

import scala.annotation.StaticAnnotation
import scala.language.experimental.macros
import scala.reflect.macros.blackbox
import scala.scalajs.js

/**
  * Enrich the annotated type with a set of speified properties whose type is  `T`.
  *
  * If the below code given,
  *
  * {{{
  * @js.native
  * trait Base extends js.Object {
  *   var title: String = js.native
  * }
  *
  * @Record[Base]("foo", "buz")
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
  *     // Picked from `Base`
  *     var foo: Base = js.native
  *     def buz: Base = js.native
  *   }
  * }}}
  *
  * @tparam T
  */
class Record[T <: js.Object](keys: String*) extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro Record.impl
}

object Record {
  def impl(c: blackbox.Context)(annottees: c.Expr[Any]*) = {
    import c.universe._

    def bail(message: String) = c.abort(c.enclosingPosition, message)

    val specifiedFieldNames: Set[String] = c.prefix.tree match {
      case q"new Record[$a](..$b)" => b.map(_.toString.drop(1).dropRight(1)).toSet
      case _                       => bail("""@Record requires a type argument T and at-least one field names to be picked from T.""")
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
          (argumentType.members.toSet -- c.typeOf[js.Object].members.toSet).toList
            .filterNot(_.isConstructor)
            .map(_.name.decodedName.toString)
            .toSet
        val duplicateProperties = specifiedFieldNames intersect inheritedMembers
        if (duplicateProperties.nonEmpty) {
          bail(s"""Duplicate keys: ${duplicateProperties.mkString(", ")}""")
        }

        val addedProperties = specifiedFieldNames.map { s =>
          val name = TermName(s)
          if (isJsNative) {
            q"var $name: $argumentType = scala.scalajs.js.native"
          } else {
            q"var $name: $argumentType"
          }
        }.toList

        c.Expr[Any](q"""
          $mods trait $tpname[..$tparams] extends { ..$earlydefns } with ..$parents { $self => 
            ..$ownMembers
            ..$addedProperties
          }
        """)
      case _ => bail("Must be a trait")
    }
  }
}