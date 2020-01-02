package dsentric.schema

import dsentric._
import dsentric.contracts._
import dsentric.operators.Internal

object Definition {

  type Definitions = Vector[ObjectDefinition]
  type Infos = Vector[ContractInfo]

  def contractName[D <: DObject](contract:ContractFor[D]):String = {
    SchemaReflection.getDisplayName(contract)
  }

  def contractObjectDefinitionRef[D <: DObject](contract:ContractFor[D], defs:Definitions, forceNested:Boolean = false):(String, Definitions) = {
    val name = contractName(contract)
    defs.find(_.definition.contains(name))
      .map{_ => name -> defs}
      .getOrElse{
        val (contractInfo, infos) = SchemaReflection.getContractInfo(contract, Vector.empty)
        val (c, _, d) = baseContractObjectDefinition(contract._fields, contractInfo, infos, Vector.empty, false)
        name -> (d :+ c)
      }
  }

  def nestedContractObjectDefinition[D <: DObject](contract:ContractFor[D]):ObjectDefinition = {
    val (contractInfo, infos) = SchemaReflection.getContractInfo(contract, Vector.empty)
    val (c, _, _) = baseContractObjectDefinition(contract._fields,contractInfo, infos, Vector.empty, true)
    c
  }

  def contractObjectDefinitions[D <: DObject](contract:BaseContract[D]):(ObjectDefinition, Definitions) = {
    val (contractInfo, newInfos) = SchemaReflection.getContractInfo(contract, Vector.empty)
    val (c, _, d) = baseContractObjectDefinition(contract._fields, contractInfo, newInfos, Vector.empty, false)
    c -> d
  }

  private def baseContractObjectDefinition[D <: DObject](fields:Map[String, Property[D, _]], contractInfo:ContractInfo, infos:Infos, defs:Definitions, forceNested:Boolean):(ObjectDefinition, Infos, Definitions) = {
    val properties = findPropertyAnnotations(fields, contractInfo, forceNested)
    val (propertyDefs, newInfos, newDefs) = contractPropertyDefinitions(properties, infos, defs, forceNested)
    val (referencedDefinitions, newInfos2, newDefs2) =
      if (!contractInfo.schemaAnnotations.nested && !forceNested)
        inheritFold(fields, contractInfo.inherits, newInfos, newDefs, forceNested)
      else
        (Vector.empty, newInfos, newDefs)

    val objDef =
      ObjectDefinition(contractInfo.schemaAnnotations.typeName.orElse(contractInfo.displayName),
        contractInfo.schemaAnnotations.title,
        contractInfo.schemaAnnotations.description,
        referencedDefinitions,
        propertyDefs,
        Left(!contractInfo.isClosed)
      )
    (objDef, newInfos2, newDefs2)
  }

  private def contractPropertyDefinitions[D <: DObject](properties:Iterable[(String, Property[D, _], SchemaAnnotations)], infos:Infos, defs:Definitions, forceNested:Boolean):(Set[PropertyDefinition], Infos, Definitions) = {
    properties
      .foldLeft((Set.empty[PropertyDefinition], infos, defs)) {
        case (a, (_, prop, _)) if skipProperty(prop) =>
          a
        //Nested, display all properties
        case ((p, infos0, defs0), (name, b:BaseContract[D]@unchecked with Property[D, _], schema)) if schema.nested || forceNested =>
          val (objectDefinition, infos1, defs1) = resolveNestedContract(b, infos0, defs0, forceNested, Some(schema))
          val resolvedDefinition = resolveDataOperators(b, objectDefinition)
          val property = PropertyDefinition(name, resolvedDefinition, schema.examples, getDefault(b), isRequired(b), schema.description)
          (p + property, infos1, defs1)

        case ((p, infos0, defs0), (name, b:BaseContract[D]@unchecked with Property[D ,_], schema)) =>
          val (bInfo, infos1) = SchemaReflection.getContractInfo(b, infos0)
          //Internal object is a single type inheritance
          if (bInfo.inherits.size == 1 && bInfo.fields.isEmpty && b._dataOperators.isEmpty) {
            val (ref, infos2, defs1) = baseContractObjectDefinition(b._fields, bInfo.inherits.head, infos1, defs0, false)
            val refName = ref.definition.getOrElse(throw SchemaGenerationException("Referenced trait cannot be unnamed")) //should never happen
            val property = PropertyDefinition(name, ByRefDefinition(refName), schema.examples, getDefault(b), isRequired(b), schema.description)
            (p + property, infos2, defs1 :+ ref)
          }
          else {
            val subProperties = findPropertyAnnotations(b._fields, bInfo, false)
            val (subPropertyDefs, infos2, defs1) = contractPropertyDefinitions(subProperties, infos1, defs0, false)
            val (inheritedRef, infos3, defs2) = inheritFold(b._fields, bInfo.inherits, infos2, defs1, false)
            val objectDefinition =
              ObjectDefinition(
                None,
                bInfo.schemaAnnotations.title,
                bInfo.schemaAnnotations.description,
                inheritedRef,
                subPropertyDefs,
                Left(!bInfo.isClosed)
              )
            val resolvedDefinition = resolveDataOperators(b, objectDefinition)

            val property = PropertyDefinition(name, resolvedDefinition, schema.examples, getDefault(b), isRequired(b), schema.description)
            (p + property, infos3, defs2)
          }

        case ((p, infos0, defs0), (name, prop:ObjectsProperty[D, _], schema)) if schema.nested || forceNested =>
          val (objectDefinition, infos1, defs1) = resolveNestedContract(prop._contract, infos0, defs0, forceNested, None)
          val arrayDefinition = ArrayDefinition(Vector(objectDefinition))
          val resolvedDefinition = resolveDataOperators(prop, arrayDefinition)
          val required = resolvedDefinition.minLength.exists(_ > 0)
          val property = PropertyDefinition(name, resolvedDefinition, schema.examples, None, required, schema.description)
          (p + property, infos1, defs1)

        case ((p, infos0, defs0), (name, prop:ObjectsProperty[D, _], schema)) =>
          ???

        case ((p, infos, defs), (name, prop, schema)) =>
          val resolvedDefinition = resolveDataOperators(prop, prop._codec.typeDefinition)
          val property = PropertyDefinition(name, resolvedDefinition, schema.examples, getDefault(prop), isRequired(prop), schema.description)
          (p + property, infos, defs)
    }
  }
  //TODO work out schema override
  private def resolveNestedContract[D <: DObject](contract:BaseContract[D], infos: Infos, defs:Definitions, forceNested:Boolean, schemaOverride:Option[SchemaAnnotations]): (ObjectDefinition, Infos, Definitions) = {
    val (bInfo, infos1) = SchemaReflection.getContractInfo(contract, infos)
    val subProperties = findPropertyAnnotations(contract._fields, bInfo, true)
    val (subPropertyDefs, infos2, defs1) = contractPropertyDefinitions(subProperties, infos1, defs, forceNested)
    val objectDefinition =
      ObjectDefinition(
        bInfo.schemaAnnotations.typeName,
        bInfo.schemaAnnotations.title,
        bInfo.schemaAnnotations.description,
        Vector.empty,
        subPropertyDefs,
        Left(!bInfo.isClosed)
      )
    (objectDefinition, infos2, defs1)
  }

  private def resolveDataOperators[D <: DObject, T <: TypeDefinition](property:Property[D, _], typeDef:T):T =
    property._dataOperators.foldLeft(typeDef)((a, d) => d.definition.lift(a).getOrElse(a))

  private def getDefault[D <: DObject](p:Property[D, _]): Option[Any] =
    p match {
      case d:DefaultProperty[_, Any]@unchecked =>
        Some(d._codec(d._default).value)
      case _ => None
    }

  private def skipProperty[D <: DObject](p:Property[D, _]): Boolean =
    p._dataOperators.contains(Internal)

  private def isRequired[D <: DObject](p:Property[D, _]): Boolean =
    p.isInstanceOf[ExpectedProperty[D, _]] || p.isInstanceOf[ExpectedObjectProperty[D]]

  private def inheritFold[D <: DObject](fields:Map[String, Property[D, _]], inherits:Vector[ContractInfo], infos:Vector[ContractInfo], defs:Vector[ObjectDefinition], forceNested:Boolean): (Vector[String], Vector[ContractInfo], Vector[ObjectDefinition]) =
    inherits.foldLeft((Vector.empty[String], infos, defs)){
      case ((r, infos0, defs0), ci) =>
        val n = ci.schemaAnnotations.typeName.orElse(ci.displayName).getOrElse(throw SchemaGenerationException("Inherited contract cannot be anonymous"))
        defs0.find(o => o.definition.contains(n)) match {
          case Some(_) =>
            (r :+ n, infos0, defs0)
          case None =>
            val (o, infos1, defs1) = baseContractObjectDefinition(fields, ci, infos0, defs0, forceNested)
            (r :+ n, infos1, defs1 :+ o)
        }
    }

  private def findPropertyAnnotations[D <: DObject](fields:Map[String, Property[D, _]], info:ContractInfo, nestedOverride:Boolean): Iterable[(String, Property[D, _], SchemaAnnotations)] =
    fields.flatMap { field =>
      val schema =
        if (nestedOverride || info.schemaAnnotations.nested)
          info.getInheritedFieldAnnotation(field._1)
        else
          info.fields.get(field._1)
      schema.map(s => (field._1, field._2, s))
    }

}