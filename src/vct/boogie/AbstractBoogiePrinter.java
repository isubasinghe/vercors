// -*- tab-width:2 ; indent-tabs-mode:nil -*-
package vct.boogie;

import hre.ast.TrackingOutput;
import vct.col.ast.*;
import vct.util.*;
import static hre.System.Fail;


/**
 * This class contains a pretty printer for the common part of Boogie and Chalice.
 * 
 * @author Stefan Blom
 */
public abstract class AbstractBoogiePrinter extends AbstractPrinter {
  
  private boolean boogie;
  
  protected boolean in_clause=false;
  protected ASTNode post_condition=null;
  
  public AbstractBoogiePrinter(Syntax syntax,TrackingOutput out,boolean boogie){
    super(syntax,out);
    this.boogie=boogie;
  }
  public void visit(MethodInvokation e){
    String tag;
    if (e.labels()==1){
      tag=e.getLabel(0).getName();
    } else {
      tag="_";
    }
    boolean statement=!in_expr;
    if(!in_expr) {
      out.printf("call ");
    }
    setExpr();
    DeclarationStatement types[]=e.getDefinition().getArgs();
    ASTNode args[]=e.getArgs();
    String next="";
    for(int i=0;i<args.length;i++){
      if (types[i].isValidFlag(ASTFlags.OUT_ARG)&&types[i].getFlag(ASTFlags.OUT_ARG)) {
        out.printf("%s",next);
        args[i].accept(this);
        next=",";
      }
    }
    for(int i=args.length;i<types.length;i++){
      if (types[i].isValidFlag(ASTFlags.OUT_ARG)&&types[i].getFlag(ASTFlags.OUT_ARG)) {
        out.printf("%s%s_%s",next,tag,types[i].getName());
        next=",";
      }
    }
    if (next.equals(",")) {
      out.printf(" := ");
    }
    if (e.object!=null && !boogie){
      e.object.accept(this);
      out.printf(".");
    }
    e.method.accept(this);
    out.printf("(");
    next="";
    for(int i=0;i<args.length;i++){
      if (types[i].isValidFlag(ASTFlags.OUT_ARG)&&types[i].getFlag(ASTFlags.OUT_ARG)) continue;
      out.printf("%s",next);
      args[i].accept(this);
      next=",";
    }
    for(int i=args.length;i<types.length;i++){
      if (types[i].isValidFlag(ASTFlags.OUT_ARG)&&types[i].getFlag(ASTFlags.OUT_ARG)) continue;
      if (types[i].getInit()==null){
        Fail("Missing argument without default");
      }
      out.printf("%s",next);
      types[i].getInit().accept(this);
      next=",";
        
    }
    out.printf(")");
    if(statement) out.lnprintf(";");
  }
  public void visit(AssignmentStatement s){
    if (in_expr) throw new Error("assignment is a statement in chalice");
    ASTNode expr=s.getExpression();
    nextExpr();
    s.getLocation().accept(this);
    out.printf(" := ");
    nextExpr();
    s.getExpression().accept(this);
    out.lnprintf(";");
  }
 
  public void visit(BlockStatement s){
    out.lnprintf("{");
    out.incrIndent();
    int N=s.getLength();
    for(int i=0;i<N;i++) s.getStatement(i).accept(this);
    out.decrIndent();
    out.lnprintf("}");
  }
  
  public void visit(Contract contract){
    visit(contract,false);
  }
  
  public void visit(Contract contract,boolean function){
    in_clause=true;
    out.incrIndent();
    nextExpr();
    if (contract.pre_condition.getOrigin()==null) {
      throw new Error("pre condition has no origin");
    }
    out.printf("requires ");
    current_precedence=0;
    contract.pre_condition.accept(this);
    out.lnprintf(";");
    if(!function){
      nextExpr();
      if (contract.post_condition.getOrigin()==null) {
        throw new Error("post condition has no origin");
      }
      out.printf("ensures ");
      current_precedence=0;
      contract.post_condition.accept(this);
      out.lnprintf(";");
    }
    if (contract.modifies!=null){
      out.printf("modifies ");
      nextExpr();
      contract.modifies[0].accept(this);
      for(int i=1;i<contract.modifies.length;i++){
        out.printf(", ");
        nextExpr();
        contract.modifies[i].accept(this);
      }
      out.lnprintf(";");
    }
    out.decrIndent();
    in_clause=false;
  }
  
  public void visit(PrimitiveType t){
    switch (t.sort){
    case Long:
    case Integer:
      out.printf("int");
      break;
    case Void:
      out.printf("void");
      break;
    case Boolean:
      out.printf("bool");
      break;
    default:
      Fail("Primitive type %s is not supported, please use an encoding.",t.sort);
    }
  }

  public void visit(DeclarationStatement s){
    out.printf("var %s : ",s.getName());
    nextExpr();
    s.getType().accept(this);
    out.lnprintf(";");
  }
  
  public void visit(IfStatement s){
    int N=s.getCount();
    for(int i=0;i<N;i++){
      ASTNode g=s.getGuard(i);
      ASTNode gs=s.getStatement(i);
      if(i==0) {
        out.printf("if(");
        nextExpr();
        g.accept(this);
        out.lnprintf(")");
      } else if (i==N-1 && g==IfStatement.else_guard) {
        out.lnprintf("else");
      } else {
        out.printf("else if(");
        nextExpr();
        g.accept(this);
        out.lnprintf(")");
      }
      out.incrIndent();
      gs.accept(this);
      out.decrIndent();
    }
  }

  public void visit(LoopStatement s){
    ASTNode init_block=s.getInitBlock();
    ASTNode entry_guard=s.getEntryGuard();
    ASTNode exit_guard=s.getExitGuard();
    ASTNode body=s.getBody();
    if (exit_guard!=null) throw new Error("cannot generate for exit condition yet");
    if (init_block!=null){
      init_block.accept(this);
    }
    if (entry_guard!=null) {
      out.printf("while(");
      nextExpr();
      entry_guard.accept(this);
      out.lnprintf(")");
    } else {
      out.lnprintf("while(true)");
    }
    out.incrIndent();
    for(ASTNode inv:s.getInvariants()){
      in_clause=true;
      out.printf("invariant ");
      nextExpr();
      inv.accept(this);
      out.lnprintf(";");
      in_clause=false;
    }
    out.decrIndent();
    if (body instanceof BlockStatement) {
      body.accept(this);
    } else {
      out.lnprintf("{");
      out.incrIndent();
      body.accept(this);
      out.decrIndent();      
      out.lnprintf("}");
    }
  }
  public void visit(OperatorExpression e){
    String keyword=null;
    switch(e.getOperator()){
      case DirectProof:{
        out.printf("%s",((StringValue)((ConstantExpression)e.getArg(0)).value).getStripped());
        out.lnprintf(";");
        break;
      }
      case Assume:
        if (keyword==null) keyword="assume";
      case Assert:
      {
        if (keyword==null) keyword="assert";
        if (in_expr) Fail("%s is a statement",keyword);
        in_clause=true;
        out.printf("%s ",keyword);
        current_precedence=0;
        setExpr();
        ASTNode prop=e.getArg(0);
        prop.accept(this);
        out.lnprintf(";");
        in_clause=false;
        break;
      }
      case Select:{
        ASTNode a0=e.getArg(0);
        ASTNode a1=e.getArg(1);
        if (a0 instanceof NameExpression && a1 instanceof NameExpression){
          String s0=((NameExpression)a0).toString();
          String s1=((NameExpression)a1).toString();
          if (s0.equals("model")){
            if (s1.equals("old")){
              out.print("old");
              return;
            }
            throw new Error("unknown keyword "+s1);
          }
        }
        // Let's hope this was a this. in case of Boogie!
        // a1.accept(this);
        // break;
      }
      default:{
        super.visit(e);
      }
    }
  }

  public void visit(ReturnStatement s){
    if (s.getExpression()!=null) {
      out.printf("__result := ");
      nextExpr();
      s.getExpression().accept(this);
      out.lnprintf(";");
    }
    if (post_condition!=null){
      out.printf("assert ");
      nextExpr();
      in_clause=true;
      post_condition.accept(this);
      out.lnprintf(";");
      in_clause=false;
    }
    out.lnprintf("assume false; // return;");   
  }
  
}

