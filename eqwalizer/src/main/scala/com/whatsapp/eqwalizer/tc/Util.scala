/* Copyright (c) Meta Platforms, Inc. and affiliates. All rights reserved.
 *
 * This source code is licensed under the Apache 2.0 license found in
 * the LICENSE file in the root directory of this source tree.
 */

package com.whatsapp.eqwalizer.tc

import com.whatsapp.eqwalizer.ast.Forms._
import com.whatsapp.eqwalizer.ast.Types._
import com.whatsapp.eqwalizer.ast.stub.DbApi
import com.whatsapp.eqwalizer.ast.{Id, RemoteId}

import scala.collection.immutable.TreeSeqMap

class Util(pipelineContext: PipelineContext) {
  private val module = pipelineContext.module
  private var recordCache: Map[(String, String), Option[RecDeclTyped]] = Map.empty

  def globalFunId(module: String, id: Id): RemoteId = {
    val imports = DbApi.getImports(module).get
    val hostModule = imports.getOrElse(id, module)
    RemoteId(hostModule, id.name, id.arity)
  }

  def getFunType(module: String, id: Id): Option[FunType] =
    getFunType(globalFunId(module, id))

  private def typeAndGetRecord(module: String, name: String): Option[RecDeclTyped] =
    DbApi.getRecord(module, name) map { rec =>
      var fields: TreeSeqMap[String, RecFieldTyped] = TreeSeqMap.empty
      var refinable: Boolean = false
      rec.fields.map(recField).foreach { f =>
        fields += f.name -> f
        refinable = refinable || f.refinable
      }
      RecDeclTyped(rec.name, fields, refinable, rec.file)
    }

  def getRecord(module: String, name: String): Option[RecDeclTyped] = {
    if (recordCache.contains((module, name))) {
      recordCache((module, name))
    } else {
      val recDecl = typeAndGetRecord(module, name)
      recordCache += ((module, name) -> recDecl)
      recDecl
    }
  }

  private def recField(f: RecField): RecFieldTyped = {
    val tp = f.tp match {
      case Some(tp) =>
        tp
      case None =>
        if (pipelineContext.gradualTyping)
          DynamicType
        else
          AnyType
    }
    RecFieldTyped(f.name, tp, f.defaultValue, f.refinable)
  }

  def getFunType(fqn: RemoteId, gradual: Boolean = pipelineContext.gradualTyping): Option[FunType] = {
    val funType = DbApi.getSpec(fqn.module, Id(fqn.name, fqn.arity)).map(_.ty)
    if (funType.isEmpty && gradual) {
      val arity = fqn.arity
      Some(FunType(Nil, List.fill(arity)(DynamicType), DynamicType))
    } else {
      funType
    }
  }

  def getOverloadedSpec(fqn: RemoteId): Option[OverloadedFunSpec] =
    DbApi.getOverloadedSpec(fqn.module, Id(fqn.name, fqn.arity))

  def enterScope(env0: Env, scopeVars: Set[String]): Env = {
    var env = env0
    for {
      v <- scopeVars if !env0.contains(v)
    } env = env.updated(v, AnyType)
    env
  }

  def exitScope(env0: Env, env1: Env, scopeVars: Set[String]): Env = {
    val allVars = env0.keySet ++ scopeVars
    env1.view.filterKeys(allVars).toMap
  }

  def getTypeDeclBody(remoteId: RemoteId, args: List[Type]): Type = {
    remoteId match {
      case RemoteId("eqwalizer", "dynamic", 0) if pipelineContext.gradualTyping =>
        return DynamicType
      case RemoteId("eqwalizer", "dynamic", 1) if pipelineContext.gradualTyping =>
        return BoundedDynamicType(args.head)
      case _ =>
    }
    val id = Id(remoteId.name, remoteId.arity)
    def applyType(decl: TypeDecl): Type = {
      val subst = decl.params.zip(args).map { case (VarType(n), ty) => n -> ty }.toMap
      Subst.subst(subst, decl.body)
    }

    DbApi
      .getType(remoteId.module, id)
      .map(applyType)
      .getOrElse({
        if (pipelineContext.module == remoteId.module) {
          // TODO: remove failsafe after T172093135
          DbApi.getPrivateOpaque(module, id) match {
            case Some(ty) => applyType(ty)
            case None     => DynamicType
          }
        } else {
          if (!DbApi.getPrivateOpaque(remoteId.module, id).isDefined) {
            DynamicType
          }
          OpaqueType(remoteId, args)
        }
      })
  }

  def flattenUnions(ty: Type): List[Type] = ty match {
    case UnionType(tys) =>
      tys.flatMap(flattenUnions).toList
    case _ => List(ty)
  }

  def isFunType(ty: Type, arity: Int): Boolean = ty match {
    case FunType(_, argTys, _) if argTys.size == arity       => true
    case DynamicType                                         => true
    case NoneType                                            => true
    case AnyFunType if pipelineContext.gradualTyping         => true
    case AnyArityFunType(_) if pipelineContext.gradualTyping => true
    case RemoteType(rid, argTys) =>
      val body = getTypeDeclBody(rid, argTys)
      isFunType(body, arity)
    case UnionType(tys) =>
      tys.forall(isFunType(_, arity))
    case BoundedDynamicType(_) => true
    case _                     => false
  }
}
