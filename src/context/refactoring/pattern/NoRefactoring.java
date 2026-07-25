package context.refactoring.pattern;

import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SynchronizedStatement;
import org.eclipse.jdt.core.dom.TypeDeclaration;

public class NoRefactoring extends RefactoringPattern {
    @Override
    public boolean refactorMethod(TypeDeclaration td, MethodDeclaration md, CompilationUnit cu) {
        // 什么都不做
        return false;
    }

    @Override
    public boolean refactorBlock(TypeDeclaration td, MethodDeclaration md,
                                  SynchronizedStatement syncStmt, CompilationUnit cu) {
        return false;
    }
    
    @Override
    public String getPatternName() {
        return "不重构";
    }
}