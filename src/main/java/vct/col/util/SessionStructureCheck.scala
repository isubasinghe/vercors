package vct.col.util

import hre.lang.System.Fail
import vct.col.ast.`type`.{ClassType, PrimitiveType, Type}
import vct.col.ast.expr.{MethodInvokation, NameExpression, OperatorExpression, StandardOperator}
import vct.col.ast.generic.ASTNode
import vct.col.ast.stmt.composite.{BlockStatement, IfStatement, LoopStatement, ParallelRegion}
import vct.col.ast.stmt.decl.Method.Kind
import vct.col.ast.stmt.decl.{ASTClass, Method, ProgramUnit}
import vct.col.ast.stmt.terminal.AssignmentStatement
import vct.col.util.SessionUtil.{barrierClassName, channelClassName, getNamesFromExpression, mainClassName, runMethodName}

import scala.collection.JavaConversions._

object SessionStructureCheck {

  def check(source : ProgramUnit) : Unit = {
    checkMainClass(source)
    checkMainConstructor(source)
    checkMainMethodsAllowedSyntax(source)
    checkMainMethodsRecursion(source)
    checkRoleFieldsTypes(source)
    checkRoleMethodsTypes(source)
    checkOtherClassesFieldsTypes(source)
    checkOtherClassesMethodsTypes(source)
  }

  def getRoleOrHelperClasses(source : ProgramUnit) : Iterable[ASTClass] = source.get().filter(c => c.name != mainClassName && c.name != channelClassName && c.name != barrierClassName) .map(_.asInstanceOf[ASTClass])

  def getMainClass(source : ProgramUnit) : ASTClass = source.get().find(_.name == mainClassName).get.asInstanceOf[ASTClass]

  private def checkMainClass(source : ProgramUnit) : Unit = {
    source.get().find(_.name == mainClassName) match {
      case None => Fail("Session Fail: class 'Main' is required!")
      case Some(main) =>
        val mcl = main.asInstanceOf[ASTClass]
        val constrs = mcl.methods().filter(_.kind== Kind.Constructor)
        if(constrs.size != 1) {
          Fail("Session Fail: class 'Main' method must have exactly one constructor!")
        } else {
          if (constrs.head.getArity >0){
            Fail("Session Fail: The constructor of class 'Main' cannot have any arguments!")
          } else if(constrs.head.name != mainClassName) {
            Fail("Session Fail: Method without type provided, or constructor with other name than 'Main'")
          }
        }
        mcl.methods().find(_.name == runMethodName) match {
          case None => Fail("Session Fail: The class 'Main' must have a method '%s'!",runMethodName)
          case Some(run) => if(run.getArgs.length != 0) Fail("Session Fail: the method '%s' of class 'Main' cannot have any arguments!",runMethodName)
        }
    }
  }

  private def getMainConstructor(source : ProgramUnit) : Method = getMainClass(source).methods().find(_.kind== Kind.Constructor).get

  def getRoleObjects(source : ProgramUnit) : Array[AssignmentStatement] = {
    getMainClass(source).methods().find(_.kind == Method.Kind.Constructor).get.getBody.asInstanceOf[BlockStatement].getStatements.map(_.asInstanceOf[AssignmentStatement])
  }

  private def getRoleObjectNames(source : ProgramUnit) : Array[String] = getRoleObjects(source).map(_.location.asInstanceOf[NameExpression].name)

  private def checkMainConstructor(source : ProgramUnit) : Unit  = {
    val roles : Array[ASTNode] = getMainConstructor(source).getBody match {
      case b: BlockStatement => b.getStatements
      case _ => Fail("Constructor of 'Main' must have a body of type BlockStatement, i.e. be defined!"); Array()
    }
    if(roles.length == 0)
      Fail("Session Fail: Main constructor is mandatory and  must assign at least one role!")
    roles.foreach {
      case a: AssignmentStatement => a.location match {
        case n: NameExpression => getMainClass(source).fields().map(_.name).find(r => r == n.name) match {
          case None => Fail("Session Fail: can only assign to role fields of class 'Main' in constructor")
          case Some(_) => a.expression match {
            case m: MethodInvokation => getRoleOrHelperClasses(source).find(_.name == m.dispatch.getName) match {
              case None => Fail("Session Fail: Wrong method: constructor of 'Main' must initialize roles with a call to a role constructor")
              case Some(_) => true
            }
            case _ => Fail("Session Fail: No MethodInvokation: constructor of 'Main' must initialize roles with a call to a role constructor")
          }
        }
      }
      case _ => Fail("Session Fail: constructor of 'Main' can only assign role classes")
    }
    if(getRoleObjectNames(source).toSet != getMainClass(source).fields().map(_.name).toSet) {
      Fail("Session Fail: the fields of class 'Main' must be all assigned in constructor 'Main'")
    }
  }

  private def getMainMethods(source : ProgramUnit) : Iterable[Method] = getMainClass(source).methods().filter(m => m.kind != Method.Kind.Constructor && m.kind != Method.Kind.Pure)

  private def checkMainMethodsAllowedSyntax(source : ProgramUnit) : Unit = {
    val roleNames = getRoleObjectNames(source)
    val roleClasses = getRoleClasses(source).map(_.name)
    val mainMethods = getMainMethods(source)
    val mainMethodNames = mainMethods.map(_.name)
    val pureMethods = getPureMainMethods(source)
    getMainMethods(source).foreach(m => checkMainStatement(m.getBody,roleNames, roleClasses, mainMethodNames,pureMethods))
  }

  private def getPureMainMethods(source : ProgramUnit) = getMainClass(source).methods().filter(_.kind == Method.Kind.Pure)

  private def checkMainStatement(s : ASTNode, roleNames : Iterable[String], roleClassNames : Iterable[String], mainMethodNames : Iterable[String], pureMethods : Iterable[Method]) : Unit = {
    s match {
      case b : BlockStatement => b.getStatements.foreach(checkMainStatement(_,roleNames,roleClassNames,mainMethodNames,pureMethods))
      case a: AssignmentStatement =>
        val expNames = getNamesFromExpression(a.expression).map(_.name).toSet.filter(roleNames.contains(_))
        if(expNames.size > 1) {
          Fail("Session Fail: the assignment %s in a method of class 'Main' cannot have multiple roles in its expression.",a.toString)
        }
      case i: IfStatement => {
        if (i.getCount == 1 || i.getCount == 2) {
          if (checkSessionCondition(i.getGuard(0), roleNames)) {
            checkMainStatement(i.getStatement(0), roleNames,roleClassNames, mainMethodNames, pureMethods)
            if (i.getCount == 2) checkMainStatement(i.getStatement(1), roleNames,roleClassNames, mainMethodNames, pureMethods)
          } else Fail("Session Fail: IfStatement needs to have one condition for each role! " + s.getOrigin)
        } else Fail("Session Fail: one or two branches expected in IfStatement! " + s.getOrigin)
      }
      case l: LoopStatement => {
        if (l.getInitBlock == null && l.getUpdateBlock == null)
          if (checkSessionCondition(l.getEntryGuard, roleNames))
            checkMainStatement(l.getBody, roleNames,roleClassNames,mainMethodNames,pureMethods)
          else Fail("Session Fail: a while loop needs to have one condition for each role! " + s.getOrigin)
        else Fail("Session Fail: a for loop is not supported, use a while loop! " + s.getOrigin)
      }
      case p : ParallelRegion => {
        p.blocks.foreach(b => checkMainStatement(b.block,roleNames,roleClassNames,mainMethodNames,pureMethods))
      }
      case m : MethodInvokation =>
        if(m.method == mainClassName)
          Fail("Session Fail: cannot call constructor '%s'!",mainClassName)
        else if(roleClassNames.contains(m.method))
          Fail("Session Fail: cannot call constructor '%s'",m.method)
        else if(!mainMethodNames.contains(m.method)) {
          if(m.`object` == null) {
            Fail("Session Fail: method call not allowed or object of method call '%s' is not given! %s",m.method,m.getOrigin)
          }
          m.`object` match {
            case n : NameExpression =>
              if(!roleNames.contains(n.name))
                Fail("Session Fail: invocation of method %s is not allowed here, because method is either pure, or from a non-role class! %s",m.method, m.getOrigin)
          }
        }
      case _ => Fail("Session Fail: Syntax not allowed; statement is not a session statement! " + s.getOrigin)
    }
  }

  private def checkSessionCondition(node: ASTNode, roleNames : Iterable[String]) : Boolean = {
    val roles = splitOnAnd(node).map(getNamesFromExpression).map(_.map(_.name).toSet)
    roles.forall(_.size == 1) && roleNames.toSet == roles.flatten
  }

  private def splitOnAnd(node : ASTNode) : Set[ASTNode] = {
    node match {
      case e : OperatorExpression => e.operator match {
        case StandardOperator.And => splitOnAnd(e.first) ++ splitOnAnd(e.second)
        case _ => Set(node)
      }
      case _ => Set(node)
    }
  }

  private def getRoleClasses(source : ProgramUnit) : Iterable[ASTClass] = {
    val roleClassNames = getRoleObjects(source).map(_.expression.asInstanceOf[MethodInvokation].dispatch.getName)
    getRoleOrHelperClasses(source).filter(c => roleClassNames.contains(c.name))
  }

  private def checkMainMethodsRecursion(source : ProgramUnit) : Unit = {
    val mainMethods = getMainMethods(source)
    mainMethods.foreach(m => checkGuardedRecursion(m.getBody,Set(m.name),mainMethods))
  }


  private def checkGuardedRecursion(statement : ASTNode, encounteredMethods : Set[String], mainMethods : Iterable[Method]) : Unit =
    statement match {
      case b : BlockStatement => if(b.getLength > 0) checkGuardedRecursion(b.getStatement(0), encounteredMethods,mainMethods)
      case i : MethodInvokation =>
        if(encounteredMethods.contains(i.method))
          Fail("Session Fail: recursive call not allowed as first statement of method '%s'! %s", i.method, statement.getOrigin)
        else mainMethods.find(_.name == i.method) match {
          case Some(m) => checkGuardedRecursion(m.getBody,encounteredMethods + m.name,mainMethods)
          case None => //fine, it is a role method (without any recursion)
        }
      case i : IfStatement => {
        checkGuardedRecursion(i.getStatement(0), encounteredMethods,mainMethods)
        if (i.getCount == 2)
          checkGuardedRecursion(i.getStatement(1), encounteredMethods,mainMethods)
      }
      case l : LoopStatement => checkGuardedRecursion(l.getBody, encounteredMethods,mainMethods)
      case p : ParallelRegion => p.blocks.foreach(b => checkGuardedRecursion(b.block,encounteredMethods,mainMethods))
      case a : AssignmentStatement => checkGuardedRecursion(a.expression,encounteredMethods,mainMethods)
      case e : OperatorExpression => e.args.foreach(checkGuardedRecursion(_,encounteredMethods,mainMethods))
      case _ => //fine
    }

  private def checkRoleMethodsTypes(source : ProgramUnit) : Unit = {
    val roles = getRoleClasses(source)
    val roleClassNames = roles.map(_.name)
    roles.foreach(_.methods().forEach(checkRoleMethodTypes(_,roleClassNames)))
  }

  private def checkRoleMethodTypes(roleMethod : Method, roleClassNames : Iterable[String]) : Unit = {
    if(!isNonRoleOrPrimitive(roleMethod.getReturnType,roleClassNames)) {
      Fail("Session Fail: return type of method %s is a role or other unexpected type",roleMethod.name)
    }
    roleMethod.getArgs.foreach(arg => {
      if(!isNonRoleOrPrimitive(arg.`type`, roleClassNames)) {
        Fail("Session Fail: the type of argument %s of method %s is a role or other unexpected type",arg.name,roleMethod.name)
      }
    })
  }

  private def checkRoleFieldsTypes(source : ProgramUnit) : Unit = {
    val roles = getRoleClasses(source)
    val roleClassNames = roles.map(_.name)
    roles.foreach(role => role.fields().foreach(field => {
     if(!isNonRoleOrPrimitive(field.`type`,roleClassNames))
       Fail("Session Fail: type '%s' of field '%s' of role '%s' is not allowed", field.`type`.toString, field.name, role.name)
    }))
  }

  private def isNonRoleOrPrimitive(t : Type, roleClassNames : Iterable[String]) : Boolean = t match {
    case p : PrimitiveType => p.isBoolean || p.isDouble || p.isInteger || p.isVoid
    case c : ClassType => c.getName != mainClassName && c.getName != barrierClassName && c.getName != channelClassName && !roleClassNames.contains(c.getName)
    case _ => Fail("Session Fail: didn't expect this Type: " + t.toString); false
  }

  private def getOtherClasses(source : ProgramUnit) : Iterable[ASTClass] = {
    val roleClassNames = getRoleClasses(source).map(_.name)
    source.get().filter({
      case c : ASTClass => c.name != mainClassName && !roleClassNames.contains(c.name) && c.name != channelClassName && c.name != barrierClassName
    }).map(_.asInstanceOf[ASTClass])
  }

  private def checkOtherClassesFieldsTypes(source : ProgramUnit) : Unit = {
    val others = getOtherClasses(source)
    val roleClasses = getRoleClasses(source).map(_.name)
    others.foreach(role => role.fields().foreach(field => {
      if(!isNonRoleOrPrimitive(field.`type`,roleClasses))
        Fail("Session Fail: type '%s' of field '%s' of non-role class '%s' is not allowed", field.`type`.toString, field.name, role.name)
    }))
  }

  private def checkOtherClassesMethodsTypes(source: ProgramUnit) : Unit = {
    val others = getOtherClasses(source)
    val roleClasses = getRoleClasses(source).map(_.name)
    others.foreach(_.methods().forEach(checkRoleMethodTypes(_,roleClasses)))
  }

}
