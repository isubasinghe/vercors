package vct.col.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Hashtable;

import vct.col.ast.ASTNode;
import vct.col.ast.BindingExpression;
import vct.col.ast.BlockStatement;
import vct.col.ast.DeclarationStatement;
import vct.col.ast.LoopStatement;
import vct.col.ast.NameExpression;
import vct.col.ast.RecursiveVisitor;
import vct.col.ast.Type;


public class NameScanner extends RecursiveVisitor<Object> {

  private Hashtable<String,Type> vars;
  
  private HashSet<DeclarationStatement> safe_decls=new HashSet();
  
  public NameScanner(Hashtable<String,Type> vars) {
    super(null, null);
    this.vars=vars;
  }
  
  public void visit(NameExpression e){
    switch(e.getKind()){
      case Reserved: return;
      case Label: return;
      case Field:
      case Local:
      case Argument:
        String name=e.getName();
        Type t=e.getType();
        if (vars.contains(name)){
          if (!t.equals(vars.get(name))) {
            Fail("type mismatch %s != %s",t,vars.get(name));
          }
        } else {
          vars.put(name,t);
        }
        return;
      default:
        Abort("missing case %s %s in name scanner",e.getKind(),e.getName());
    }
  }
  
  public void visit(DeclarationStatement d){
    if (!safe_decls.contains(d)){
      Abort("missing case in free variable detection");
    }
    super.visit(d);
  }

  public static boolean occurCheck(ASTNode invariant, String var_name) {
    Hashtable<String, Type> vars=new Hashtable<String, Type>();
    invariant.accept(new NameScanner(vars));
    return vars.containsKey(var_name);
  }

  public void visit(LoopStatement s){
    ASTNode init=s.getInitBlock();
    if (init instanceof BlockStatement){
      BlockStatement block=(BlockStatement)init;
      if (block.getLength()==1){
        init=block.get(0);
      }
    }
    if (init instanceof DeclarationStatement){
      DeclarationStatement decl=(DeclarationStatement)init;
      Type old=vars.get(decl.name);
      safe_decls.add(decl);
      super.visit(s);
      vars.remove(decl.name);
      if(old!=null){
        vars.put(decl.name,old);
      }
    } else {
      super.visit(s);
    }
  }
  
  public void visit(BindingExpression e){
    if (e.getDeclCount()==1){
      DeclarationStatement decl=e.getDeclaration(0);
      Type old=vars.get(decl.name);
      safe_decls.add(decl);
      super.visit(e);
      vars.remove(decl.name);
      if(old!=null){
        vars.put(decl.name,old);
      }      
    } else {
      Abort("missing case in free variable detection");
    }
  }
}
