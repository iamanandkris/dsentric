package dsentric.contracts

import cats.data.NonEmptyList
import dsentric.codecs.std.DCodecs
import dsentric.{Available, DNull, DObject, DeltaEmpty, DeltaFailed, DeltaReduce, DeltaReduced, DeltaRemove, DeltaRemoving, Failed, Found, NotFound, Path, Raw, RawArray, RawObject, RawObjectOps, RawOps}
import dsentric.codecs.{DCodec, DCollectionCodec, DContractCodec, DCoproductCodec, DMapCodec, DProductCodec, DTypeContractCodec, DValueClassCodec, DValueCodec}
import dsentric.failure.{ClosedContractFailure, ContractTypeResolutionFailure, CoproductTypeValueFailure, Failure, IncorrectKeyTypeFailure, IncorrectTypeFailure, ValidResult}
import shapeless.HList

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
 * Reduces deltas that do no change, or are effectively empty
 * (An empty object will not replace or remove a Value)
 */
private[dsentric] trait DeltaReduceOps extends ReduceOps {
  /**
   * Delta on the object only needs to check the properties that it is changing to valid.
   * It is not the responsibility of the delta to be validated against incorrect types or expected requirements
   * in the object that it is not changing.
   * @param baseContract
   * @param delta
   * @param currentObject
   * @param badTypes
   * @tparam D
   * @return
   */
  def deltaReduce[D <: DObject](baseContract:BaseContract[D], delta:Raw, currentObject:RawObject, badTypes:BadTypes):DeltaReduce[RawObject] = {
    def deltaReduceAdditionalProperties(deltaObject:RawObject):ValidResult[RawObject] = {
      val exclude = baseContract._fields.keySet
      baseContract match {
        case a:AdditionalProperties[Any, Any]@unchecked =>
          val (baseDelta, additionalDelta) = deltaObject.partition(p => exclude(p._1))
          if (additionalDelta.nonEmpty) {
            val additionalCurrent = currentObject.filter(p => !exclude(p._1))
            deltaReduceMap(a._root, a._path, badTypes, DCodecs.keyValueMapCodec(a._additionalKeyCodec, a._additionalValueCodec), additionalDelta, additionalCurrent) match {
              case DeltaEmpty | DeltaRemove => //Delta Remove isnt possible in this instance
                ValidResult.success(baseDelta)
              case DeltaRemoving(delta) =>
                ValidResult.success(baseDelta ++ delta)
              case DeltaReduced(delta) =>
                ValidResult.success(baseDelta ++ delta)
              case DeltaFailed(head, tail) =>
                ValidResult.failure(head, tail)
            }
          }
          else
            ValidResult.success(baseDelta)
        case _ =>
          deltaObject.foldLeft[ValidResult[RawObject]](ValidResult.success(deltaObject)) {
            case (r, (key, _)) if exclude(key) =>
              r
            //Allow removing of existing additional properties that shouldn't be there
            case (Right(d), (key, DNull)) if currentObject.contains(key) =>
              ValidResult.success(d)
            case (Right(d), (key, DNull | RawObject.empty)) =>
              ValidResult.success(d - key)
            case (failed, (_, DNull | RawObject.empty)) =>
              failed
            case (Right(d), (key, __)) if badTypes == DropBadTypes =>
              ValidResult.success(d - key)
            case (Right(_), (key, __)) =>
              ValidResult.failure(ClosedContractFailure(baseContract._root, baseContract._path, key))
            case (f, _) if badTypes == DropBadTypes =>
              f
            case (Left(NonEmptyList(head, tail)), (key, _)) =>
              ValidResult.failure(ClosedContractFailure(baseContract._root, baseContract._path, key), head :: tail)
          }
      }
    }

    delta match {
      case DNull =>
        DeltaRemove
      case deltaObject:RawObject@unchecked =>
        val drop = badTypes.nest == DropBadTypes //not sure if this means anything in Delta, need to review
        val init = deltaReduceAdditionalProperties(deltaObject)
        baseContract._fields
          .view.filterKeys(deltaObject.contains)
          .foldLeft(init){
            case (Right(d), (_, p)) =>
              p.__reduceDelta(d, currentObject, drop)
            case (l@Left(nel), (_, p)) =>
              p.__reduceDelta(deltaObject, currentObject, drop) match {
                case Right(_) =>
                  l
                case Left(nel2) =>
                  Left(nel ::: nel2)
              }
          } match {
          case Left(nel) =>
            DeltaFailed(nel.head, nel.tail)
          case Right(delta) =>
            if (delta.isEmpty) DeltaEmpty
            else if (RawObjectOps.rightReduceConcatMap(currentObject, currentObject).isEmpty) //TODO improve performance
              DeltaRemoving(delta)
            else
              DeltaReduced(delta)
        }

      case _ if badTypes == DropBadTypes =>
        DeltaEmpty
    }
  }


  def deltaReduce[D <: DObject, T](propertyLens: ValuePropertyLens[D, T], delta:Raw, current:Raw, badTypes: BadTypes):DeltaReduce[Raw] = {
    val deltaReduce = deltaReduceCodec(propertyLens._root, propertyLens._path, badTypes)((propertyLens._codec, delta, current))
    propertyLens.__applyConstraints(current, deltaReduce, badTypes)
  }

  private def available2DeltaReduce[T]:Function[Available[T],DeltaReduce[T]] = {
    case Found(t) => DeltaReduced(t)
    case NotFound => DeltaEmpty
    case Failed(head, tail) => DeltaFailed(head, tail)
  }


  protected def deltaReduceCodec[D <: DObject, C](contract:ContractFor[D], path:Path, badTypes:BadTypes):Function[(DCodec[C], Raw, Raw), DeltaReduce[Raw]] = {
    case (_, DNull, _) =>
      DeltaRemove
    case (d:DValueCodec[C], delta, current) =>
      deltaReduceValue(contract, path, badTypes, d, delta, current)
    case (d:DMapCodec[C, _, _], deltaObject:RawObject@unchecked, current:Raw) =>
      deltaReduceMap(contract, path, badTypes, d, deltaObject, current)
    case (d:DCollectionCodec[C, _], deltaArray: RawArray@unchecked, current:Raw) =>
      available2DeltaReduce(reduceCollection(contract, path, badTypes, d, deltaArray)) match {
        case DeltaReduced(dr) if dr == current =>
          DeltaEmpty
        case d =>
          d
      }
    case (d: DContractCodec[_], rawObject:RawObject@unchecked, current) =>
      deltaReduceContract(contract, path, badTypes, d.contract, rawObject, current)
    case (d:DValueClassCodec[C, _], raw, current) =>
      deltaReduceCodec(contract, path, badTypes)((d.internalCodec,raw, current))
    case (d:DProductCodec[C, _, _], deltaArray: RawArray@unchecked, current:Raw) =>
      available2DeltaReduce(reduceProduct(contract, path, badTypes, d, deltaArray)) match {
        case DeltaReduced(dr) if dr == current =>
          DeltaEmpty
        case d =>
          d
      }
    case (d:DCoproductCodec[C, _], raw, current) =>
      deltaReduceCoproduct(contract, path, badTypes, d, raw, current)
    case (d:DTypeContractCodec[_], rawObject: RawObject@unchecked, current) =>
      deltaReduceTypeContract(contract, path, badTypes, d.contracts, d.cstr, rawObject, current)
    case _ if badTypes == DropBadTypes =>
      DeltaEmpty
    case (d, raw, _) =>
      DeltaFailed(IncorrectTypeFailure(contract, path, d, raw))
  }


  protected def deltaReduceValue[D <: DObject, V](contract:ContractFor[D], path:Path, badTypes:BadTypes, codec:DValueCodec[V], delta:Raw, current:Raw):DeltaReduce[Raw] =
    (delta, current) match {
      case (deltaObject:RawObject@unchecked, currentObject:RawObject@unchecked) =>
        RawObjectOps.rightDifferenceReduceMap(currentObject -> deltaObject) match {
          case None =>
            DeltaEmpty
          case Some(reducedDelta) =>
            val newState = RawObjectOps.rightReduceConcatMap(currentObject, reducedDelta)
            if (newState.isEmpty)
              DeltaRemoving(reducedDelta)
            else
              codec.unapply(newState) match {
                case None if badTypes == DropBadTypes =>
                  DeltaEmpty
                case None =>
                  DeltaFailed(IncorrectTypeFailure(contract, path, codec, reducedDelta))
                case Some(_) =>
                  DeltaReduced(reducedDelta)
              }
        }
      //If Delta is causing no change we still want it to fail if its no changing to an invalid value.
      case (deltaRaw:Raw, currentRaw) if currentRaw == deltaRaw  =>
        reduceValue(contract, path, badTypes, codec, deltaRaw) match {
          case Found(_) | NotFound =>
            DeltaEmpty
          case Failed(head, tail) =>
            DeltaFailed(head, tail)
        }

      case (deltaRaw:Raw, _) =>
        available2DeltaReduce(reduceValue(contract, path, badTypes, codec, deltaRaw))
    }

  protected def deltaReduceMap[D <: DObject, C, K, V](contract:ContractFor[D], path:Path, badTypes:BadTypes, codec:DMapCodec[C, K, V], deltaObject:RawObject, current:Raw):DeltaReduce[RawObject] =
    current match {
      case currentObject: RawObject@unchecked =>
        val nest = badTypes.nest
        deltaObject.map{
          case (key, deltaValue) if !currentObject.contains(key) && RawOps.reducesEmpty(deltaValue) =>
            key -> DeltaEmpty
          case (key, DNull) =>
            //cannot be Empty as checked above
            key -> DeltaRemove
          case (key, deltaValue) =>
            codec.keyCodec.unapply(key) -> currentObject.get(key) match {
              case (None, _) if nest == DropBadTypes =>
                key -> NotFound
              case (None, None) =>
                val failure = IncorrectKeyTypeFailure(contract, path, codec.keyCodec, key)
                reduceCodec(contract, path \ key, nest)(codec.valueCodec -> deltaValue) match {
                  case Failed(head, tail) =>
                    key -> DeltaFailed(failure, head :: tail)
                  case _ =>
                    key -> DeltaFailed(failure)
                }
              case (None, Some(currentValue)) =>
                val failure = IncorrectKeyTypeFailure(contract, path, codec.keyCodec, key)
                deltaReduceCodec(contract, path \ key, nest)((codec.valueCodec, deltaValue, currentValue)) match {
                  case DeltaFailed(head, tail) =>
                    key -> DeltaFailed(failure, head :: tail)
                  case _ =>
                    key -> DeltaFailed(failure)
                }
              case (Some(_), None) =>
                key -> available2DeltaReduce(reduceCodec(contract, path \ key, nest)(codec.valueCodec -> deltaValue))
              case (Some(_), Some(currentValue)) =>
                key -> deltaReduceCodec(contract, path \ key, nest)((codec.valueCodec, deltaValue, currentValue))
            }
        }.foldLeft[Either[ListBuffer[Failure], mutable.Builder[(String, Raw), Map[String, Raw]]]](Right(Map.newBuilder[String, Raw])){
          case (Right(mb), (key, DeltaReduced(d))) =>
            Right(mb.addOne(key -> d))
          case (Right(mb), (key, DeltaRemoving(d))) =>
            Right(mb.addOne(key -> d))
          case (Right(mb), (key, DeltaRemove)) =>
            Right(mb.addOne(key -> DNull))
          case (Right(_), (_, DeltaFailed(head, tail))) =>
            Left(new ListBuffer[Failure].addAll(head :: tail))
          case (Left(lb), (_, DeltaFailed(head, tail))) =>
            Left(lb.addAll(head :: tail))
          case (result, _) =>
            result
        } match {
          case Left(_) if badTypes == DropBadTypes =>
            DeltaEmpty
          case Left(lb) =>
            DeltaFailed(lb.head, lb.tail.result())
          case Right(mb) =>
            val map = mb.result()
            if (map.isEmpty) DeltaEmpty
            else {
              val applyDelta = RawObjectOps.rightReduceConcatMap(currentObject, map)
              if (codec.unapply(applyDelta).isEmpty) {
                if (badTypes == DropBadTypes)
                  DeltaEmpty
                else
                  DeltaFailed(IncorrectTypeFailure(contract, path, codec, applyDelta))
              }
              else if (applyDelta.isEmpty)
                DeltaRemoving(map)
              else
                DeltaReduced(map)
            }
        }
      case _ =>
        available2DeltaReduce(reduceMap(contract, path, badTypes, codec, deltaObject))
    }

  protected def deltaReduceContract[D <: DObject, D2 <: DObject](contract:ContractFor[D], path:Path, badTypes:BadTypes, codecContract:ContractFor[D2], deltaObject:RawObject, current:Raw):DeltaReduce[RawObject] =
    current match {
      case currentObject: RawObject@unchecked =>
        codecContract.__reduceDelta(deltaObject, currentObject, badTypes.nest == DropBadTypes) match {
          case _:DeltaFailed if badTypes == DropBadTypes =>
            DeltaEmpty
          case d:DeltaFailed =>
            d.rebase(contract, path)
          case dr =>
            dr
        }
      case _ =>
        available2DeltaReduce(reduceContract(contract, path, badTypes, codecContract, deltaObject))
    }

  protected def deltaReduceTypeContract[D <: DObject, D2 <: DObject](
                                                                      contract:ContractFor[D],
                                                                      path:Path,
                                                                      badTypes:BadTypes,
                                                                      typeCodecs:PartialFunction[D2, ContractFor[D2]],
                                                                      d2Cstr:RawObject => D2,
                                                                      deltaObject:RawObject,
                                                                      current:Raw):DeltaReduce[RawObject] =
    current match {
      case currentObject: RawObject@unchecked =>
        val newState = RawObjectOps.rightReduceConcatMap(currentObject, deltaObject)
        typeCodecs.lift(d2Cstr(currentObject)) -> typeCodecs.lift(d2Cstr(newState)) match {
          case (_, None) =>
            DeltaFailed(ContractTypeResolutionFailure(contract, path, deltaObject))
          case (Some(currentContract), maybeDeltaContract) if maybeDeltaContract.isEmpty | maybeDeltaContract.contains(currentContract) =>
            deltaReduceContract(contract, path, badTypes, currentContract, deltaObject, current)
          //Contract type has changed, need to validate delta operations as well as final state
          case (_, Some(deltaContract)) =>
            deltaReduceContract(contract, path, badTypes, deltaContract, deltaObject, current) match {
              case DeltaReduced(delta) =>
                val newReducedState = RawObjectOps.rightReduceConcatMap(currentObject, delta)
                reduceContract(contract, path, FailOnBadTypes, deltaContract, newReducedState) match {
                  case _:Failed if badTypes == DropBadTypes =>
                    DeltaEmpty
                  case Failed(head, tail) =>
                    DeltaFailed(head, tail)
                  case _ =>
                    DeltaReduced(delta)
                }
              case d =>
                d
            }
        }
      case _ =>
        available2DeltaReduce(reduceTypeContract(contract, path, badTypes, typeCodecs, d2Cstr, deltaObject))
    }

  protected def deltaReduceCoproduct[D <: DObject, T, H <: HList](contract:ContractFor[D], path:Path, badTypes:BadTypes, codec:DCoproductCodec[T, H], raw:Raw, current:Raw): DeltaReduce[Raw] = {
    codec.codecsList.foldLeft[Option[DeltaReduce[Raw]]](None){
      case (Some(f:DeltaFailed), c) =>
        deltaReduceCodec(contract, path, FailOnBadTypes)((c, raw, current)) match {
          case f2:DeltaFailed =>
            Some(f ++ f2)
          case d =>
            Some(d)
        }
      case (None, c) =>
        Some(deltaReduceCodec(contract, path, FailOnBadTypes)((c, raw, current)))
      case (a, _) =>
        a
    } match {
      case Some(_:DeltaFailed) if badTypes == DropBadTypes =>
        DeltaEmpty
      case Some(DeltaFailed(head, tail)) =>
        DeltaFailed(CoproductTypeValueFailure(contract, codec, path, head :: tail, raw))
      case Some(d) =>
        d
      case None =>
        DeltaEmpty
    }
  }
}

private[dsentric] object DeltaReduceOps extends DeltaReduceOps