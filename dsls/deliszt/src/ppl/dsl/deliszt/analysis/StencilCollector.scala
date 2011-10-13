package ppl.dsl.deliszt.analysis

import java.io.{PrintWriter}

import scala.collection.immutable.Set
import scala.collection.mutable.{Set => MSet, Map}

import scala.virtualization.lms.common._
import scala.virtualization.lms.internal._

import ppl.delite.framework.DeliteApplication
import ppl.delite.framework.analysis.TraversalAnalysis

import ppl.dsl.deliszt.datastruct.scala._
import ppl.dsl.deliszt._

import ppl.delite.framework.datastruct.scala._

object MeshObjVal {
  def apply[MO<:MeshObj](mo: MO) = new MeshObjValImpl(Set(mo))
  def apply[MO<:MeshObj](ms: MeshSet[MO]) = new MeshObjValImpl(ms.toSet)
}

trait MeshObjVal {
  def +(b: MeshObj) : MeshObjVal = new MeshObjValImpl(objs + b)
  def ++(b: MeshObjVal) : MeshObjVal = new MeshObjValImpl(objs ++ b.objs)
  val objs : Set[MeshObj]
}

class MeshObjValImpl(val objs : Set[MeshObj] = Set[MeshObj]()) extends MeshObjVal {
}

case class NoObjs() extends MeshObjVal {
  val objs = Set[MeshObj]()
  
  override def ++(b: MeshObjVal) = b
}

case class MeshObjSetVal[MO<:MeshObj](val ms : MeshSet[MO]) {
  val objs = ms.toSet
} 

case class FieldAccess(field: Int, mo: MeshObj)

class ReadWriteSet {
  val read = MSet[FieldAccess]()
  val write = MSet[FieldAccess]()
}

trait DeLisztCodeGenAnalysis extends TraversalAnalysis {
  val IR: DeliteApplication with DeLisztExp
  import IR._

  type StencilMap = Map[MeshObj,ReadWriteSet]
  val forMap = Map[Int,StencilMap]()
  
  // Store the current top level for loop
  var currentFor : Option[Int] = None
  
  // And the current mesh object in the top level for loop
  var currentMo : Option[MeshObj] = None
  
  val className = "DeLisztAnalysis"
  
  override def init(app: DeliteApplication, args: Array[String]) {
    super.init(app, args)
  
    _result = Some(forMap)
  
    MeshLoader.init(if(args.length > 0) args(0) else "liszt.cfg")
  }
  
  // Mark accesses
  def markRead[MO<:MeshObj,VT](f: Exp[Field[MO,VT]], i: Exp[MeshObj]) {
    val sym = f.asInstanceOf[Sym[Field[MO,VT]]]
    val mo = value[MeshObj](i)
    
    forMap(currentFor.get)(currentMo.get).read += FieldAccess(sym.id, mo)
  }
  
  def markWrite[MO<:MeshObj,VT](f: Exp[Field[MO,VT]], i: Exp[MeshObj]) {
    val sym = f.asInstanceOf[Sym[Field[MO,VT]]]
    val mo = value[MeshObj](i)
    
    forMap(currentFor.get)(currentMo.get).write += FieldAccess(sym.id, mo)
  }
  
  def matchFor(i: Int) = {
    currentFor match {
      case Some(i) => true
      case _ => false
    }
  }
  
  def setFor(i: Int) {
    if(currentFor.isEmpty) {
      currentFor = Some(i)
      forMap(i) = Map[MeshObj,ReadWriteSet]()
    }
  }
  
  val values = Map[Int,Any]()
  
  def store(sym: Sym[_], x: Any) {
    values(sym.id) = x
  }
    
  def combine(a: Any, b: Any) = {
    (a, b) match {
      case (x: MeshObjVal, y: MeshObjVal) => x ++ y
      case (x: MeshObjVal, _) => x
      case (_, y: MeshObjVal) => y
      
      // Just pick one, doesn't really matter
      case _ => a
    }
  }
  
  def combine(a: Exp[Any], b: Exp[Any]) : Any = {
    val resultA = value(a)
    val resultB = value(b)
    
    // Combine the two! Only care really if it is a mesh element though
    combine(resultA,resultB)
  }
  
  def rawValue(x: Exp[Any]) : Option[Any] = x match {
    case Const(null) => None
    case Const(s: String) => Some(s)
    case null => None
    case Const(f: Float) => Some(f)
    case Const(z) => Some(z)
    case Sym(n) => { System.out.println("SYM " + n); val temp = values.get(n); System.out.println(temp); temp }
    // Doesn't exist anymore?
    // case External(s, args) => null
    case _ => { throw new RuntimeException("Could not get value"); None }
  }
  
  def value[T:Manifest](x: Exp[Any]) : T = {System.out.println("Fetching value for")
    System.out.println(x)
    (rawValue(x) match {
    case Some(o) => o
    case None => null
    case _ => null
  }).asInstanceOf[T]}
  
  def value[T:Manifest](x: Def[Any]) : T = (maybeValue(x) match {
    case Some(o) => o
    case None => null
    case _ => null
  }).asInstanceOf[T]
  
  def maybeValue(x: Def[Any])(implicit stream: PrintWriter) : Option[Any] = {
    val o = x match {
      // or could also lookup the exp
      case b@DeLisztBoundarySet(name) => Mesh.boundarySet(value[String](name))(b.moc)

      case DeLisztMesh() => Mesh.mesh

      case DeLisztVerticesCell(e) => Mesh.vertices(value[Cell](e))
      case DeLisztVerticesEdge(e) => Mesh.vertices(value[Edge](e))
      case DeLisztVerticesFace(e) => Mesh.vertices(value[Face](e))
      case DeLisztVerticesVertex(e) => Mesh.vertices(value[Vertex](e))
      case DeLisztVerticesMesh(e) => Mesh.vertices(value[Mesh](e))

      case DeLisztVertex(e, i) => Mesh.vertex(value(e), value(i))

      case DeLisztFaceVerticesCCW(e) => Mesh.verticesCCW(value(e))
      case DeLisztFaceVerticesCW(e) => Mesh.verticesCW(value(e))

      case DeLisztCellsCell(e) => Mesh.cells(value[Cell](e))
      case DeLisztCellsEdge(e) => Mesh.cells(value[Edge](e))
      case DeLisztCellsFace(e) => Mesh.cells(value[Face](e))
      case DeLisztCellsVertex(e) => Mesh.cells(value[Vertex](e))
      case DeLisztCellsMesh(e) => Mesh.cells(value[Mesh](e))

      case DeLisztEdgeCellsCCW(e) => Mesh.cellsCCW(value(e))
      case DeLisztEdgeCellsCW(e) => Mesh.cellsCW(value(e))

      case DeLisztEdgesCell(e) => Mesh.edges(value[Cell](e))
      case DeLisztEdgesFace(e) => Mesh.edges(value[Face](e))
      case DeLisztEdgesVertex(e) => Mesh.edges(value[Vertex](e))
      case DeLisztEdgesMesh(e) => Mesh.edges(value[Mesh](e))

      case DeLisztFacesCell(e) => Mesh.faces(value[Cell](e))
      case DeLisztFacesEdge(e) => Mesh.faces(value[Edge](e))
      case DeLisztFacesVertex(e) => Mesh.faces(value[Vertex](e))
      case DeLisztFacesMesh(e) => Mesh.faces(value[Mesh](e))

      case DeLisztEdgeFacesCCW(e) => Mesh.facesCCW(value(e))
      case DeLisztEdgeFacesCW(e) => Mesh.facesCW(value(e))
  
      case DeLisztFaceEdgesCCW(e) => Mesh.edgesCCW(value(e))
      case DeLisztFaceEdgesCW(e) => Mesh.edgesCW(value(e))

      case DeLisztEdgeHead(e) => Mesh.head(value(e))
      case DeLisztEdgeTail(e) => Mesh.tail(value(e))

      case DeLisztFaceInside(e) => Mesh.inside(value(e))
      case DeLisztFaceOutside(e) => Mesh.outside(value(e))
  
      case DeLisztFace(e, i) => Mesh.face(value(e), value(i))

      case DeLisztFlipEdge(e) => Mesh.flip(value[Edge](e))
      case DeLisztFlipFace(e) => Mesh.flip(value[Face](e))

      case DeLisztTowardsEdgeVertex(e, v) => Mesh.towards(value[Edge](e), value[Vertex](v))
      case DeLisztTowardsFaceCell(e, c) => Mesh.towards(value[Face](e), value[Cell](c))
      
      case DeliteCollectionApply(e, i) => {
        System.out.println("DC APPLY?")
        (rawValue(e), rawValue(i)) match {
          case (Some(c), Some(idx)) => (c.asInstanceOf[DeliteCollection[Any]]).dcApply(idx.asInstanceOf[Int])
          case _ => None
        }
      }
      
      // While loops. Execute once. Store results if results are a mesh element
      case While(c,b) => {
        emitBlock(c)
        
        emitBlock(b)
        blockValue(b)
      }
      
      // Execute both branches. Store results if results are a mesh element
      case IfThenElse(c,a,b) => {
        emitBlock(c)
        
        emitBlock(a)
        emitBlock(b)
        
        val resultA = blockValue(a)
        val resultB = blockValue(b)
        
        // Combine the two! Only care really if it is a mesh element though
        combine(resultA,resultB)
      }

      case _ => None
    }
    
    o match {
      case None => None
      case _ => Some(o)
    }
  }
  
  def blockValue(b: Exp[Any]) = value(getBlockResult(b))
  
  override def emitNode(sym: Sym[Any], rhs: Def[Any])(implicit stream: PrintWriter) = {
    maybeValue(rhs) match {
      case Some(o) => {
        System.out.println("STORING SYM " + sym.id)
        System.out.println(o)
        store(sym, o)
      }
      case None => { System.out.println("FAILTRAIN SYM " + sym.id); System.out.println(rhs) }
    }
  
    rhs match {
      // Foreach, only apply to top level foreach though...
      case f@MeshSetForeach(m, b) => {
        // if not current top, mark current top....
        if(currentFor.isEmpty)
          currentFor = Some(sym.id)
        
        // Get value for the mesh set
        val ms = value[MeshSet[_]](m)
        
        System.out.println("RUNNING FOREACH")
        
        val mssize:Int = ms.size
        val stuff:Int = 0
        
        var i: Int = 0
        // Run foreach over mesh set
        for(mo <- ms) {
          // If top level foreach, mark the current element as one we are collecting stencil for
          if(matchFor(sym.id)) {
            currentMo = Some(mo)
          }
        
          System.out.println("STORING SYM " + f.v.id)
          System.out.println(mo)
          
          System.out.println("STORING SYM INDEX " + f.i.id)
          System.out.println(i)
          // Store mesh object in loop index
          store(f.i, i)
          store(f.v, i)
          
          // Re "emit" block
          f.body match {
            case DeliteForeachElem(func, sync) => emitBlock(func)
          }
          
          i += 1
        }
      }
        
      // Mark 
      case FieldApply(f,i) => {
        // Mark a read on the field for the current element... for i
      }
        
      case FieldPlusUpdate(f,i,v) => {
        // Mark a write on the field for the current element... for i
      }
      
      case FieldTimesUpdate(f,i,v) => {
        // Mark a write on the field for the current element... for i
      }
        
      case FieldMinusUpdate(f,i,v) => {
        // Mark a write on the field for the current element... for i
      }
      
      case FieldDivideUpdate(f,i,v) => {
        // Mark a write on the field for the current element... for i
      }
      
      case _ => None
    }
  }
}