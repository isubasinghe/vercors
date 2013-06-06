package vct.col.rewrite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Set;

import vct.col.ast.ASTClass;
import vct.col.ast.ASTNode;
import vct.col.ast.ASTVisitor;
import vct.col.ast.BlockStatement;
import vct.col.ast.Contract;
import vct.col.ast.ContractBuilder;
import vct.col.ast.DeclarationStatement;
import vct.col.ast.Dereference;
import vct.col.ast.LoopStatement;
import vct.col.ast.Method;
import vct.col.ast.NameExpression;
import vct.col.ast.OperatorExpression;
import vct.col.ast.ParallelBarrier;
import vct.col.ast.ParallelBlock;
import vct.col.ast.PrimitiveType.Sort;
import vct.col.ast.ProgramUnit;
import vct.col.ast.StandardOperator;
import vct.col.ast.Type;
import vct.col.util.ASTUtils;
import vct.col.util.ControlFlowAnalyzer;
import vct.util.Configuration;

public class KernelRewriter extends AbstractRewriter {

  /** Holds the number of the current barrier during
   *  the rewrite of a kernel.
   *
   */
  private int barrier_no;
  
  /**
   * Used for for building the contract of the barrier function
   * called form the thread verification function. 
   */
  private ContractBuilder barrier_cb;
  
  /**
   * Mapping from barrier statements to their numbers.
   */
  private HashMap<ParallelBarrier,Integer> barrier_map;
  
  /**
   * Map every barrier number to the list of post-resources of the barrier.
   * The program starts at the implicit barrier number 0, which is the
   * entry point of the thread.
   */
  private Hashtable<Integer,ArrayList<ASTNode>> resources;
  
  /**
   * Map every barrier to the functional pre-conditions. 
   */
  private Hashtable<Integer,ArrayList<ASTNode>> barrier_pre;
  /**
   * Map every barrier to the functional post-conditions. 
   */
  private Hashtable<Integer,ArrayList<ASTNode>> barrier_post;

  
  public KernelRewriter(ProgramUnit source) {
    super(source);
  }

  /**
   * Put all preceeding barriers of node into barriers.
   * @param barriers Set to
   * @param node The node for which to find the set of preceeding barriers.
   * 
   */
  private void find_predecessors(Set<Integer> barriers, ASTNode node) {
    for(ASTNode pred : node.getPredecessors()){
      if (pred instanceof Method){
        barriers.add(0);
      } else if (pred instanceof ParallelBarrier) {
        barriers.add(barrier_map.get(pred));
      } else {
        find_predecessors(barriers,pred);
      }
    }
  }

  private ASTNode create_barrier_call(int no) {
    ArrayList<ASTNode> args=new ArrayList<ASTNode>();
    args.add(create.local_name("tcount"));
    args.add(create.local_name("gsize"));
    args.add(create.local_name("tid"));
    args.add(create.local_name("gid"));
    args.add(create.local_name("lid"));
    args.add(create.constant(no));
    args.add(create.local_name("__last_barrier"));
    for (ASTNode a:private_args){
      args.add(copy_rw.rewrite(a));
    }
    return create.assignment(create.local_name("__last_barrier"),
        create.invokation(null,null,base_name+"_barrier",args.toArray(new ASTNode[0])));
  }
  
  private void add(Hashtable<Integer,ArrayList<ASTNode>>map,int no,ASTNode res){
    ArrayList<ASTNode> list=map.get(no);
    if (list==null) {
      list=new ArrayList<ASTNode>();
      map.put(no,list);
    }
    list.add(res);
  }
  
  private ASTNode create_resources(NameExpression name) {
    ASTNode res=create.constant(true);
    for (Integer i:resources.keySet()){
      ASTNode tmp=create.expression(StandardOperator.Implies,
          create.expression(StandardOperator.EQ,name,create.constant(i.intValue())),
          create.fold(StandardOperator.Star,resources.get(i))
      );
      res=create.expression(StandardOperator.Star,res,tmp);
    }
    return res;
  }

  public void visit(ParallelBarrier pb){
    barrier_no++;
    barrier_map.put(pb,barrier_no);
    for(ASTNode clause:ASTUtils.conjuncts(pb.contract.pre_condition)){
      add(barrier_pre,barrier_no,clause);
      barrier_cb.requires(create(clause).expression(StandardOperator.Implies,
          create.expression(StandardOperator.EQ,create.local_name("this_barrier"),create.constant(barrier_no)),
          rewrite(clause)
      ));
    }
    for(ASTNode clause:ASTUtils.conjuncts(pb.contract.post_condition)){
      if (clause.getType().isPrimitive(Sort.Resource)){
        add(resources,barrier_no,clause);
      } else {
        add(barrier_post,barrier_no,clause);
        barrier_cb.ensures(create(clause).expression(StandardOperator.Implies,
            create.expression(StandardOperator.EQ,create.local_name("this_barrier"),create.constant(barrier_no)),
            rewrite(clause)
        ));
      }
    }
    result=create_barrier_call(barrier_no);
  }
  
  private ASTVisitor cfa=new ControlFlowAnalyzer(source());
  
  private String base_name;

  private ArrayList<DeclarationStatement> private_decls;

  private ArrayList<ASTNode> private_args;

  private ArrayList<ASTNode> kernel_main_invariant;
  @Override
  public void visit(ASTClass cl){
    if (cl.kind==ASTClass.ClassKind.Kernel){
      cl.accept(cfa);
      resources=new Hashtable<Integer,ArrayList<ASTNode>>();
      barrier_pre=new Hashtable<Integer,ArrayList<ASTNode>>();
      barrier_post=new Hashtable<Integer,ArrayList<ASTNode>>();
      HashSet<String> locals=new HashSet<String>();
      HashSet<String> globals=new HashSet<String>();
      kernel_main_invariant = new ArrayList<ASTNode>();
      ArrayList<ASTNode> base_inv=new ArrayList<ASTNode>();
      ArrayList<ASTNode> constraint=new ArrayList<ASTNode>();
      kernel_main_invariant.add(create.expression(StandardOperator.LTE,create.constant(0),create.local_name("tid")));
      kernel_main_invariant.add(create.expression(StandardOperator.LT,create.local_name("tid"),create.local_name("tcount")));
      if (Configuration.assume_single_group.get()){
        kernel_main_invariant.add(create.expression(StandardOperator.EQ,create.local_name("tid"),create.local_name("lid")));
        kernel_main_invariant.add(create.expression(StandardOperator.EQ,create.local_name("tcount"),create.local_name("gsize")));
        kernel_main_invariant.add(create.expression(StandardOperator.EQ,create.local_name("gid"),create.constant(0)));
        base_inv.add(create.expression(StandardOperator.EQ,create.local_name("tcount"),create.local_name("gsize")));
        base_inv.add(create.expression(StandardOperator.EQ,create.local_name("gid"),create.constant(0)));        
      } else {
        kernel_main_invariant.add(create.expression(StandardOperator.LTE,create.constant(0),create.local_name("lid")));
        kernel_main_invariant.add(create.expression(StandardOperator.LT,create.local_name("lid"),create.local_name("gsize")));
        kernel_main_invariant.add(create.expression(StandardOperator.LTE,create.constant(0),create.local_name("gid")));
        kernel_main_invariant.add(create.expression(StandardOperator.EQ,create.local_name("tid"),
            create.expression(StandardOperator.Plus,create.local_name("lid"),
                create.expression(StandardOperator.Mult,create.local_name("gid"),create.local_name("gsize"))
            )
        ));
        base_inv.add(create.expression(StandardOperator.LTE,create.constant(0),create.local_name("tcount")));
        base_inv.add(create.expression(StandardOperator.LTE,create.constant(0),create.local_name("gsize")));
        base_inv.add(create.expression(StandardOperator.GT,create.local_name("tcount"),
            create.expression(StandardOperator.Mult,create.local_name("gid"),create.local_name("gsize"))));
      }
      ASTClass res=create.ast_class(cl.getName(), ASTClass.ClassKind.Plain, null, null);
      for (DeclarationStatement global:cl.staticFields()){
        globals.add(global.getName());
        kernel_main_invariant.add(create.expression(StandardOperator.Value,create.field_name(global.getName())));
        base_inv.add(create.expression(StandardOperator.Value,create.field_name(global.getName())));
        Type t=global.getType();
        if (t.isPrimitive(Sort.Array)){
          ASTNode tmp=create.expression(StandardOperator.EQ,
              create.dereference(create.field_name(global.getName()),"length"),
              rewrite(t.getArg(1))
          );
          kernel_main_invariant.add(tmp);
          base_inv.add(tmp);
          t=create.primitive_type(Sort.Array,rewrite(t.getArg(0)));          
        } else {
          t=rewrite(t);
        }
        constraint.add(create.expression(StandardOperator.EQ,create.field_name(global.getName()),
            create.expression(StandardOperator.Old,create.field_name(global.getName()))));
        res.add_dynamic(create.field_decl(global.getName(), t));
      }
      for (DeclarationStatement local:cl.dynamicFields()){
        locals.add(local.getName());
        kernel_main_invariant.add(create.expression(StandardOperator.Value,create.field_name(local.getName())));
        base_inv.add(create.expression(StandardOperator.Value,create.field_name(local.getName())));
        Type t=local.getType();
        if (t.isPrimitive(Sort.Array)){
          ASTNode tmp=create.expression(StandardOperator.EQ,
              create.dereference(create.field_name(local.getName()),"length"),
              rewrite(t.getArg(1))
          );
          kernel_main_invariant.add(tmp);
          base_inv.add(tmp);
          t=create.primitive_type(Sort.Array,rewrite(t.getArg(0)));          
        } else {
          t=rewrite(t);
        }
        constraint.add(create.expression(StandardOperator.EQ,create.field_name(local.getName()),
            create.expression(StandardOperator.Old,create.field_name(local.getName()))));
        res.add_dynamic(create.field_decl(local.getName(), t));
      }
      for (Method m:cl.dynamicMethods()){
        if (m.getKind()==Method.Kind.Pure){
          res.add_dynamic(copy_rw.rewrite(m));
          continue;
        }
        ArrayList<ASTNode> group_inv=new ArrayList<ASTNode>(base_inv);
        if (m.getArity()!=0) Fail("TODO: kernel argument support");
        Type returns=create(m).primitive_type(Sort.Void);
        Contract contract=m.getContract();
        if (contract==null) Fail("kernel without contract");
        base_name=m.getName();
        barrier_no=0;
        barrier_map=new HashMap<ParallelBarrier, Integer>();
        barrier_cb=new ContractBuilder();
        ContractBuilder thread_cb=new ContractBuilder();
        thread_cb.requires(create.fold(StandardOperator.Star, kernel_main_invariant));
        thread_cb.ensures(create.fold(StandardOperator.Star, kernel_main_invariant));
        thread_cb.ensures(create.fold(StandardOperator.Star, constraint));
        for(ASTNode clause:ASTUtils.conjuncts(contract.pre_condition)){
          thread_cb.requires(clause);
          if(clause.getType().isPrimitive(Sort.Resource)){
            add(resources,0,clause);
          } else if (!ASTUtils.find_name(clause,"tid")
                  && !ASTUtils.find_name(clause,"lid")) {
            group_inv.add(clause);
          }
        }
        for(ASTNode clause:ASTUtils.conjuncts(contract.post_condition)){
          thread_cb.ensures(clause);
        }        
        DeclarationStatement args[]=new DeclarationStatement[]{
            create.field_decl("tcount", create.primitive_type(Sort.Integer)),
            create.field_decl("gsize", create.primitive_type(Sort.Integer)),
            create.field_decl("tid", create.primitive_type(Sort.Integer)),
            create.field_decl("gid", create.primitive_type(Sort.Integer)),
            create.field_decl("lid", create.primitive_type(Sort.Integer))            
        };
        private_decls = new ArrayList();
        private_args = new ArrayList();
        BlockStatement orig_block=(BlockStatement)m.getBody();
        BlockStatement block=create.block();
        int K=orig_block.getLength();
        block.add_statement(create.field_decl("__last_barrier",create.primitive_type(Sort.Integer),create.constant(0)));
        for(int i=0;i<K;i++){
          ASTNode s=orig_block.getStatement(i);
          if (s instanceof DeclarationStatement){
            DeclarationStatement d=(DeclarationStatement)s;
            private_decls.add(d);
            private_args.add(create.local_name(d.getName()));
          }
        }
        for(int i=0;i<K;i++){
          ASTNode s=orig_block.getStatement(i);
          block.add_statement(rewrite(s));
        }
        res.add_dynamic(create(m).method_decl(returns,thread_cb.getContract(),base_name+"_main", args, block)); 
        barrier_cb.requires(create_resources(create.local_name("last_barrier")),false);
        barrier_cb.requires(create.fold(StandardOperator.Star, kernel_main_invariant),false);
        for(ParallelBarrier barrier : barrier_map.keySet()){
//          Warning("computing possible predecessors of barrier %d:",barrier_map.get(barrier));
          Set<Integer> preds=new HashSet<Integer>();
          ASTNode tmp=create.constant(false);
          find_predecessors(preds,barrier);
          for(Integer i : preds){
//            Warning("    %d",i);
            tmp=create.expression(StandardOperator.Or,tmp,
                  create.expression(StandardOperator.EQ,
                      create.local_name("last_barrier"),
                      create.constant(i.intValue())
                  )
                );
          }
          barrier_cb.requires(create.expression(StandardOperator.Implies,
              create.expression(StandardOperator.EQ,
                  create.local_name("this_barrier"),
                  create.constant(barrier_map.get(barrier).intValue())
              ),
              tmp
          ),false);
        }
        barrier_cb.ensures(create.expression(StandardOperator.EQ,
            create.reserved_name("\\result"),create.local_name("this_barrier")),false);
        barrier_cb.ensures(create_resources(create.reserved_name("\\result")),false);
        barrier_cb.ensures(create.fold(StandardOperator.Star, constraint),false);
        barrier_cb.ensures(create.fold(StandardOperator.Star, kernel_main_invariant),false);
        ArrayList<DeclarationStatement> barrier_decls=new ArrayList<DeclarationStatement>();
        barrier_decls.add(create.field_decl("tcount", create.primitive_type(Sort.Integer)));
        barrier_decls.add(create.field_decl("gsize", create.primitive_type(Sort.Integer)));
        barrier_decls.add(create.field_decl("tid", create.primitive_type(Sort.Integer)));
        barrier_decls.add(create.field_decl("gid", create.primitive_type(Sort.Integer)));
        barrier_decls.add(create.field_decl("lid", create.primitive_type(Sort.Integer)));
        barrier_decls.add(create.field_decl("this_barrier", create.primitive_type(Sort.Integer)));
        barrier_decls.add(create.field_decl("last_barrier", create.primitive_type(Sort.Integer)));
        for(DeclarationStatement d:private_decls){
          barrier_decls.add(copy_rw.rewrite(d));
        }
        res.add_dynamic(create.method_decl(
            create.primitive_type(Sort.Integer),
            barrier_cb.getContract(),
            base_name+"_barrier",
            barrier_decls.toArray(args),
            null
        ));
        for (int i=1;i<=barrier_no;i++){
          ContractBuilder resource_cb;
          
          ASTNode min;
          if (Configuration.assume_single_group.get()){
            min=create.local_name("gsize");
          } else {
            min=create.expression(StandardOperator.Mult,create.local_name("gsize"),
                create.expression(StandardOperator.Plus,create.local_name("gid"),create.constant(1)));
            min=create.expression(StandardOperator.ITE,
                create.expression(StandardOperator.LT,create.local_name("tcount"),min),
                create.local_name("tcount"),min
            );
          }
          ASTNode guard=create.expression(StandardOperator.And,
              create.expression(StandardOperator.LTE,
                  create.expression(StandardOperator.Mult,create.local_name("gid"),create.local_name("gsize")),
                  create.local_name("tid")),
              create.expression(StandardOperator.LT,create.local_name("tid"),min)
          );
          
          ASTNode local_guard=create.expression(StandardOperator.And,
              create.expression(StandardOperator.LTE,create.constant(0),create.local_name("lid")),
              create.expression(StandardOperator.LT,create.local_name("lid"),create.local_name("gsize"))
          );
          resource_cb=new ContractBuilder();
          resource_cb.requires(create.fold(StandardOperator.Star, group_inv));
          for(ASTNode claim:resources.get(0)){
            if (ASTUtils.find_name(claim,"lid")){
              resource_cb.requires(create.starall(
                  copy_rw.rewrite(local_guard),
                  copy_rw.rewrite(claim),
                  create.field_decl("lid", create.primitive_type(Sort.Integer))
              ));
            } else {
              resource_cb.requires(create.starall(
                  copy_rw.rewrite(guard),
                  copy_rw.rewrite(claim),
                  create.field_decl("tid", create.primitive_type(Sort.Integer))
              ));
            }
          }

          resource_cb.ensures(create.fold(StandardOperator.Star, group_inv));
          resource_cb.ensures(create.fold(StandardOperator.Star, constraint));
          for(ASTNode claim:resources.get(i)){
            resource_cb.ensures(create.starall(
                copy_rw.rewrite(guard),
                copy_rw.rewrite(claim),
                create.field_decl("tid", create.primitive_type(Sort.Integer))
            ));
          }
          ArrayList<DeclarationStatement> resource_decls=new ArrayList<DeclarationStatement>();
          resource_decls.add(create.field_decl("tcount", create.primitive_type(Sort.Integer)));
          resource_decls.add(create.field_decl("gsize", create.primitive_type(Sort.Integer)));
          resource_decls.add(create.field_decl("gid", create.primitive_type(Sort.Integer)));
          for(DeclarationStatement d:private_decls){
            resource_decls.add(copy_rw.rewrite(d));
          }
          res.add_dynamic(create.method_decl(
              create.primitive_type(Sort.Void),
              resource_cb.getContract(),
              base_name+"_resources_of_"+i,
              resource_decls.toArray(new DeclarationStatement[0]),
              create.block()
          ));
        }
        
        for (int i=1;i<=barrier_no;i++){
          ContractBuilder cb=new ContractBuilder();
          ArrayList<DeclarationStatement> post_check_decls=new ArrayList<DeclarationStatement>();
          post_check_decls.add(create.field_decl("tcount", create.primitive_type(Sort.Integer)));
          post_check_decls.add(create.field_decl("gsize", create.primitive_type(Sort.Integer)));
          post_check_decls.add(create.field_decl("tid", create.primitive_type(Sort.Integer)));
          post_check_decls.add(create.field_decl("gid", create.primitive_type(Sort.Integer)));
          post_check_decls.add(create.field_decl("lid", create.primitive_type(Sort.Integer)));
          for(DeclarationStatement d:private_decls){
            post_check_decls.add(copy_rw.rewrite(d));
          }

          Hashtable<NameExpression,ASTNode> map=new Hashtable();
          map.put(create.local_name("tid"),create.local_name("_x_tid"));
          map.put(create.local_name("lid"),create.local_name("_x_lid"));
          Substitution sigma=new Substitution(source(),map);

          ASTNode min;
          if (Configuration.assume_single_group.get()){
            min=create.local_name("gsize");
          } else {
              min=create.expression(StandardOperator.Mult,create.local_name("gsize"),
                  create.expression(StandardOperator.Plus,create.local_name("gid"),create.constant(1)));
              min=create.expression(StandardOperator.ITE,
                  create.expression(StandardOperator.LT,create.local_name("tcount"),min),
                  create.local_name("tcount"),min
              );
          }
          
          ASTNode guard=create.expression(StandardOperator.And,
              create.expression(StandardOperator.LTE,
                  create.expression(StandardOperator.Mult,create.local_name("gid"),create.local_name("gsize")),
                  create.local_name("_x_tid")),
              create.expression(StandardOperator.LT,create.local_name("_x_tid"),min)
          );
          
          ASTNode local_guard=create.expression(StandardOperator.And,
              create.expression(StandardOperator.LTE,create.constant(0),create.local_name("_x_lid")),
              create.expression(StandardOperator.LT,create.local_name("_x_lid"),create.local_name("gsize"))
          );
          
          cb.requires(create.fold(StandardOperator.Star, kernel_main_invariant));
          for(ASTNode claim:resources.get(0)){
            if (ASTUtils.find_name(claim,"lid")){
              cb.requires(create.starall(
                  copy_rw.rewrite(local_guard),
                  sigma.rewrite(claim),
                  create.field_decl("_x_lid", create.primitive_type(Sort.Integer))
              ));
            } else {
              cb.requires(create.starall(
                  copy_rw.rewrite(guard),
                  sigma.rewrite(claim),
                  create.field_decl("_x_tid", create.primitive_type(Sort.Integer))
              ));
            }
          }
          //cb.requires(create.fold(StandardOperator.Star, barrier_pre.get(i)));
          for(ASTNode claim:barrier_pre.get(i)){
              if (ASTUtils.find_name(claim,"lid")){
                cb.requires(create.forall(
                    copy_rw.rewrite(local_guard),
                    sigma.rewrite(claim),
                    create.field_decl("_x_lid", create.primitive_type(Sort.Integer))
                ));
              } else if (ASTUtils.find_name(claim,"tid")){
                cb.requires(create.forall(
                    copy_rw.rewrite(guard),
                    sigma.rewrite(claim),
                    create.field_decl("_x_tid", create.primitive_type(Sort.Integer))
                ));
              } else {
            	cb.requires(copy_rw.rewrite(claim));
              }
            }
          cb.ensures(create.fold(StandardOperator.Star,kernel_main_invariant));
          cb.ensures(create.fold(StandardOperator.Star,constraint));
          cb.ensures(create.fold(StandardOperator.Star, resources.get(i)));
          cb.ensures(create.fold(StandardOperator.Star, barrier_post.get(i)));
          res.add_dynamic(create.method_decl(
              create.primitive_type(Sort.Void),
              cb.getContract(),
              base_name+"_post_check_"+i,
              post_check_decls.toArray(new DeclarationStatement[0]),
              create.block()
          ));          
        }
      }
      result=res;
    } else {
      result=copy_rw.rewrite(cl);
    }
  }

  @Override
  public void visit(LoopStatement s) {
    //checkPermission(s);
    LoopStatement res=new LoopStatement();
    ASTNode tmp;
    tmp=s.getInitBlock();
    if (tmp!=null) res.setInitBlock(tmp.apply(this));
    tmp=s.getUpdateBlock();
    if (tmp!=null) res.setUpdateBlock(tmp.apply(this));
    tmp=s.getEntryGuard();
    if (tmp!=null) res.setEntryGuard(tmp.apply(this));
    tmp=s.getExitGuard();
    if (tmp!=null) res.setExitGuard(tmp.apply(this));
    for(ASTNode inv:kernel_main_invariant){
      res.appendInvariant(copy_rw.rewrite(inv));
    }
    for(ASTNode inv:s.getInvariants()){
      res.appendInvariant(inv.apply(this));
    }
    tmp=s.getBody();
    res.setBody(tmp.apply(this));
    res.set_before(rewrite(s.get_before()));
    res.set_after(rewrite(s.get_after()));
    res.setOrigin(s.getOrigin());
    ASTNode inv1=create.constant(false);
    Set<Integer> preds=new HashSet<Integer>();
    find_predecessors(preds,s.getBody());
    for(Integer i : preds){
//      Warning("    %d",i);
      inv1=create.expression(StandardOperator.Or,inv1,
            create.expression(StandardOperator.EQ,
                create.local_name("__last_barrier"),
                create.constant(i.intValue())
            )
          );
    }
    res.appendInvariant(inv1);
    result=res; return ;
  }

}
