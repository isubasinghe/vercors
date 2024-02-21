package vct.rewrite.rasi

import vct.col.ast._
import vct.rewrite.cfg.{CFGEdge, CFGEntry, CFGNode, CFGTerminal}

import scala.collection.mutable

case class AbstractProcess[G](name: String) {
  def get_next(node: CFGEntry[G], state: AbstractState[G]): Set[AbstractState[G]] = node match {
    case CFGTerminal() => Set(state.without_process(this))
    case CFGNode(n, succ) => n match {
      // Assign statements change the state of variables directly (if they appear in the valuation)
      case Assign(target, value) => viable_edges(succ, state).map(e => take_edge(e, state.with_valuation(target, state.resolve_expression(value))))
      case Havoc(loc) => viable_edges(succ, state).map(e => take_edge(e, state.with_valuation(loc, loc.t match {
        case _: IntType[_] => UncertainIntegerValue.uncertain()
        case _: TBool[_] => UncertainBooleanValue.uncertain()
      })))
      // TODO: Consider state changes by specifications
      case Assume(assn) => viable_edges(succ, state).map(e => take_edge(e, state))
      case Inhale(res) => viable_edges(succ, state).map(e => take_edge(e, state))
      case InvokeProcedure(ref, _, _, _, _, _) => viable_edges(succ, state).map(e => take_edge(e, state))
      case InvokeConstructor(ref, _, _, _, _, _, _) => viable_edges(succ, state).map(e => take_edge(e, state))
      case InvokeMethod(_, ref, _, _, _, _, _) => viable_edges(succ, state).map(e => take_edge(e, state))
      // TODO: What do wait and notify do?
      case Wait(obj) => viable_edges(succ, state).map(e => take_edge(e, state))
      case Notify(obj) => viable_edges(succ, state).map(e => take_edge(e, state))
      // Lock and Unlock manipulate the global lock and are potentially blocking      TODO: Differentiate between locks!
      case Lock(_) => state.lock match {
        case Some(proc) => if (!proc.equals(this)) Set(state)
                           else throw new IllegalStateException("Trying to lock already acquired lock")
        case None => viable_edges(succ, state).map(e => take_edge(e, state).locked_by(this))
      }
      case Unlock(_) => state.lock match {
        case Some(proc) => if (proc.equals(this)) viable_edges(succ, state).map(e => take_edge(e, state).unlocked())
                           else throw new IllegalStateException("Trying to unlock lock owned by other process")
        case None => throw new IllegalStateException("Trying to unlock unlocked lock")
      }
      // When forking a new process, make the step of creating it simultaneously to the normal steps    TODO: consider join
      case Fork(obj) =>
        val edges: (Set[CFGEdge[G]], Set[CFGEdge[G]]) = viable_edges(succ, state).partition(e => e.target match {
          case CFGTerminal() => false
          case CFGNode(t, _) => t.equals(obj.t.asClass.get.cls.decl.declarations.collect{ case r: RunMethod[G] => r }.head.body.get)
        })
        edges._2.map(e => take_edge(e, state.with_process_at(AbstractProcess(s"${name}_${obj.toInlineString}"), edges._1.head.target)))
      case Join(obj) => viable_edges(succ, state).map(e => take_edge(e, state))
      // Everything else does not affect the state, so simply go to the next step
      case _ => viable_edges(succ, state).map(e => take_edge(e, state))
    }
  }

  private def viable_edges(edges: mutable.Set[CFGEdge[G]], state: AbstractState[G]): Set[CFGEdge[G]] =
    edges.filter(e => e.condition.isEmpty || state.resolve_boolean_expression(e.condition.get).can_be_true).toSet

  private def take_edge(edge: CFGEdge[G], state: AbstractState[G]): AbstractState[G] =
    state.with_process_at(this, edge.target)
}
