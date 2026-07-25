package context.refactoring.pattern;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.dom.*;
import context.refactoring.CriticalSectionModel;  // 根据实际包路径调整

public class ReadWriteLockRefactoring extends RefactoringPattern {

    // ==== 原有无模型版本（保留，但可以简化调用带默认写锁）====
    @Override
    public boolean refactorMethod(TypeDeclaration td, MethodDeclaration md, CompilationUnit cu) {
    	
        return refactorMethod(td, md, cu, null); // 无模型时默认写锁
    }
    

    // ==== 带模型版本（核心）====
    @Override
    public boolean refactorMethod(TypeDeclaration td, MethodDeclaration md, CompilationUnit cu,
                                   CriticalSectionModel model) {
        boolean useReadLock = false;

        // 如果模型存在且 readWritePattern 全为 'r'，则使用读锁；否则写锁
        if (model != null && model.readWritePattern != null) {
            String pattern = model.readWritePattern.trim();
            useReadLock = !pattern.contains("w") && !pattern.contains("W");
        }

        return refactorMethodInternal(td, md, cu, useReadLock);
    }

    // ==== 通用实现（含锁类型参数）====
    private boolean refactorMethodInternal(TypeDeclaration td, MethodDeclaration md,
                                            CompilationUnit cu, boolean useReadLock) {
        AST ast = cu.getAST();
        String lockName = addReadWriteLockFieldIfNeeded(td, ast);
        String lockMethod = useReadLock ? "readLock" : "writeLock";

        // 移除 synchronized 修饰符
        //md.modifiers().removeIf(m -> ((Modifier) m).isSynchronized());
        md.modifiers().removeIf(m -> 
        m instanceof Modifier && ((Modifier) m).isSynchronized()
    );
        

        Block body = md.getBody();
        List<Statement> oldStmts = new ArrayList<>(body.statements());
        body.statements().clear();

        body.statements().add(makeLockCallStmt(lockName, lockMethod, ast,true));

        TryStatement tryStmt = ast.newTryStatement();
        Block tryBlock = ast.newBlock();
        tryBlock.statements().addAll(oldStmts);
        tryStmt.setBody(tryBlock);

        Block finallyBlock = ast.newBlock();
        finallyBlock.statements().add(makeLockCallStmt(lockName, lockMethod, ast, false));
        tryStmt.setFinally(finallyBlock);

        body.statements().add(tryStmt);

        addImportIfMissing(cu, "java.util.concurrent.locks.ReadWriteLock");
        addImportIfMissing(cu, "java.util.concurrent.locks.ReentrantReadWriteLock");
        return true;
    }

    // ==== 同步块版本（同理）====
    @Override
    public boolean refactorBlock(TypeDeclaration td, MethodDeclaration md,
                                  SynchronizedStatement syncStmt, CompilationUnit cu) {
        return refactorBlock(td, md, syncStmt, cu, null);
    }

    @Override
    public boolean refactorBlock(TypeDeclaration td, MethodDeclaration md,
                                  SynchronizedStatement syncStmt, CompilationUnit cu,
                                  CriticalSectionModel model) {
        boolean useReadLock = false;
        if (model != null && model.readWritePattern != null) {
            String pattern = model.readWritePattern.trim();
            useReadLock = !pattern.contains("w") && !pattern.contains("W");
        }
        return refactorBlockInternal(td, md, syncStmt, cu, useReadLock);
    }

    private boolean refactorBlockInternal(TypeDeclaration td, MethodDeclaration md,
                                           SynchronizedStatement syncStmt, CompilationUnit cu,
                                           boolean useReadLock) {
    	AST ast = cu.getAST();
        String lockName = addReadWriteLockFieldIfNeeded(td, ast);
        String lockMethod = useReadLock ? "readLock" : "writeLock";

        ASTNode parent = syncStmt.getParent();

        List parentStatements;
        if (parent instanceof Block) {
            parentStatements = ((Block) parent).statements();
        } else if (parent instanceof SwitchStatement) {
            parentStatements = ((SwitchStatement) parent).statements();
        } else {
            System.err.println("暂不支持的 synchronized 父节点类型: "
                    + parent.getClass().getName());
            return false;
        }

        int idx = parentStatements.indexOf(syncStmt);
        if (idx < 0) {
            System.err.println("未能在父节点 statements 中找到 synchronized 语句");
            return false;
        }

        List<Statement> newStmts = new ArrayList<>();
        newStmts.add(makeLockCallStmt(lockName, lockMethod, ast, true));

        TryStatement tryStmt = ast.newTryStatement();
        Block tryBlock = ast.newBlock();

        List<Statement> innerStmts = new ArrayList<>(syncStmt.getBody().statements());
        syncStmt.getBody().statements().clear();
        tryBlock.statements().addAll(innerStmts);
        tryStmt.setBody(tryBlock);

        Block finallyBlock = ast.newBlock();
        finallyBlock.statements().add(makeLockCallStmt(lockName, lockMethod, ast, false));
        tryStmt.setFinally(finallyBlock);

        newStmts.add(tryStmt);

        parentStatements.remove(idx);
        parentStatements.addAll(idx, newStmts);

        addImportIfMissing(cu, "java.util.concurrent.locks.ReadWriteLock");
        addImportIfMissing(cu, "java.util.concurrent.locks.ReentrantReadWriteLock");
        return true;
    }

    // ===== 辅助方法（保持不变）=====
    private String addReadWriteLockFieldIfNeeded(TypeDeclaration td, AST ast) {
        String base = "rwLock";
        if (!hasField(td, base)) {
            VariableDeclarationFragment frag = ast.newVariableDeclarationFragment();
            frag.setName(ast.newSimpleName(base));
            ClassInstanceCreation init = ast.newClassInstanceCreation();
            init.setType(ast.newSimpleType(ast.newSimpleName("ReentrantReadWriteLock")));
            frag.setInitializer(init);
            FieldDeclaration fd = ast.newFieldDeclaration(frag);
            fd.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.PRIVATE_KEYWORD));
            fd.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.FINAL_KEYWORD));
            fd.setType(ast.newSimpleType(ast.newSimpleName("ReadWriteLock")));
            td.bodyDeclarations().add(0, fd);
        }
        return base;
    }

    private ExpressionStatement makeLockCallStmt(String lockName, String lockMethod, AST ast, boolean isLock) {
        // 首先构建 rwLock.writeLock() 或 rwLock.readLock()
        MethodInvocation getLock = ast.newMethodInvocation();
        getLock.setExpression(ast.newSimpleName(lockName));
        getLock.setName(ast.newSimpleName(lockMethod));
        // 然后调用 lock() 或 unlock()
        MethodInvocation finalCall = ast.newMethodInvocation();
        finalCall.setExpression(getLock);
        finalCall.setName(ast.newSimpleName(isLock ? "lock" : "unlock"));
        return ast.newExpressionStatement(finalCall);
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

    private void addImportIfMissing(CompilationUnit cu, String qualifiedName) {
        for (Object o : cu.imports()) {
            if (o instanceof ImportDeclaration
                    && ((ImportDeclaration) o).getName().getFullyQualifiedName().equals(qualifiedName))
                return;
        }
        ImportDeclaration id = cu.getAST().newImportDeclaration();
        id.setName(cu.getAST().newName(qualifiedName));
        cu.imports().add(id);
    }
}