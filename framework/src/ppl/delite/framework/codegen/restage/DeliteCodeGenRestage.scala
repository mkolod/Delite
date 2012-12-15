package ppl.delite.framework.codegen.restage

import java.io.PrintWriter
import reflect.{SourceContext, RefinedManifest}
import scala.virtualization.lms.internal._
import scala.virtualization.lms.common._

import ppl.delite.framework.codegen.Target
import ppl.delite.framework.codegen.delite.overrides._
import ppl.delite.framework.datastructures._
import ppl.delite.framework.{DeliteRestageOps,DeliteRestageOpsExp}
import ppl.delite.framework.ops.{DeliteOpsExp,DeliteCollection,DeliteCollectionOpsExp,ScalaGenDeliteCollectionOps}

trait TargetRestage extends Target {
  import IR._

  val name = "Restage"
}

trait RestageCodegen extends ScalaCodegen with Config {
  val IR: Expressions 
  import IR._

  // should be set by DeliteRestage if there are any transformations to be run before codegen
  var transformers: List[WorklistTransformer{val IR: RestageCodegen.this.IR.type}] = Nil
  
  override def kernelFileExt = "scala"

  override def toString = "restage"
  
  def emitHeader(out: PrintWriter, append: Boolean) {
    if (!append) {    
      // restage header
      out.println("import ppl.delite.framework.{DeliteILApplication,DeliteILApplicationRunner}")
      out.println("import ppl.delite.framework.datastructures.{DeliteArray,DeliteArrayBuffer}")
      out.println("import ppl.delite.framework.ops.DeliteCollection")
      out.println("import scala.virtualization.lms.util.OverloadHack")
      out.println("import reflect.{RefinedManifest,SourceContext}")
      out.println()
      out.println("object RestageApplicationRunner extends DeliteILApplicationRunner with RestageApplication")
      out.println("trait RestageApplication extends DeliteILApplication with OverloadHack {")      
      out.println("/* Emitting re-stageable code */")
      out.println("def main() {")
      out.println("val x0 = args;")
      out.println("{")
    }
    else {
      out.println("{")
    }    
  }  
}

trait RestageFatCodegen extends GenericFatCodegen with RestageCodegen {
  val IR: Expressions with Effects with FatExpressions
  import IR._
    
  override def emitSource[A : Manifest](args: List[Sym[_]], body: Block[A], className: String, out: PrintWriter) = {
    val staticData = getFreeDataBlock(body)
    
    println("--RestageCodegen emitSource")
    
    var b = body
    for (t <- transformers) {
      b = t.run(b)
    }
    
    withStream(out) {
      emitBlock(b)
      // stream.println("setLastScopeResult(" + quote(getBlockResult(body)) + ")")
    }    
    
    staticData
  }    

}

// for now just lumping all the Delite restage generators together in one place..
trait DeliteCodeGenRestage extends RestageFatCodegen 
  with ScalaGenDeliteCollectionOps with ScalaGenDeliteArrayOps with ScalaGenDeliteStruct with DeliteScalaGenAllOverrides {
    
  val IR: Expressions with Effects with FatExpressions with DeliteRestageOpsExp 
          with IOOpsExp with PrimitiveOpsExp with MathOpsExp with RangeOpsExp
          with DeliteCollectionOpsExp with DeliteArrayFatExp with DeliteOpsExp with DeliteAllOverridesExp
  import IR._
  import ppl.delite.framework.Util._

  override def remap[A](m: Manifest[A]): String = {
    val ms = manifest[String]
    m match {
      case `ms` => "String"
      case s if s.erasure.getSimpleName == "Tuple2" => "Record" // this is custom overridden in ScalaGenTupleOps 
      case s if s.erasure.getSimpleName == "DeliteArrayBuffer" => "DeliteArrayBuffer[" + remap(s.typeArguments(0)) + "]"
      case s if s.erasure.getSimpleName == "DeliteArray" => m.typeArguments(0) match {
        case StructType(_,_) => "DeliteArray[" + remap(s.typeArguments(0)) + "]" // "Record" // need to maintain DeliteArray-ness even when a record
        case arg => "DeliteArray[" + remap(arg) + "]"
      }
      case s if (s <:< manifest[Record] && isSubtype(s.erasure,classOf[DeliteCollection[_]])) => 
        "DeliteCollection[" + remap(s.typeArguments(0)) + "]" 
      case s if s <:< manifest[Record] => "Record" // should only be calling 'field' on records at this level      
      // case s if isSubtype(s.erasure,classOf[Record]) => "Record"  
      case _ => 
        // Predef.println("calling remap on: " + m.toString)
        // Predef.println("m.erasure: " + m.erasure)
        // Predef.println("m.simpleName: " + m.erasure.getSimpleName)
        // for (cls <- m.erasure.getInterfaces()) {
        //   println("  intf: " + cls.getSimpleName)
        // }
        // println("  superclass: " + m.erasure.getSuperlass().getSimpleName)
        // Predef.println("m.getInterfaces: " + m.erasure.getInterfaces())
        super.remap(m)
    }
  }
  
  override def quote(x: Exp[Any]) : String = x match {
    case Const(s: String) => (super.quote(x)).replace("\\", "\\\\") // need extra backslashes since we are going through an extra layer
    case _ => 
      // Predef.println("called super on quote: " + x)
      super.quote(x)
  }

  def quoteTag[T](tag: StructTag[T], tp: Manifest[Any]): String = tag match {
    case ClassTag(name) => "ClassTag(\""+name+"\")"
    case NestClassTag(elem) => quoteTag(elem,tp) //"NestClassTag[Var,"+remap(tp)+"]("+quoteTag(elem,tp)+")" // dropping the var wrapper...
    case AnonTag(fields) => "ClassTag(\"erased\")"
    case SoaTag(base, length) => "SoaTag(" + quoteTag(base,tp) + ", " + quote(length) + ")"
    case MapTag() => "MapTag()"
  } 
       
  def recordFieldLookup[T](initStruct: String, fields: List[String], tp: Manifest[T]): String = {        
    if (fields.length == 1) 
      "field[" + remap(tp) + "](" + initStruct + ", \"" + fields.head + "\")"
    else
      "field[Record](" + recordFieldLookup(initStruct, fields.tail, tp) + ", \"" + fields.head + "\")"
  }    
  
  
  override def emitNode(sym: Sym[Any], rhs: Def[Any]) = rhs match {    
    // data exchange
    case ReturnScopeResult(n) => emitValDef(sym, "setScopeResult(" + quote(n) + ")")
    case LastScopeResult() => emitValDef(sym, "getScopeResult") 
    
    // scala
    case ObjBrApply(f) => emitValDef(sym, "BufferedReader(" + quote(f) + ")")
    case ObjFrApply(s) => emitValDef(sym, "FileReader(" + quote(s) + ")")    
    case ThrowException(m) => emitValDef(sym, "fatal(" + quote(m) + ")")
    case NewVar(init) => stream.println("var " + quote(sym) + " = " + quote(init))
    case ObjIntegerParseInt(s) => emitValDef(sym, "Integer.parseInt(" + quote(s) + ")")
    case RepIsInstanceOf(x,mA,mB) => emitValDef(sym, quote(x) + ".isInstanceOf[Rep[" + remap(mB) + "]]")
    case RepAsInstanceOf(x,mA,mB) => emitValDef(sym, quote(x) + ".asInstanceOf[Rep[" + remap(mB) + "]]")    
    case MathMax(x,y) => emitValDef(sym, "Math.max(" + quote(x) + ", " + quote(y) + ")")
    
    // Range foreach
    // !! this is unfortunate: we need the var to be typed differently, but most of this is copy/paste
    case RangeForeach(start, end, i, body) => {
      stream.println("var " + quote(i) + " = " + quote(start))
      stream.println("val " + quote(sym) + " = " + "while (" + quote(i) + " < " + quote(end) + ") {")
      emitBlock(body)
      stream.println(quote(getBlockResult(body)))
      stream.println(quote(i) + " = " + quote(i) + " + 1")
      stream.println("}")
    }
    
    
    // if then else
    // !! redundant - copy paste of LMS if/then/else just to avoid DeliteIfThenElse getting a hold of it, which is put in scope by the individual DSLs
    case DeliteIfThenElse(c,a,b,h) =>
      stream.println("val " + quote(sym) + " = if (" + quote(c) + ") {")
      emitBlock(a)
      stream.println(quote(getBlockResult(a)))
      stream.println("} else {")
      emitBlock(b)
      stream.println(quote(getBlockResult(b)))
      stream.println("}")

    // delite array
    case a@DeliteArrayNew(n) => emitValDef(sym, "DeliteArray[" + remap(a.mA) + "](" + quote(n) + ")")
    case DeliteArrayCopy(src,srcPos,dest,destPos,len) => emitValDef(sym, "darray_unsafe_copy(" + quote(src) + "," + quote(srcPos) + "," + quote(dest) + "," + quote(destPos) + "," + quote(len) + ")")
    case DeliteArrayGetActSize() => emitValDef(sym, "darray_unsafe_get_act_size()")
    case DeliteArraySetActBuffer(da) => emitValDef(sym, "darray_unsafe_set_act_buf(" + quote(da) + ")")
    case DeliteArraySetActFinal(da) => emitValDef(sym, "darray_unsafe_set_act_final(" + quote(da) + ")")
    
    // structs
    // case s@SimpleStruct(tag, elems) =>
    case Struct(tag, elems) =>
      // oops.. internal scalac error
      // emitValDef(sym, "anonStruct(" + elems.asInstanceOf[Seq[(String,Rep[Any])]].map{case (k,v) => "(\"" + k + "\", " + quote(v) + ")" }.mkString(",") + ")")
       
      val isVar = elems(0)._2 match {
        case Def(Reflect(NewVar(x),u,es)) => true
        case _ => false
      }
      val tp = /*if (isVar) "Var["+remap(sym.tp)+"]" else*/ remap(sym.tp)       
      val structMethod = if (isVar) "mstruct" else "struct"      
      emitValDef(sym, structMethod + "[" + tp + "](" + quoteTag(tag,sym.tp) + ", " + elems.asInstanceOf[Seq[(String,Rep[Any])]].map{t => "(\"" + t._1 + "\", " + quote(t._2) + ")" }.mkString(",") + ")" +
        "(new RefinedManifest[" + tp + "]{ def erasure = classOf[" + tp + "]; override def typeArguments = List("+sym.tp.typeArguments.map(a => "manifest[" + remap(a) + "]").mkString(",") + "); def fields = List(" + elems.map(t => ("\""+t._1+"\"","manifest["+remap(t._2.tp)+"]")).mkString(",") + ")}," +
        "implicitly[Overloaded1], implicitly[SourceContext])")
      
    
    case FieldApply(struct, index) => emitValDef(sym, "field[" + remap(sym.tp) + "](" + quote(struct) + ",\"" + index + "\")")    
    case FieldUpdate(struct, index, rhs) => emitValDef(sym, "field_update[" + remap(sym.tp) + "](" + quote(struct) + ",\"" + index + "\"," + quote(rhs) + ")")
    case NestedFieldUpdate(struct, fields, rhs) => 
      assert(fields.length > 0)      
      // x34.data.id(x66)
      // field[T](field[Record](x34, "data"), "id")
      if (fields.length == 1) { // not nested
        emitValDef(sym, "field_update[" + remap(rhs.tp) + "](" + quote(struct) + ",\"" + fields(0) + "\"," + quote(rhs) + ")")
      }
      else {
        val f = "field[Record](" + quote(struct) + ", \"" + fields.head + "\")"
        emitValDef(sym, "field_update(" + recordFieldLookup(f, fields.tail, rhs.tp) + ", " + quote(rhs) + ")")        
      }
   
    case StructUpdate(struct, fields, idx, x) =>
      assert(fields.length > 0)
      if (fields.length == 1) { // not nested
        emitValDef(sym, "darray_update(field[DeliteArray[Any]](" + quote(struct) + ", \"" + fields.head + "\"), " + quote(idx) + ", " + quote(x) + ")")
      }
      else {
        val f = "field[Record](" + quote(struct) + ", \"" + fields.head + "\")"
        emitValDef(sym, "darray_update(" + recordFieldLookup(f, fields.tail, manifest[DeliteArray[Any]]) + ", " + quote(idx) + ", " + quote(x) + ")")
      }
    
    // delite ops
    case s:DeliteOpSingleTask[_] => 
      // each stm inside the block must be restageable..
      emitBlock(s.block)
      stream.print("val " + quote(sym) + " = ")
      stream.println(quote(getBlockResult(s.block)))
      
    case e:DeliteOpExternal[_] => 
      // DeliteOpExternals are broken right now - we can't generate the JNI stuff from the external node alone... what to do?
      // use --nb? hack the JNI methods in (e.g. by copying?)
      assert(e.inputs != Nil) // basically just makes sure we are using a hacked version
      
      // the proper solution is to store everything we need to generate the external call inside DeliteOpExternal, instead of having it be
      // specified in another DSL trait like we do now...
      stream.print("val " + quote(sym) + " = ")
      stream.println("extern(\"" + e.funcName + "\", {")
      emitBlock(e.allocVal)
      stream.println(quote(getBlockResult(e.allocVal)))
      stream.println("},")      
      stream.println("scala.List(" + e.inputs.map(quote).mkString(",") + "))")
      
    case op: AbstractLoop[_] => 
      stream.println("// a *thin* loop follows: " + quote(sym))
      emitFatNode(List(sym), SimpleFatLoop(op.size, op.v, List(op.body)))        
    
    case _ => 
      // Predef.println("calling super.emitNode on: " + rhs.toString)
      super.emitNode(sym, rhs)
  }
  
  override def emitFatNode(symList: List[Sym[Any]], rhs: FatDef) = rhs match {
    case op: AbstractFatLoop => emitRestageableLoop(op, symList)
    case _ => super.emitFatNode(symList, rhs)
  }
  
  def makeBoundVarArgs(args: Exp[Any]*) = "(" + args.map(a => quote(a) + ": Rep[" + remap(a.tp) + "]").mkString(",") + ") => "
  
  def emitBufferElem(op: AbstractFatLoop, elem: DeliteCollectElem[_,_,_]) {
    // append
    stream.println("{")
    stream.println(makeBoundVarArgs(elem.allocVal,elem.eV,op.v))
    emitBlock(elem.buf.append)
    stream.println(quote(getBlockResult(elem.buf.append)))
    stream.println("},")
    // setSize
    stream.println("{")
    stream.println(makeBoundVarArgs(elem.allocVal,elem.sV))
    emitBlock(elem.buf.setSize)
    stream.println(quote(getBlockResult(elem.buf.setSize)))
    stream.println("},")
    // allocRaw
    stream.println("{")
    stream.println(makeBoundVarArgs(elem.allocVal,elem.sV))  
    emitBlock(elem.buf.allocRaw)
    stream.println(quote(getBlockResult(elem.buf.allocRaw)))
    stream.println("},")
    // copyRaw
    stream.println("{")
    stream.println(makeBoundVarArgs(elem.buf.aV,elem.buf.iV,elem.allocVal,elem.buf.iV2,elem.sV))  
    emitBlock(elem.buf.copyRaw)
    stream.println(quote(getBlockResult(elem.buf.copyRaw)))    
    stream.println("}")
  }
  
  
  def emitRestageableLoop(op: AbstractFatLoop, symList: List[Sym[Any]]) {
    // break the multiloops apart, they'll get fused again anyways
    (symList zip op.body) foreach {
      case (sym, elem: DeliteCollectElem[_,_,_]) => 
        stream.println("val " + quote(sym) + " = collect(")
        // loop size
        stream.println(quote(op.size) + ",")
        // alloc func
        stream.println("{")
        stream.println(makeBoundVarArgs(elem.sV))
        emitBlock(elem.allocN)
        stream.println(quote(getBlockResult(elem.allocN)))
        stream.println("},")
        // func
        stream.println("{")
        stream.println(makeBoundVarArgs(elem.eV,op.v))
        emitBlock(elem.func)
        stream.println(quote(getBlockResult(elem.func)))
        stream.println("},")
        // update
        stream.println("{")
        stream.println(makeBoundVarArgs(elem.allocVal,elem.eV,op.v))
        emitBlock(elem.update)
        stream.println(quote(getBlockResult(elem.update)))
        stream.println("},")
        // finalizer
        stream.println("{")
        stream.println(makeBoundVarArgs(elem.allocVal))
        emitBlock(elem.finalizer)
        stream.println(quote(getBlockResult(elem.finalizer)))
        // conditions
        stream.println("},")
        stream.print("scala.List(")
        for (i <- 0 until elem.cond.length) {
          stream.println("{")
          stream.println(makeBoundVarArgs(op.v))
          emitBlock(elem.cond(i))
          stream.println(quote(getBlockResult(elem.cond(i))))
          stream.print("}")
          if (i < elem.cond.length - 1) stream.println(",")
        }
        stream.println("),")
        // par
        stream.println("\"" + elem.par.toString + "\",")
        // buffer
        emitBufferElem(op, elem)
        stream.println(")")

        
      case (sym, elem: DeliteForeachElem[_]) => 
        stream.println("val " + quote(sym) + " = foreach(")
        // loop size
        stream.println(quote(op.size) + ",")
        // alloc func
        stream.println("{")
        stream.println(makeBoundVarArgs(op.v))
        emitBlock(elem.func)
        stream.println(quote(getBlockResult(elem.func)))
        stream.println("})")
        
        
      case (sym, elem: DeliteReduceElem[_]) =>    
        Predef.println("error: tried to restage DeliteReduceElem but no impl yet")
    }
  }
}