package im.actor.api

import treehugger.forest._, definitions._
import treehuggerDSL._
import scala.language.postfixOps
import scala.collection.mutable
import spray.json._, DefaultJsonProtocol._

class Json2Tree(jsonString: String) extends JsonFormats with JsonHelpers with SerializationTrees with DeserializationTrees {
  val jsonAst = jsonString.parseJson
  val rootObj = jsonAst.convertTo[JsObject]

  val aliases: Map[String, String] = rootObj.withField("aliases") {
    case JsArray(jsAliases) =>
      jsAliases.map { jsAlias =>
        val alias = aliasFormat.read(jsAlias)
        (alias.alias, alias.typ)
      }.toMap
    case _ => deserializationError("Aliases should be JsArray")
  }

  def convert(): String = {
    val packages: Vector[(String, Vector[Item])] = rootObj.fields("sections").convertTo[JsArray].elements map {
      case obj: JsObject =>
        obj.fields("package") match {
          case JsString(packageName) =>
            (packageName, items(obj.fields("items").convertTo[JsArray].elements))
          case _ =>
            throw new Exception("package field is not a JsString")
        }
      case _ =>
        throw new Exception("section is not a JsObject")
    }

    val refsTreesSeq: Vector[(Vector[Tree], Tree)] = packages map {
      case (packageName, items) =>
        val (globalRefs, block) = itemsBlock(packageName, items)
        (globalRefs, PACKAGE(packageName) := block)
    }

    val (globalRefsV, packageTrees) = refsTreesSeq.unzip

    val globalRefsTree = OBJECTDEF("Refs") withFlags(PRIVATEWITHIN("api")) := BLOCK(globalRefsV.flatten)

    val updateBoxDef = TRAITDEF("UpdateBox").tree
    val updateDef = TRAITDEF("Update").tree
    val rpcRequestDef = TRAITDEF("RpcRequest").tree
    val rpcResponseDef = TRAITDEF("RpcResponse").tree

    val tree = PACKAGE("im.actor.api") := BLOCK(
      packageTrees ++ Vector(
        globalRefsTree, parseExceptionDef, updateBoxDef, updateDef, rpcRequestDef, rpcResponseDef
      ))
    treeToString(tree)
  }

  private def items(jsonElements: Vector[JsValue]): Vector[Item] = {
    jsonElements flatMap {
      case obj: JsObject =>
        obj.getFields("type", "content") match {
          case Seq(JsString("rpc"), o: JsObject) => Some(o.convertTo[RpcContent])
          case Seq(JsString("response"), o: JsObject) => Some(o.convertTo[NamedRpcResponse])
          case Seq(JsString("update"), o: JsObject) => Some(o.convertTo[Update])
          case Seq(JsString("update_box"), o: JsObject) => Some(o.convertTo[UpdateBox])
          case Seq(JsString("struct"), o: JsObject) => Some(o.convertTo[Struct])
          case Seq(JsString("enum"), o: JsObject) => Some(o.convertTo[Enum])
          case Seq(JsString("trait"), o: JsObject) => Some(o.convertTo[Trait])
          case Seq(JsString("comment"), _) => None
          case Seq(JsString("empty")) => None
          case Seq(JsString(typ), _) => throw new Exception(f"Unsupported item: $typ%s")
          case _ => throw new Exception("Item type is not a JsString")
        }
      case _ => throw new Exception("item is not a JsObject")
    }
  }

  private type TreesTraitsChildren = (Vector[Tree], Vector[Tree], Vector[Trait], Vector[NamedItem])
  private type TreesChildren = (Vector[Tree], Vector[Tree], Vector[NamedItem])

  private def merge(a: TreesTraitsChildren, b: TreesTraitsChildren): TreesTraitsChildren = (
    a._1 ++ b._1,
    a._2 ++ b._2,
    a._3 ++ b._3,
    a._4 ++ b._4
  )

  private def merge(a: TreesTraitsChildren, b: (Vector[Tree], Vector[Tree])): TreesTraitsChildren = (
    a._1 ++ b._1,
    a._2 ++ b._2,
    a._3,
    a._4
  )

  private def merge(a: TreesTraitsChildren, b: Vector[Trait]): TreesTraitsChildren =
    a.copy(_3 = a._3 ++ b)

  private def merge(a: TreesTraitsChildren, b: TreesChildren): TreesTraitsChildren =
    (
      a._1 ++ b._1,
      a._2 ++ b._2,
      a._3,
      a._4 ++ b._3
    )

  private def itemsBlock(packageName: String, items: Vector[Item]): (Vector[Tree], Tree) = {
    val (globalRefs, trees, traits, allChildren): TreesTraitsChildren = items.foldLeft[TreesTraitsChildren](
      (Vector.empty, Vector.empty, Vector.empty, Vector.empty)
    ) {
      case (trees, item: Item) => item match {
        case rpc: RpcContent => merge(trees, rpcItemTrees(packageName, rpc))
        case response: NamedRpcResponse => merge(trees, namedResponseItemTrees(packageName, response))
        case update: Update => merge(trees, updateItemTrees(packageName, update))
        case updateBox: UpdateBox => merge(trees, updateBoxItemTrees(packageName, updateBox))
        case struct: Struct => merge(trees, structItemTrees(packageName, struct))
        case enum: Enum => merge(trees, enumItemTrees(packageName, enum))
        case trai: Trait => merge(trees, Vector(trai))
      }
    }

    val (traitGlobalRefsV, traitTreesV): (Vector[Vector[Tree]], Vector[Vector[Tree]]) = (traits map (trai =>
      traitItemTrees(packageName, trai, allChildren.filter(_.traitExt.map(_.name) == Some(trai.name)))
    )).unzip

    (globalRefs ++ traitGlobalRefsV.flatten, BLOCK(trees ++ traitTreesV.flatten))
  }

  // TODO: hex
  private def dec2headerDef(decHeader: Int): Tree =
    VAL("header") := LIT(decHeader)

  private def rpcItemTrees(packageName: String, rpc: RpcContent): (Vector[Tree], Vector[Tree]) = {
    val className = f"Request${rpc.name}%s"

    val params = paramsTrees(rpc.attributes)

    val (responseRef, (globalResponseRefs, responseTrees)) = rpc.response match {
      case ReferenceRpcResponse(name) =>
        (
          REF(f"Refs.Response$name%s"),
          (Vector.empty, Vector.empty)
        )
      case resp: AnonymousRpcResponse =>
        (
          REF(f"Refs.Response${rpc.name}%s"),
          namedResponseItemTrees(packageName, resp.toNamed(rpc.name))
        )
    }

    val headerDef = dec2headerDef(rpc.header)
    val responseRefDef = VAL("Response") := responseRef

    val classTrees = serializationTrees(
      packageName,
      className,
      rpc.attributes
    )

    val deserTrees = deserializationTrees(
      packageName,
      className,
      rpc.attributes
    )

    val objectTrees = Vector(headerDef, responseRefDef) ++ deserTrees

    val (globalRequestRefs, requestTrees) = classWithCompanion(
      packageName,
      className,
      Vector(valueCache("RpcRequest")),
      params,
      classTrees,
      objectTrees
    )

    (
      globalRequestRefs ++ globalResponseRefs,
      requestTrees ++ responseTrees
    )
  }

  /**
    *
    *  @returns response builder ref and response definition trees
    */
  private def namedResponseItemTrees(
    packageName: String,
    resp: NamedRpcResponse
  ): (Vector[Tree], Vector[Tree]) = {
    val className = f"Response${resp.name}%s"

    val params = paramsTrees(resp.attributes)

    val headerDef = dec2headerDef(resp.header)

    val serTrees = serializationTrees(
      packageName,
      className,
      resp.attributes
    )
    val deserTrees = deserializationTrees(packageName, className, resp.attributes)

    classWithCompanion(packageName, className, Vector(valueCache("RpcResponse")), params, serTrees, Vector(headerDef) ++ deserTrees)
  }

  private def traitItemTrees(packageName: String, trai: Trait, children: Vector[NamedItem]): (Vector[Tree], Vector[Tree]) = {
    val globalRefs = Vector(
      TYPEVAR(trai.name) := REF(f"$packageName%s.${trai.name}%s"),
      VAL(trai.name) := REF(f"$packageName%s.${trai.name}%s")
    )

    val traitDef = TRAITDEF(trai.name) := BLOCK(
      traitSerializationTrees(trai.name, children)
    )

    val objDef = OBJECTDEF(trai.name) := BLOCK(
      traitDeserializationTrees(trai.name, children)
    )

    (
      globalRefs,
      Vector(traitDef, objDef)
    )
  }

  private def updateItemTrees(packageName: String, update: Update): (Vector[Tree], Vector[Tree]) = {
    val className = f"Update${update.name}%s"
    val params = paramsTrees(update.attributes)
    val headerDef = dec2headerDef(update.header)

    val serTrees = serializationTrees(
      packageName,
      className,
      update.attributes
    )

    val deserTrees = deserializationTrees(
      packageName,
      className,
      update.attributes
    )

    classWithCompanion(packageName, className, Vector(valueCache("Update")), params, serTrees, Vector(headerDef) ++ deserTrees)
  }

  private def updateBoxItemTrees(packageName: String, ub: UpdateBox): (Vector[Tree], Vector[Tree]) = {
    val params = paramsTrees(ub.attributes)

    if (params.isEmpty) {
      (
        Vector(VAL(ub.name) := REF(f"$packageName%s.${ub.name}%s")),
        Vector(CASEOBJECTDEF(ub.name) withParents(valueCache("UpdateBox")))
      )
    } else {
      (
        Vector(TYPEVAR(ub.name) := REF(f"$packageName%s.${ub.name}%s")),
        Vector(CASECLASSDEF(ub.name) withParents(valueCache("UpdateBox")) withParams(params))
      )
    }
  }

  private def structItemTrees(packageName: String, struct: Struct): TreesChildren = {
    val params = paramsTrees(struct.attributes)

    val serTrees = serializationTrees(
      packageName,
      struct.name,
      struct.attributes
    )

    val deserTrees = deserializationTrees(
      packageName,
      struct.name,
      struct.attributes
    )

    val (parents, traitChildren) = struct.`trait` match {
      case Some(traitExt @ TraitExt(traitName, traitKey)) =>
        (
          Vector(typeRef(valueCache(traitName))),
          Vector(traitExt)
        )
      case None => (Vector.empty, Vector.empty)
    }

    classWithCompanion(packageName, struct.name, parents, params, serTrees, deserTrees) match {
      case (globalRefs, trees) =>
        (globalRefs, trees, Vector(struct))
    }
  }

  // TODO: use custom json formatters here
  private def enumItemTrees(packageName: String, enum: Enum): (Vector[Tree], Vector[Tree]) = {
    if (enum.values.length > 0) {
      val valuesTrees = enum.values map {
        case EnumValue(id, name) =>
          VAL(name) withType(valueCache(enum.name)) := Apply(valueCache("Value"), LIT(id))
      }

      val traitDef = TRAITDEF(enum.name) withParents("Enumeration")

      val typeAlias = (TYPEVAR(enum.name) := typeRef(valueCache("Value")))

      val objDef = OBJECTDEF(enum.name) withParents(enum.name) := BLOCK(
        Seq(typeAlias) ++ valuesTrees
      )

      (
        Vector(
          TYPEVAR(enum.name) := REF(f"$packageName%s.${enum.name}%s.${enum.name}%s"),
          VAL(enum.name) := REF(f"$packageName%s.${enum.name}%s")
        ),
        Vector(traitDef, objDef)
      )
    } else {
      deserializationError("Enum attributes should be a non-empty JsArray")
    }
  }

  private def paramsTrees(attributes: Vector[Attribute]): Vector[ValDef] = {
    attributes.sortBy(_.id) map { attr =>
      PARAM(attr.name, attrType(attr.typ)).tree
    }
  }

  private def classWithCompanion(
    packageName: String,
    name: String,
    parents: Vector[Type],
    params: Vector[ValDef],
    classTrees: Vector[Tree],
    objectTrees: Vector[Tree]
  ): (Vector[Tree], Vector[Tree]) = {
    val ref = REF(f"$packageName%s.$name%s")
    val objRef = VAL(name) := ref

    if (params.isEmpty) {
      (
        Vector(objRef),
        Vector(
          TRAITDEF(name) withParents(parents),
          CASEOBJECTDEF(name) withParents(valueCache(name)) := BLOCK(
            objectTrees ++ classTrees
          )
        )
      )
    } else {
      (
        Vector(
          TYPEVAR(name) := ref, objRef
        ),
        Vector(
          CASECLASSDEF(name) withParents(parents) withParams(params) := BLOCK(classTrees),
          OBJECTDEF(name) := BLOCK(objectTrees)
        )
      )
    }
  }
}
