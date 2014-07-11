// -*- tab-width:2 ; indent-tabs-mode:nil -*-
package vct.col.ast;

import java.util.*;

public class FunctionType extends Type {

  private Type args[];
  private Type result;
  public FunctionType(Type args[],Type result){
    this.args=Arrays.copyOf(args,args.length);
    this.result=result;
  }
  public FunctionType(ArrayList<Type> args,Type result){
    this.args=args.toArray(new Type[0]);
    this.result=result;
  }

  public int getArity(){ return args.length; }
  
  public Type getResult(){ return result; }
  
  public Type getArgument(int i){ return args[i]; }
  
  @Override
  public <T> void accept_simple(ASTVisitor<T> visitor){
    visitor.visit(this);
  }
  @Override
  public <T> T accept_simple(ASTMapping<T> map){
    return map.map(this);
  }
  @Override
  public <T> T accept_simple(TypeMapping<T> map){
    return map.map(this);
  }

  @Override
  public boolean supertypeof(ProgramUnit context, Type t) {
    // TODO Auto-generated method stub
    return false;
  }

}
