package vct.col.rewrite;

import vct.col.ast.expr.NameExpression;
import vct.col.ast.generic.ASTNode;
import vct.col.ast.stmt.composite.BlockStatement;
import vct.col.ast.stmt.composite.LoopStatement;
import vct.col.ast.stmt.decl.*;
import vct.col.ast.stmt.terminal.ReturnStatement;
import vct.col.ast.type.ASTReserved;
import vct.col.ast.util.AbstractRewriter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class BreakReturnToGoto extends AbstractRewriter {
    public BreakReturnToGoto(ProgramUnit source) {
        super(source);
    }

    private Set<NameExpression> breakLabels = new HashSet<>();
    private Set<NameExpression> continueLabels = new HashSet<>();

    boolean encounteredReturn = false;
    String currentMethod = null;
    boolean replaceResultVar = false;

    /*
     * Later an out parameter will be added called sys__result where the return value will be stored.
     * (Or maybe with a different backend, not at all)
     * However here that does not exist yet. So we create a local variable where the result is stored,
     * and add a return statement at the end of the function that returns that local variable.
     * Then the backend pass can decide how exactly that value is returned.
     */
    public static final String localReturnVarName = "sys__local__result";

    public NameExpression generateBreakLabel(NameExpression label) {
        return create.label("__break_" + label.getName());
    }

    public NameExpression generateContinueLabel(NameExpression label) {
        return create.label("__continue_" + label.getName());
    }

    public NameExpression generateReturnLabel(String postfix) {
        return create.label("__return_" + postfix);
    }

    @Override
    public void post_visit(ASTNode node) {
        if (!(result instanceof LoopStatement)) {
            BlockStatement block = create.block();
            ArrayList<NameExpression> originalLabels = new ArrayList<>();

            for (NameExpression label : node.getLabels()) {
                NameExpression breakLabel = generateBreakLabel(label);
                ASTNode breakTarget = create.label_decl(breakLabel);

                // Only create break target if code actually breaks to label
                // While loops are labeled properly in their own visit
                if (breakLabels.contains(breakLabel)) {
                    block.add(breakTarget);
                    // Save it for later
                    originalLabels.add(label);
                    Debug("Creating block containing node and %s", breakTarget);
                }

                breakLabels.remove(breakLabel);
            }

            // Only replace the statement with a block if the labels are actually used
            if (block.size() > 0) {
                // Add the original label back to the replicated statement
                for (NameExpression label : originalLabels) {
                    result.addLabel(label);
                }
                block.prepend(result);
                result = block;
                // Clear the labels so they won't be copied in the post_visit call to the new generated block
                node.clearLabels();
            }
        }

        super.post_visit(node);
    }

    public void visit(Method method) {
        currentMethod = method.getName();

        super.visit(method);

        currentMethod = null;

        if (breakLabels.size() + continueLabels.size() != 0) {
            Warning("Some break or continue labels were not deleted, even though they should be. This indicates a logic error.");
        }

        if (encounteredReturn) {
            NameExpression returnLabel = generateReturnLabel(method.getName());
            ASTSpecial labelStatement = create.label_decl(returnLabel);
            Method resultMethod = (Method) result;
            BlockStatement body = (BlockStatement) resultMethod.getBody();

            // Always at least add a return label
            // TODO (Bob): Account for overloading in the label name, since if this pass is called early overloading isn't encoded yet
            body.append(labelStatement);

            if (!method.getReturnType().isVoid()) {
                // Add a local return variable and return statement as well if we're actually return a value
                DeclarationStatement localReturnVariable = create.field_decl(localReturnVarName, rewrite(method.getReturnType()));
                ReturnStatement finalReturn = create.return_statement(create.unresolved_name(localReturnVarName));

                body.prepend(localReturnVariable);
                body.append(finalReturn);
            }
        }

        encounteredReturn = false;
    }

    public void visit(LoopStatement loopStatement) {
        super.visit(loopStatement);

        BlockStatement breakTargets = create.block();

        for (NameExpression label : loopStatement.getLabels()) {
            // The targets are where to jump if "break/continue labelX" is called. Naturally the targets are not the same
            // location as the label itself, hence we need separate target labels.

            NameExpression continueLabel = generateContinueLabel(label);
            ASTNode continueTarget = create.label_decl(continueLabel);
            // Only create continue target if code actually continues to label
            if (continueLabels.contains(continueLabel)) {
                BlockStatement blockStatement = (BlockStatement) ((LoopStatement) result).getBody();
                blockStatement.prepend(continueTarget);
                Debug("Pepending %s to while loop", continueTarget);
            }

            NameExpression breakLabel = generateBreakLabel(label);
            ASTNode breakTarget = create.label_decl(breakLabel);
            // Only create break target if code actually breaks to label
            if (breakLabels.contains(breakLabel)) {
                breakTargets.add(breakTarget);
                Debug("Appending %s to while loop", breakTarget);
            }

            breakLabels.remove(breakLabel);
            continueLabels.remove(continueLabel);
        }

        // If there were one or more break targets, replace the resulting while loop with a block containing the while
        // loop, followed by all the break targets
        if (breakTargets.size() > 0) {
            // This is needed because the post_visit step usually does fixate, but if we put our while loop inside a block
            // then the post_visit won't call fixate on the members, so we have to do it ourselves...?
            ((LoopStatement) result).fixate();
            breakTargets.prepend(result);
            result = breakTargets;
        }
    }

    public void visit(ASTSpecial special) {
        switch (special.kind) {
            default:
                super.visit(special);
                break;
            case Break:
                visitBreak(special);
                break;
            case Continue:
                visitContinue(special);
                break;
        }
    }

    private void visitContinue(ASTSpecial continueStatement) {
        Abort("Continue not supported; should've been translated into break");
    }

    public void visitBreak(ASTSpecial breakStatement) {
        NameExpression label = (NameExpression) breakStatement.args[0];
        NameExpression newLabel = generateBreakLabel(label);
        result = create.jump(newLabel);
        breakLabels.add(newLabel);
    }

    public void visit(NameExpression nameExpression) {
        // Reusing the pass like this is very messy, so maybe refactor it to separately use
        // a substitution pass. But if this is the only thing we're doing it's fine.
        if (replaceResultVar) {
            if (nameExpression.isReserved(ASTReserved.Result)) {
                result = create.unresolved_name(localReturnVarName);
            } else {
                super.visit(nameExpression);
            }
        } else {
            super.visit(nameExpression);
        }
    }

    public void visit(ReturnStatement returnStatement) {
        BlockStatement res=create.block();

        if (returnStatement.getExpression() != null){
            res.add(create.assignment(create.local_name(localReturnVarName), rewrite(returnStatement.getExpression())));
        }

        for(ASTNode n : returnStatement.get_after()) {
            res.add(rewrite(n));
        }

        if (current_method().getContract()!=null){
            replaceResultVar = true;
            res.add(create.special(ASTSpecial.Kind.Assert,rewrite(current_method().getContract().post_condition)));
            replaceResultVar = false;
        }

        res.add(create.jump(generateReturnLabel(current_method().getName())));

        result = res;

        encounteredReturn = true;
    }
}
