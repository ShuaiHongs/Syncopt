package context.refactoring.pattern;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.dom.*;

public class LockSplittingRefactoring extends RefactoringPattern {

    @Override
    public boolean refactorMethod(TypeDeclaration td, MethodDeclaration md, CompilationUnit cu) {
        AST ast = cu.getAST();
        
        // 收集所有赋值语句（左端为 SimpleName）
        List<Assignment> assignments = new ArrayList<>();
        md.accept(new ASTVisitor() {
            @Override
            public boolean visit(Assignment node) {
                if (node.getLeftHandSide() instanceof SimpleName) {
                    assignments.add(node);
                }
                return true;
            }
        });
        
        if (assignments.size() < 2) return false;
        
        // 为每个赋值语句创建锁字段
        for (int i = 0; i < assignments.size(); i++) {
            addLockField(td, "lock" + i, ast);
        }
        
        md.modifiers().removeIf(m -> ((Modifier) m).isSynchronized());
        
        Block body = md.getBody();
        if (body == null) return false;
        
        List<Statement> oldStmts = new ArrayList<>(body.statements());
        body.statements().clear();
        
        int assignIdx = 0;
        for (Statement stmt : oldStmts) {
            if (stmt instanceof ExpressionStatement) {
                Expression expr = ((ExpressionStatement) stmt).getExpression();
                if (expr instanceof Assignment && assignments.contains(expr)) {
                    // 包裹在 synchronized(lockN) 中
                    SynchronizedStatement syncStmt = ast.newSynchronizedStatement();
                    syncStmt.setExpression(ast.newSimpleName("lock" + assignIdx));
                    Block syncBlock = ast.newBlock();
                    // 将原语句复制（解耦父子关系）
                    Statement copied = (Statement) ASTNode.copySubtree(ast, stmt);
                    syncBlock.statements().add(copied);
                    syncStmt.setBody(syncBlock);
                    body.statements().add(syncStmt);
                    assignIdx++;
                } else {
                    body.statements().add(stmt);
                }
            } else {
                body.statements().add(stmt);
            }
        }
        return true;
    }

    @Override
    public boolean refactorBlock(TypeDeclaration td, MethodDeclaration md,
                                  SynchronizedStatement syncStmt, CompilationUnit cu) {
        System.out.println("锁分解(block)暂未实现");
        return false;
    }

    private void addLockField(TypeDeclaration td, String baseName, AST ast) {
        String name = baseName;
        int i = 0;
        while (hasField(td, name)) {
            i++;
            name = baseName + "_" + i;
        }
        VariableDeclarationFragment frag = ast.newVariableDeclarationFragment();
        frag.setName(ast.newSimpleName(name));
        ClassInstanceCreation init = ast.newClassInstanceCreation();
    	init.setType(ast.newSimpleType(ast.newSimpleName("Object")));
    	frag.setInitializer(init);
        FieldDeclaration fd = ast.newFieldDeclaration(frag);
        fd.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.PRIVATE_KEYWORD));
        fd.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.FINAL_KEYWORD));
        fd.setType(ast.newSimpleType(ast.newSimpleName("Object")));
        td.bodyDeclarations().add(0, fd);
    }

    private boolean hasField(TypeDeclaration td, String name) {
        for (FieldDeclaration fd : td.getFields()) {
            for (Object o : fd.fragments()) {
                if (((VariableDeclarationFragment) o).getName().getIdentifier().equals(name))
                    return true;
            }
        }
        return false;
    }
}