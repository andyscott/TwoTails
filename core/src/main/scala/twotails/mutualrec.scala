package twotails

import scala.annotation.{StaticAnnotation, compileTimeOnly, switch, tailrec}
import scala.tools.nsc.{Global, Phase}
import scala.tools.nsc.plugins.{Plugin, PluginComponent}
import scala.tools.nsc.symtab.Flags._
import scala.tools.nsc.transform.{Transform, TypingTransformers}
import collection.mutable.{Map => MMap}
import collection.breakOut

@compileTimeOnly("Somehow this didn't get processed as part of the compilation.")
final class mutualrec extends StaticAnnotation

class TwoTailsPlugin(val global: Global) extends Plugin{
  val name = "twotails"
  val description = "Adds support for mutually recursive functions in Scala."
  val components = List[PluginComponent](new MutualRecComponent(this, global))
}

//TODO: must require that them mutualrec methods are "final"
class MutualRecComponent(val plugin: Plugin, val global: Global) 
    extends PluginComponent with Transform with TypingTransformers {//with TreeDSL{
  import global._

  val phaseName = "twotails"
  override val runsBefore = List("tailcalls", "patmat") //must occur before the tailcalls phase and pattern matcher
  val runsAfter = List("typer")
  override val requires = List("typer")

  def newTransformer(unit: CompilationUnit) = new MutualRecTransformer(unit)

  final class MutualRecTransformer(unit: CompilationUnit) extends TypingTransformer(unit){

    //Thanks @retronym
    override def transformStats(stats: List[Tree], exprOwner: Symbol): List[Tree] = {
      val flattened = stats.flatMap {
        case Block(st, EmptyTree) => st
        case x => x :: Nil
      }
      super.transformStats(flattened, exprOwner)
    }

    override def transform(tree: Tree): Tree = super.transform{
      tree match{
        case cd @ ClassDef(mods, name, tparams, body) =>
          val trans = transformBody(tree, body)
          if(trans != body) treeCopy.ClassDef(cd, mods, name, tparams, trans) else cd
        case md @ ModuleDef(mods, name, body) =>
          val trans = transformBody(tree, body)
          if(trans != body) treeCopy.ModuleDef(md, mods, name, trans) else md
        case bl @ Block(stats, expr) if expr != EmptyTree =>
          val trans = transformNested(tree, stats)
          if(stats != trans) treeCopy.Block(bl, trans, expr) else bl
        case _ => tree
      }
    }

    def transformBody(root: Tree, template: Template): Template ={
      val Template(parents, valDef, body) = template

      val cnt = body.count{
        case ddef: DefDef => hasMutualRec(ddef)
        case _ => false
      }

      (cnt: @switch) match{
        case 0 => template
        case 1 => convertTailRec(body); template
        case _ => treeCopy.Template(template, parents, valDef, convertMutualRec(root, body))
      }
    }

    def transformNested(root: Tree, stats: List[Tree]): List[Tree] ={
      val cnt = stats.count{
        case ddef: DefDef => hasMutualRec(ddef)
        case _ => false
      }

      (cnt: @switch) match{
        case 0 => stats
        case 1 => convertTailRec(stats); stats
        case _ => convertMutualRec(root, stats)
      }
    }

    private final val mtrec: Symbol = rootMirror.getRequiredClass("twotails.mutualrec")
    private final val trec = AnnotationInfo(definitions.TailrecClass.tpe, Nil, Nil)

    def hasMutualRec(tree: Tree) = 
      (tree.symbol != NoSymbol) &&
      tree.symbol.annotations.exists(_.tpe.typeSymbol == mtrec)

    //In case there's just one, convert to a @tailrec. No reason to add a whole new method.
    def convertTailRec(body: List[Tree]): Unit = body.foreach{
      case ddef: DefDef if hasMutualRec(ddef) => 
        //if(!ddef.symbol.isEffectivelyFinalOrNotOverridden) unit.
        ddef.symbol
          .removeAnnotation(mtrec)
          .setAnnotations(trec :: Nil)
      case _ => 
    }

    def convertMutualRec(root: Tree, body: List[Tree]): List[Tree] ={
      val (head :: tail, everythingElse) = body.partition{
        case ddef: DefDef => hasMutualRec(ddef)
        case _ => false
      }

      //Right here making the assumption that every method that's mutualrec has both the same named arguments
      //with potentially the same default arguments. Will need to handle that in the future as a TODO.
      val methSym = mkNewMethodSymbol(head.symbol)
      val rhs = mkNewMethodRhs(methSym, head :: tail)
      val methTree = mkNewMethodTree(methSym, root, rhs)
      val forwardedTrees = forwardTrees(methSym, head :: tail)

      Block(everythingElse ::: (methTree :: forwardedTrees), EmptyTree) :: Nil
    }

    def mkNewMethodSymbol(symbol: Symbol, 
                          name: TermName = TermName("mutualrec_fn"), 
                          flags: FlagSet = METHOD | FINAL | PRIVATE | ARTIFACT): Symbol ={
      val methSym = symbol.cloneSymbol(symbol.owner, flags, name)
      val param = methSym.newSyntheticValueParam(definitions.IntTpe, TermName("indx"))
      
      methSym.modifyInfo {
        case GenPolyType(tparams, MethodType(params, res)) => GenPolyType(tparams, MethodType(param :: params, res))
      }
      methSym
        .removeAnnotation(mtrec)
        .setAnnotations(trec :: Nil)
      localTyper.namer.enterInScope(methSym)
    }

    def mkNewMethodRhs(methSym: Symbol, defdef: List[Tree]): Tree ={
      val substDef = defdef.zipWithIndex.map{
        case (d, i) => SubstDefDef(d, localTyper.typed(Literal(Constant(i))))
      }
      val callTransformer = new MutualCallTransformer(methSym, substDef.map{ x => (x.symbol, x) }(breakOut))
      val defrhs = substDef.map{ subst =>
        val (oldSkolems, deskolemized) = subst.skolems(methSym)
        val old = oldSkolems ::: subst.typeParams ::: subst.vparamSymbols
        val neww = deskolemized ::: methSym.typeParams ::: methSym.info.paramss.flatten.drop(1)

        callTransformer.transform(subst.rhs)
          .changeOwner(subst.symbol -> methSym)
          .substituteSymbols(old, neww)
      }

      val cases = defrhs.zipWithIndex.map{
        case (body, i) => cq"$i => $body"
      }

      super.transform(q"(indx: @scala.annotation.switch) match{ case ..$cases }")
    }

    def mkNewMethodTree(methSym: Symbol, tree: Tree, rhs: Tree): Tree = if(tree.symbol != null){
      localTyper.typedPos(tree.symbol.pos)(DefDef(methSym, rhs))
    }
    else{
      localTyper.typed(DefDef(methSym, rhs))
    }

    def forwardTrees(methSym: Symbol, defdef: List[Tree]): List[Tree] = defdef.zipWithIndex.map{
  	  case (tree, indx) =>
        val DefDef(_, _, _, vp :: vps, _, _) = tree
        val newVp = localTyper.typed(Literal(Constant(indx))) :: vp.map(p => gen.paramToArg(p.symbol))
        val refTree = gen.mkAttributedRef(tree.symbol.owner.thisType, methSym)
        val forwarderTree = (Apply(refTree, newVp) /: vps){
          (fn, params) => Apply(fn, params map (p => gen.paramToArg(p.symbol)))
        }
        val forwarded = deriveDefDef(tree)(_ => localTyper.typedPos(tree.symbol.pos)(forwarderTree))
        forwarded.symbol.removeAnnotation(mtrec)
        forwarded
    }
  }

  class MutualCallTransformer(methSym: Symbol, subst: Map[Symbol, SubstDefDef]) extends Transformer{
    //TODO: FIgure out type parameters
    override def transform(tree: Tree): Tree = tree match{
      case Apply(fn, args) if subst.contains(fn.symbol) => multiArgs(tree) 
      case _ => super.transform(tree)
    }

    def multiArgs(tree: Tree, lvl: Int=0):Tree = tree match{
      case Apply(fn, args) => 
        val newArgs = args //TODO: figure out default and then named default values.
        multiArgs(fn, lvl+1) match{
          case f @ Apply(_, _) => treeCopy.Apply(tree, f, transformTrees(args))
          case f => treeCopy.Apply(tree, f, subst(fn.symbol).indx :: transformTrees(args))
        }
      case TypeApply(fn, targs) => treeCopy.TypeApply(tree, multiArgs(fn), targs)
      case _ => gen.mkAttributedRef(methSym)
    }
  }

  case class SubstDefDef(tree: Tree, indx: Tree){
    def symbol = tree.symbol
    def typeParams = tree.symbol.typeParams
    def vparamSymbols = vparams.flatMap(_.map(_.symbol))
    val DefDef(_, _, _, vparams, _, rhs) = tree

    //shamelessly taken from @retronym's example #20 of the Scalac survival guide.
    def skolems(methSym: Symbol) ={
      val origTparams = tree.symbol.info.typeParams
      if(origTparams.isEmpty) (Nil, Nil) else{
        val skolemSubst = MMap.empty[Symbol, Symbol]
        rhs.foreach{
          _.tpe.foreach {
            case tp if tp.typeSymbolDirect.isSkolem =>
              val tparam = tp.typeSymbolDirect.deSkolemize
              if (!skolemSubst.contains(tparam) && origTparams.contains(tparam)) {
                skolemSubst(tp.typeSymbolDirect) = methSym.typeParams(origTparams.indexOf(tparam))
              }
            case _ =>
          }
        }
        skolemSubst.toList.unzip
      }
    }

    val (defaultArgs, namedDefaultArgs) ={
      val namedArgs = Map.newBuilder[TermName, Tree]
      val args = vparams.map{
        _.collect{
          case ValDef(_, name, _, rhs) => 
            namedArgs += (name -> rhs)
            rhs
        }
      }
      (args, namedArgs result ())
    }
  }
}

/*
remote debugging is as simple as

sbt test:compile -jvm-debug 5005
*/