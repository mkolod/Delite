package ppl.dsl.deliszt

import ppl.dsl.deliszt.datastruct.scala._

trait LanguageImplOps {
  this: DeLisztExp =>
  
  def DeLisztInit() : Unit = {
    // Load cfg files
    //
    MeshLoader.init()
  }
  
  def print_impl(as : Seq[Exp[Any]]) {
    for(a <- as) {
      print(a)
    }
    
    println()
  }
}

trait LanguageImplOpsStandard extends LanguageImplOps {
  this: DeLisztCompiler with DeLisztExp =>
}